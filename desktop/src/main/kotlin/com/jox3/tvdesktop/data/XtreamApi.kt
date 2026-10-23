package com.jox3.tvdesktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStreamReader
import java.util.Base64
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import javax.net.ssl.SSLException
import com.jox3.tvdesktop.core.Log

/** Cliente de la API Xtream Codes (player_api.php). Misma lógica que la app Android. */
class XtreamApi(val account: Account) {

    private val host = account.host.trimEnd('/')

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(action: String?, extra: String = ""): String = buildString {
        append(host).append("/player_api.php?username=").append(enc(account.user))
        append("&password=").append(enc(account.pass))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    /**
     * Hace la petición y entrega la respuesta a [read] (en un hilo de red). Traduce los
     * errores de red a mensajes claros y los anota en el registro (sin contraseñas).
     */
    private suspend fun <T> request(url: String, read: (okhttp3.ResponseBody) -> T): T = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", Net.API_USER_AGENT)
            .header("Accept", "application/json, */*")
            .build()
        try {
            Net.api.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException(
                    if (r.code == 401 || r.code == 403) "Acceso denegado: revisa usuario y contraseña, o la cuenta está bloqueada (HTTP ${r.code})"
                    else "El servidor respondió con un error (HTTP ${r.code})"
                )
                read(r.body ?: throw IOException("Respuesta vacía del servidor"))
            }
        } catch (e: UnknownHostException) {
            Log.w("Servidor no encontrado: $url")
            throw IOException("No se encontró el servidor. Revisa la dirección o tu internet.")
        } catch (e: SocketTimeoutException) {
            Log.w("Tiempo agotado: $url")
            throw IOException("El servidor no respondió a tiempo. Intenta de nuevo.")
        } catch (e: ConnectException) {
            Log.w("Conexión rechazada: $url")
            throw IOException("El servidor rechazó la conexión. Revisa la dirección y el puerto.")
        } catch (e: SSLException) {
            Log.w("Error de conexión segura: $url", e)
            throw IOException("Falló la conexión segura (https). Prueba con http://")
        } catch (e: IOException) {
            Log.w("Error de red: $url", e)
            throw e
        }
    }

    private suspend fun get(url: String): String = request(url) { body ->
        body.string().also { if (it.isBlank()) throw IOException("Respuesta vacía del servidor") }
    }

    /**
     * Lista grande (canales, películas, series) leída POR PARTES mientras llega: nunca se
     * guarda el texto completo en memoria (catálogos de decenas de MB). De cada elemento
     * solo se guardan [keys]. Mismo método que la app Android.
     */
    private suspend fun <T> streamList(url: String, keys: Set<String>, make: (Map<String, String>, Int) -> T?): List<T> =
        request(url) { body ->
            val out = ArrayList<T>()
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { jr ->
                jr.setStrictness(com.google.gson.Strictness.LENIENT)
                if (jr.peek() != JsonToken.BEGIN_ARRAY) { jr.skipValue(); return@use }
                jr.beginArray()
                var i = 0
                val fields = HashMap<String, String>(keys.size * 2)
                while (jr.hasNext()) {
                    if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
                    fields.clear()
                    jr.beginObject()
                    while (jr.hasNext()) {
                        val name = jr.nextName()
                        if (name !in keys) { jr.skipValue(); continue }
                        when (jr.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> fields[name] = jr.nextString()
                            JsonToken.BOOLEAN -> fields[name] = jr.nextBoolean().toString()
                            JsonToken.NULL -> { jr.nextNull(); fields[name] = "" }
                            else -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                    make(fields, i)?.let { out.add(it) }
                    i++
                }
                jr.endArray()
            }
            out
        }

    // ── URLs de reproducción ──
    /**
     * Canal en vivo. Con VLC el formato .ts (directo) arranca más rápido y se corta menos
     * que .m3u8; se usa .ts salvo que el servidor diga que no lo permite.
     */
    @Volatile var liveExt: String = "ts"
    fun liveUrl(id: String): String {
        // Ajustes → En vivo → Formato de los canales (automático = lo que permite el servidor)
        val ext = when (com.jox3.tvdesktop.core.Settings.string("live.format", "auto")) { "ts" -> "ts"; "m3u8" -> "m3u8"; else -> liveExt }
        return "$host/live/${account.user}/${account.pass}/$id.$ext"
    }
    fun movieUrl(id: String, ext: String) = "$host/movie/${account.user}/${account.pass}/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$host/series/${account.user}/${account.pass}/$id.${ext.ifBlank { "mkv" }}"

    // ── Cuenta ──
    suspend fun serverInfo(): ServerInfo {
        val text = get(apiUrl(null))
        return withContext(Dispatchers.Default) {
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw IOException("El servidor no respondió como un servidor Xtream")
            }
            val u = json.optJSONObject("user_info") ?: throw IOException("Respuesta no válida del servidor")
            if (u.optInt("auth", 1) == 0) throw IOException("Usuario o contraseña incorrectos")
            val exp = u.optString("exp_date", "").toLongOrNull()?.takeIf { it > 0 }?.times(1000)
            val formats = u.optJSONArray("allowed_output_formats")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).lowercase() }
            }.orEmpty()
            liveExt = if (formats.isEmpty() || "ts" in formats) "ts" else "m3u8"
            ServerInfo(
                status = u.optString("status", ""),
                expires = exp,
                activeCons = u.optString("active_cons", "0"),
                maxCons = u.optString("max_connections", "?"),
            )
        }
    }

    // ── Catálogo ──
    suspend fun categories(section: Section): List<Category> {
        val text = get(apiUrl(section.apiCategories)).trim()
        return withContext(Dispatchers.Default) {
            if (!text.startsWith("[")) return@withContext emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("category_id").ifBlank { return@mapNotNull null }
                Category(id, o.optString("category_name", "?").trim())
            }.distinctBy { it.id }
        }
    }

    suspend fun channels(categoryId: String? = null): List<Channel> {
        val extra = if (categoryId != null) "&category_id=${enc(categoryId)}" else ""
        return streamList(apiUrl("get_live_streams", extra), CH_KEYS) { o, i ->
            val id = o["stream_id"].orEmpty().ifBlank { return@streamList null }
            Channel(
                id = id,
                name = o["name"]?.trim()?.ifBlank { null } ?: "?",
                logo = o["stream_icon"].orEmpty(),
                categoryId = o["category_id"].orEmpty(),
                num = o["num"]?.toDoubleOrNull()?.toInt() ?: (i + 1),
                epgId = o["epg_channel_id"].orEmpty().takeUnless { it == "null" }.orEmpty(),
            )
        }.distinctBy { it.id } // algunos servidores repiten el mismo canal
    }

    suspend fun movies(categoryId: String? = null): List<Movie> {
        val extra = if (categoryId != null) "&category_id=${enc(categoryId)}" else ""
        return streamList(apiUrl("get_vod_streams", extra), MOVIE_KEYS) { o, _ ->
            val id = o["stream_id"].orEmpty().ifBlank { return@streamList null }
            Movie(
                id = id,
                name = o["name"]?.trim()?.ifBlank { null } ?: "?",
                poster = o["stream_icon"].orEmpty(),
                rating = o["rating"].orEmpty().takeUnless { it == "0" || it == "null" }.orEmpty(),
                ext = o["container_extension"]?.ifBlank { null } ?: "mp4",
                categoryId = o["category_id"].orEmpty(),
                added = o["added"]?.toLongOrNull() ?: 0L,
            )
        }.distinctBy { it.id }
    }

    suspend fun series(categoryId: String? = null): List<Series> {
        val extra = if (categoryId != null) "&category_id=${enc(categoryId)}" else ""
        return streamList(apiUrl("get_series", extra), SERIES_KEYS) { o, _ ->
            val id = o["series_id"].orEmpty().ifBlank { return@streamList null }
            Series(
                id = id,
                name = o["name"]?.trim()?.ifBlank { null } ?: "?",
                poster = o["cover"].orEmpty(),
                rating = o["rating"].orEmpty().takeUnless { it == "0" || it == "null" }.orEmpty(),
                categoryId = o["category_id"].orEmpty(),
                updated = o["last_modified"]?.toLongOrNull() ?: 0L,
            )
        }.distinctBy { it.id }
    }

    suspend fun movieInfo(id: String): MovieInfo {
        val text = get(apiUrl("get_vod_info", "&vod_id=${enc(id)}"))
        return withContext(Dispatchers.Default) { parseMovieInfo(text) }
    }

    suspend fun seriesInfo(id: String): SeriesInfo {
        val text = get(apiUrl("get_series_info", "&series_id=${enc(id)}"))
        return withContext(Dispatchers.Default) { parseSeriesInfo(text) }
    }

    /** Guía corta de un canal: el programa actual y los siguientes. */
    suspend fun shortEpg(streamId: String, limit: Int = 4): List<EpgItem> {
        val text = get(apiUrl("get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit"))
        return withContext(Dispatchers.Default) {
            val json = try { JSONObject(text) } catch (e: Exception) { return@withContext emptyList() }
            val list = json.optJSONArray("epg_listings") ?: return@withContext emptyList()
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val start = o.optString("start_timestamp", "").toLongOrNull()?.times(1000) ?: 0L
                val end = o.optString("stop_timestamp", "").toLongOrNull()?.times(1000) ?: 0L
                EpgItem(b64(o.optString("title")), b64(o.optString("description")), start, end)
            }.filter { it.title.isNotBlank() && it.end > it.start }
        }
    }

    companion object {
        private val CH_KEYS = setOf("stream_id", "name", "stream_icon", "category_id", "num", "epg_channel_id")
        private val MOVIE_KEYS = setOf("stream_id", "name", "stream_icon", "rating", "container_extension", "category_id", "added")
        private val SERIES_KEYS = setOf("series_id", "name", "cover", "rating", "category_id", "last_modified")

        private fun JSONObject.str(vararg keys: String): String {
            for (k in keys) {
                val v = optString(k, "").trim()
                if (v.isNotEmpty() && v != "null") return v
            }
            return ""
        }

        /** backdrop_path puede ser texto o arreglo de URLs. */
        private fun JSONObject.firstImage(key: String): String = when (val v = opt(key)) {
            is JSONArray -> if (v.length() > 0) v.optString(0) else ""
            is String -> v
            else -> ""
        }

        /** Ficha de película (get_vod_info). Separado para poder probarlo sin servidor. */
        fun parseMovieInfo(text: String): MovieInfo {
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            val info = json.optJSONObject("info") ?: JSONObject()
            val data = json.optJSONObject("movie_data") ?: JSONObject()
            return MovieInfo(
                plot = info.str("plot", "description"),
                genre = info.str("genre"),
                director = info.str("director"),
                cast = info.str("cast", "actors"),
                duration = info.str("duration"),
                year = info.str("releasedate", "release_date", "year").take(4),
                rating = info.str("rating").takeUnless { it == "0" }.orEmpty(),
                backdrop = info.firstImage("backdrop_path"),
                poster = info.str("movie_image", "cover_big"),
                ext = data.optString("container_extension", ""),
            )
        }

        /**
         * Ficha de serie con sus temporadas (get_series_info). Los paneles devuelven
         * "episodes" como objeto {"1":[...]} o como arreglo de arreglos: se aceptan ambos.
         */
        fun parseSeriesInfo(text: String): SeriesInfo {
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            val info = json.optJSONObject("info") ?: JSONObject()
            val seasons = sortedMapOf<Int, MutableList<Episode>>()
            fun addEpisode(o: JSONObject, seasonHint: Int) {
                val season = o.optInt("season", seasonHint)
                val epInfo = o.optJSONObject("info") ?: JSONObject()
                val list = seasons.getOrPut(season) { mutableListOf() }
                list.add(
                    Episode(
                        id = o.optString("id"),
                        season = season,
                        num = o.optInt("episode_num", list.size + 1),
                        title = o.optString("title", "").trim(),
                        plot = epInfo.str("plot"),
                        image = epInfo.str("movie_image", "cover_big"),
                        duration = epInfo.str("duration"),
                        ext = o.optString("container_extension", "mkv"),
                    )
                )
            }
            when (val eps = json.opt("episodes")) {
                is JSONObject -> eps.keys().forEach { k ->
                    val list = eps.optJSONArray(k) ?: return@forEach
                    for (i in 0 until list.length()) list.optJSONObject(i)?.let { addEpisode(it, k.toIntOrNull() ?: 1) }
                }
                is JSONArray -> for (s in 0 until eps.length()) {
                    val list = eps.optJSONArray(s) ?: continue
                    for (i in 0 until list.length()) list.optJSONObject(i)?.let { addEpisode(it, s + 1) }
                }
            }
            val clean = seasons.mapValues { (_, list) -> list.filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedBy { it.num } }
                .filterValues { it.isNotEmpty() }
            return SeriesInfo(
                name = info.str("name"),
                plot = info.str("plot"),
                genre = info.str("genre"),
                year = info.str("releaseDate", "release_date", "year").take(4),
                rating = info.str("rating").takeUnless { it == "0" }.orEmpty(),
                backdrop = info.firstImage("backdrop_path"),
                poster = info.str("cover"),
                seasons = clean,
            )
        }

        /** Los títulos de la guía vienen en Base64; si no lo son, se dejan tal cual. */
        fun b64(s: String): String {
            if (s.isBlank()) return ""
            return try {
                String(Base64.getMimeDecoder().decode(s.trim()), Charsets.UTF_8).trim()
                    .takeIf { t -> t.isNotEmpty() && t.none { it == '\uFFFD' } } ?: s
            } catch (e: IllegalArgumentException) { s }
        }

        /**
         * Acepta lo que el usuario pegue en "Servidor": un host normal, una URL
         * get.php?username=..&password=.. o un enlace /live/usuario/clave/...
         */
        fun parseLogin(hostIn: String, userIn: String, passIn: String): Account? {
            var host = hostIn.trim().trimEnd('/')
            var user = userIn.trim()
            var pass = passIn.trim()
            if (host.isEmpty()) return null
            if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://$host"
            try {
                val uri = URI(host)
                val origin = "${uri.scheme}://${uri.rawAuthority}"
                val query = (uri.rawQuery ?: "").split('&').mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
                }.toMap()
                val qUser = query["username"]
                val qPass = query["password"]
                val segs = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
                when {
                    qUser != null && qPass != null -> { host = origin; user = qUser; pass = qPass }
                    segs.size >= 3 -> { host = origin; user = segs[1]; pass = segs[2] }
                    segs.size == 2 && user.isEmpty() -> { host = origin; user = segs[0]; pass = segs[1] }
                    uri.rawAuthority != null -> host = origin
                }
            } catch (e: Exception) { /* se usa tal cual */ }
            if (user.isEmpty() || pass.isEmpty()) return null
            return Account(host, user, pass)
        }
    }
}
