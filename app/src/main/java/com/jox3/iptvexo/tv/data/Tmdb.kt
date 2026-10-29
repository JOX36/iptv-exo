package com.jox3.iptvexo.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import com.jox3.iptvexo.NetClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Información de TMDB (themoviedb.org) para completar lo que el servidor no trae:
 * calificación, clasificación por edad, reparto con fotos, tráiler, sinopsis en español,
 * fondo y carátulas faltantes.
 *
 * Usa el token de lectura de la app (va integrado al compilar). Acepta también una "API Key"
 * v3 (32 caracteres) o un token v4 (largo).
 * Todo lo consultado se guarda en el equipo: cada título se pide una sola vez.
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
        /** El tráiler es oficial del estudio (casi nunca trae anuncios): solo esos van de fondo. */
        val trailerOfficial: Boolean = false,
    ) {
        val ratingText: String get() = if (votes > 0 && rating > 0f) String.format(java.util.Locale("es"), "%.1f", rating) else ""
    }

    private const val PREFS = "tmdb"
    private const val CACHE = "tmdb_cache"
    private const val IMG = "https://image.tmdb.org/t/p/"
    private val gate = Semaphore(4)      // como mucho 4 consultas a la vez
    private val mem = HashMap<String, Info?>()
    private val posters = HashMap<String, String>()
    /** Sube cuando cambia la clave: las pantallas vuelven a consultar. */
    val version = mutableIntStateOf(0)

    /** Token de la app (integrado al compilar desde el secreto TMDB_TOKEN). */
    @Suppress("UNUSED_PARAMETER")
    fun key(ctx: Context): String = com.jox3.iptvexo.BuildConfig.TMDB_TOKEN.trim()
    fun enabled(ctx: Context) = key(ctx).length >= 20

    fun setKey(ctx: Context, k: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", k.trim()).apply()
        // Con otra clave, lo que antes no se encontró se vuelve a intentar
        synchronized(mem) { mem.clear() }
        synchronized(posters) { posters.clear() }
        ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().clear().apply()
        version.intValue++
    }

    /** Prueba la clave: null = funciona; texto = el problema. */
    suspend fun test(ctx: Context, k: String): String? = withContext(Dispatchers.IO) {
        try {
            val r = get(k, "configuration")
            if (r.has("images")) null else "TMDB no aceptó la clave"
        } catch (e: Exception) { e.message ?: "No se pudo conectar con TMDB" }
    }

    // ── Consultas ──

    suspend fun movie(ctx: Context, name: String): Info? = details(ctx, "movie", name)
    suspend fun series(ctx: Context, name: String): Info? = details(ctx, "tv", name)

    /** Carátula para un título sin imagen (o con la imagen rota). "" = no se encontró. */
    suspend fun posterFor(ctx: Context, name: String): String {
        if (!enabled(ctx) || name.isBlank()) return ""
        val ck = "p:" + TvRepo.titleKey(name) + "|" + yearOf(name)
        synchronized(posters) { posters[ck] }?.let { return it }
        val prefs = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        prefs.getString(ck, null)?.let { synchronized(posters) { posters[ck] = it }; return it }
        val url = runCatching {
            gate.withPermit {
                withContext(Dispatchers.IO) {
                    val res = search(key(ctx), "multi", name)
                    res?.optString("poster_path")?.takeIf { it.isNotBlank() && it != "null" }?.let { IMG + "w342" + it }.orEmpty()
                }
            }
        }.getOrNull() ?: return ""      // error de red: no se guarda, se reintenta otro día
        synchronized(posters) { posters[ck] = url }
        prefs.edit().putString(ck, url).apply()
        return url
    }

    private suspend fun details(ctx: Context, type: String, name: String): Info? {
        if (!enabled(ctx) || name.isBlank()) return null
        val ck = type + ":" + TvRepo.titleKey(name) + "|" + yearOf(name)
        synchronized(mem) { if (mem.containsKey(ck)) return mem[ck] }
        val prefs = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        prefs.getString(ck, null)?.let { raw ->
            val info = if (raw.isEmpty()) null else runCatching { parse(type, JSONObject(raw)) }.getOrNull()
            synchronized(mem) { mem[ck] = info }
            return info
        }
        val k = key(ctx)
        val json = try {
            gate.withPermit {
                withContext(Dispatchers.IO) {
                    val hit = search(k, type, name) ?: return@withContext null
                    val id = hit.optInt("id")
                    val extra = if (type == "movie") "credits,videos,release_dates" else "credits,videos,content_ratings"
                    get(k, "$type/$id", "language=es-MX&append_to_response=$extra&include_video_language=es,en,null")
                }
            }
        } catch (e: Exception) { return null }   // error de red: se reintenta la próxima vez
        val info = json?.let { runCatching { parse(type, it) }.getOrNull() }
        synchronized(mem) { mem[ck] = info }
        prefs.edit().putString(ck, json?.toString() ?: "").apply()
        return info
    }

    /**
     * Títulos que TMDB recomienda a quien vio [name] ("recommendations" y, si son pocos,
     * "similar"), en español y en su idioma original, en orden de relevancia.
     * Se guardan 7 días. Lista vacía si no hay TMDB o no se encontró.
     */
    suspend fun related(ctx: Context, type: String, name: String): List<String> {
        if (!enabled(ctx) || name.isBlank()) return emptyList()
        val ck = "rel:" + type + ":" + TvRepo.titleKey(name) + "|" + yearOf(name)
        val prefs = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        prefs.getString(ck, null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                if (System.currentTimeMillis() - o.optLong("t") < 7L * 86_400_000) {
                    val a = o.optJSONArray("titles") ?: org.json.JSONArray()
                    return (0 until a.length()).map { a.optString(it) }
                }
            }
        }
        val info = details(ctx, type, name) ?: return emptyList()
        val k = key(ctx)
        val titles = try {
            gate.withPermit {
                withContext(Dispatchers.IO) {
                    val out = ArrayList<String>()
                    fun collect(o: JSONObject) {
                        val res = o.optJSONArray("results") ?: return
                        for (i in 0 until res.length()) {
                            val r = res.optJSONObject(i) ?: continue
                            listOf("title", "name", "original_title", "original_name").forEach { f ->
                                r.optString(f).takeIf { it.isNotBlank() && it != "null" }?.let { out += it }
                            }
                        }
                    }
                    collect(get(k, "$type/${info.id}/recommendations", "language=es-MX&page=1"))
                    if (out.size < 30) collect(get(k, "$type/${info.id}/similar", "language=es-MX&page=1"))
                    out.distinct()
                }
            }
        } catch (e: Exception) { return emptyList() }
        val arr = org.json.JSONArray(); titles.forEach { arr.put(it) }
        prefs.edit().putString(ck, JSONObject().put("t", System.currentTimeMillis()).put("titles", arr).toString()).apply()
        return titles
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
        NetClient.api().newCall(b.build()).execute().use { r ->
            if (r.code == 401) throw java.io.IOException("La clave de TMDB no es válida")
            if (!r.isSuccessful) throw java.io.IOException("TMDB respondió HTTP ${r.code}")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    private fun parse(type: String, o: JSONObject): Info {
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
    private fun cleanTitle(name: String): String = name
        .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
        .replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|1080p|720p|latino|castellano|subtitulad[ao]|dual|vose|lat)\\b"), " ")
        .replace(Regex("^[A-Z]{2,3}\\s*[|:-]\\s*"), "")   // "ES | Título", "LAT - Título"
        .replace(Regex("\\b(19|20)\\d{2}\\s*$"), " ")
        .replace(Regex("[|_]+"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('-', ':', '.').trim()

    private fun yearOf(name: String): String = Regex("\\b(19[2-9]\\d|20[0-4]\\d)\\b").findAll(name).lastOrNull()?.value.orEmpty()

    // ── Tráiler de fondo (ajuste) ──
    fun backgroundTrailers(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("bg_trailer", true)
    fun setBackgroundTrailers(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("bg_trailer", on).apply()

    /** En el celular, de fondo solo con WiFi (no gasta datos). En la TV siempre. */
    fun onUnmeteredNetwork(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.isActiveNetworkMetered.not()
    }.getOrDefault(false)

    /** Abre el tráiler en la app de YouTube (o en el navegador si no está). */
    fun openTrailer(ctx: Context, key: String) {
        if (key.isBlank()) return
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(app) } catch (e: Exception) {
            try { ctx.startActivity(web) } catch (e2: Exception) {
                android.widget.Toast.makeText(ctx, "Instala YouTube para ver el tráiler", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
