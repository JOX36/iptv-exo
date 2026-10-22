package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.jox3.tvdesktop.BuildSecrets
import com.jox3.tvdesktop.core.DiskCache
import com.jox3.tvdesktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder

/**
 * Información de TMDB (themoviedb.org) para completar lo que el servidor no trae:
 * calificación, clasificación por edad, reparto con fotos, tráiler, sinopsis en español,
 * fondo y carátulas faltantes. Misma lógica que la app Android.
 *
 * Usa el token de lectura que se integra al compilar (secreto TMDB_TOKEN); nunca está en el código.
 * Lo consultado se guarda en el equipo (cache/tmdb.json): cada título se pide una sola vez.
 */
object Tmdb {
    data class Person(val name: String, val role: String, val photo: String)

    data class Info(
        val id: Int,
        val title: String,
        val overview: String,
        val rating: Float,          // 0–10
        val votes: Int,
        val year: String,
        val runtimeMin: Int,
        val genres: String,
        val poster: String,
        val backdrop: String,
        val certification: String,  // "+12", "PG-13"…
        val cast: List<Person>,
        val director: String,
        val trailerKey: String,     // YouTube
        val trailerOfficial: Boolean = false,
    ) {
        val ratingText: String get() = if (votes > 0 && rating > 0f) String.format(java.util.Locale.forLanguageTag("es"), "%.1f", rating) else ""
    }

    private const val IMG = "https://image.tmdb.org/t/p/"
    private val gate = Semaphore(4)      // como mucho 4 consultas a la vez
    private val mem = HashMap<String, Info?>()
    private val cache by lazy { DiskCache("tmdb", maxEntries = 4000) }
    /** Sube cuando hay información nueva: las pantallas vuelven a mirar. */
    val version = mutableIntStateOf(0)

    fun key(): String = BuildSecrets.TMDB_TOKEN.trim()
    fun enabled() = key().length >= 20

    // ── Consultas ──

    suspend fun movie(name: String): Info? = details("movie", name)
    suspend fun series(name: String): Info? = details("tv", name)

    /** Carátula para un título sin imagen (o con la imagen rota). "" = no se encontró. */
    suspend fun posterFor(name: String): String {
        if (!enabled() || name.isBlank()) return ""
        val ck = "p:" + TvRepo.titleKey(name) + "|" + yearOf(name)
        cache.get(ck)?.let { return it }
        val url = runCatching {
            gate.withPermit {
                withContext(Dispatchers.IO) {
                    val res = search(key(), "multi", name)
                    res?.optString("poster_path")?.takeIf { it.isNotBlank() && it != "null" }?.let { IMG + "w342" + it }.orEmpty()
                }
            }
        }.getOrNull() ?: return ""      // error de red: no se guarda, se reintenta otro día
        cache.put(ck, url)
        return url
    }

    private suspend fun details(type: String, name: String): Info? {
        if (!enabled() || name.isBlank()) return null
        val ck = type + ":" + TvRepo.titleKey(name) + "|" + yearOf(name)
        synchronized(mem) { if (mem.containsKey(ck)) return mem[ck] }
        cache.get(ck)?.let { raw ->
            val info = if (raw.isEmpty()) null else runCatching { parse(type, JSONObject(raw)) }.getOrNull()
            synchronized(mem) { mem[ck] = info }
            return info
        }
        val k = key()
        val json = try {
            gate.withPermit {
                withContext(Dispatchers.IO) {
                    val hit = search(k, type, name) ?: return@withContext null
                    val id = hit.optInt("id")
                    val extra = if (type == "movie") "credits,videos,release_dates" else "credits,videos,content_ratings"
                    get(k, "$type/$id", "language=es-MX&append_to_response=$extra&include_video_language=es,en,null")
                }
            }
        } catch (e: Exception) {
            Log.w("TMDB no respondió para \"$name\": ${e.message}")
            return null   // error de red: se reintenta la próxima vez
        }
        val info = json?.let { runCatching { parse(type, it) }.getOrNull() }
        synchronized(mem) { mem[ck] = info }
        cache.put(ck, json?.toString() ?: "")
        return info
    }

    /** Primer resultado de la búsqueda, usando el año del nombre si lo trae. */
    private fun search(k: String, type: String, name: String): JSONObject? {
        val clean = cleanTitle(name)
        if (clean.isBlank()) return null
        val year = yearOf(name)
        val yParam = when { year.isEmpty() -> ""; type == "movie" -> "&year=$year"; type == "tv" -> "&first_air_date_year=$year"; else -> "" }
        val q = URLEncoder.encode(clean, "UTF-8")
        var res = get(k, "search/$type", "language=es-MX&include_adult=false&query=$q$yParam").optJSONArray("results")
        // Con el año a veces no aparece (el servidor lo tiene mal): se busca sin él
        if ((res == null || res.length() == 0) && yParam.isNotEmpty()) {
            res = get(k, "search/$type", "language=es-MX&include_adult=false&query=$q").optJSONArray("results")
        }
        if (res == null || res.length() == 0) return null
        if (type == "multi") {
            for (i in 0 until res.length()) {
                val o = res.optJSONObject(i) ?: continue
                if (o.optString("media_type") in setOf("movie", "tv")) return o
            }
            return null
        }
        return res.optJSONObject(0)
    }

    private fun get(k: String, path: String, query: String = ""): JSONObject {
        val v4 = k.length > 40
        val url = buildString {
            append("https://api.themoviedb.org/3/").append(path).append('?')
            if (!v4) append("api_key=").append(k).append('&')
            append(query)
        }
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (v4) b.header("Authorization", "Bearer $k")
        Net.api.newCall(b.build()).execute().use { r ->
            if (r.code == 401) throw java.io.IOException("La clave de TMDB no es válida")
            if (!r.isSuccessful) throw java.io.IOException("TMDB respondió HTTP ${r.code}")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    internal fun parse(type: String, o: JSONObject): Info {
        fun img(p: String?, size: String) = p?.takeIf { it.isNotBlank() && it != "null" }?.let { IMG + size + it }.orEmpty()
        val credits = o.optJSONObject("credits")
        val castArr = credits?.optJSONArray("cast") ?: JSONArray()
        val cast = (0 until minOf(castArr.length(), 12)).mapNotNull { i ->
            val p = castArr.optJSONObject(i) ?: return@mapNotNull null
            Person(p.optString("name"), p.optString("character"), img(p.optString("profile_path"), "w185"))
        }
        val crew = credits?.optJSONArray("crew") ?: JSONArray()
        val director = (0 until crew.length()).mapNotNull { crew.optJSONObject(it) }
            .filter { it.optString("job") == "Director" }.joinToString(", ") { it.optString("name") }
            .ifBlank { (o.optJSONArray("created_by") ?: JSONArray()).let { a -> (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name").orEmpty() } } }
        // Tráiler: el oficial en español si hay; si no, el primero de YouTube
        val vids = o.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
        val list = (0 until vids.length()).mapNotNull { vids.optJSONObject(it) }.filter { it.optString("site") == "YouTube" }
        // Orden: oficial en español → oficial en inglés → cualquier oficial → no oficial en español → el primero.
        // Los oficiales los suben los estudios: casi nunca tienen anuncios.
        fun isTrailer(v: JSONObject) = v.optString("type") == "Trailer" || v.optString("type") == "Teaser"
        val off = list.filter { it.optBoolean("official") && isTrailer(it) }
        val pick = off.firstOrNull { it.optString("iso_639_1") == "es" && it.optString("type") == "Trailer" }
            ?: off.firstOrNull { it.optString("iso_639_1") == "en" && it.optString("type") == "Trailer" }
            ?: off.firstOrNull()
        val trailerObj = pick ?: list.firstOrNull { isTrailer(it) && it.optString("iso_639_1") == "es" } ?: list.firstOrNull { isTrailer(it) } ?: list.firstOrNull()
        val trailer = trailerObj?.optString("key").orEmpty()
        val genres = (o.optJSONArray("genres") ?: JSONArray()).let { a -> (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name").orEmpty() } }
        val date = if (type == "movie") o.optString("release_date") else o.optString("first_air_date")
        val runtime = if (type == "movie") o.optInt("runtime")
            else (o.optJSONArray("episode_run_time")?.optInt(0) ?: 0)
        return Info(
            id = o.optInt("id"),
            title = o.optString(if (type == "movie") "title" else "name"),
            overview = o.optString("overview"),
            rating = o.optDouble("vote_average", 0.0).toFloat(),
            votes = o.optInt("vote_count"),
            year = date.take(4),
            runtimeMin = runtime,
            genres = genres,
            poster = img(o.optString("poster_path"), "w500"),
            backdrop = img(o.optString("backdrop_path"), "w1280"),
            certification = certification(type, o),
            cast = cast,
            director = director,
            trailerKey = trailer,
            trailerOfficial = pick != null,
        )
    }

    /** Clasificación por edad: la de Colombia, México o España si está; si no, la de EE. UU. */
    private fun certification(type: String, o: JSONObject): String {
        val order = listOf("CO", "MX", "ES", "US")
        val found = HashMap<String, String>()
        if (type == "movie") {
            val arr = o.optJSONObject("release_dates")?.optJSONArray("results") ?: return ""
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val rd = c.optJSONArray("release_dates") ?: continue
                for (j in 0 until rd.length()) {
                    val cert = rd.optJSONObject(j)?.optString("certification").orEmpty()
                    if (cert.isNotBlank()) { found[c.optString("iso_3166_1")] = cert; break }
                }
            }
        } else {
            val arr = o.optJSONObject("content_ratings")?.optJSONArray("results") ?: return ""
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                c.optString("rating").takeIf { it.isNotBlank() }?.let { found[c.optString("iso_3166_1")] = it }
            }
        }
        return order.firstNotNullOfOrNull { found[it] }.orEmpty()
    }

    /** Título sin año, etiquetas ni calidad: "Rocky II (1979) [4K]" → "Rocky II". */
    internal fun cleanTitle(name: String): String = name
        .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
        .replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|1080p|720p|latino|castellano|subtitulad[ao]|dual|vose|lat)\\b"), " ")
        .replace(Regex("^[A-Z]{2,3}\\s*[|:-]\\s*"), "")   // "ES | Título", "LAT - Título"
        .replace(Regex("\\b(19|20)\\d{2}\\s*$"), " ")
        .replace(Regex("[|_]+"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('-', ':', '.').trim()

    internal fun yearOf(name: String): String = Regex("\\b(19[2-9]\\d|20[0-4]\\d)\\b").findAll(name).lastOrNull()?.value.orEmpty()

    /** Abre el tráiler en YouTube con el navegador del PC. */
    fun openTrailer(key: String): Boolean {
        if (key.isBlank()) return false
        return try {
            Desktop.getDesktop().browse(URI("https://www.youtube.com/watch?v=" + URLEncoder.encode(key, "UTF-8")))
            true
        } catch (e: Exception) {
            Log.w("No se pudo abrir el tráiler", e); false
        }
    }
}
