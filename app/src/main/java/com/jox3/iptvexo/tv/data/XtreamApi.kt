package com.jox3.iptvexo.tv.data

import android.util.Base64
import com.jox3.iptvexo.NetClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Cliente de la API Xtream Codes (player_api.php). Todas las llamadas corren en IO. */
class XtreamApi(val account: Account) {

    private val host = account.host.trimEnd('/')

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(action: String?, extra: String = ""): String = buildString {
        append(host).append("/player_api.php?username=").append(enc(account.user))
        append("&password=").append(enc(account.pass))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", NetClient.API_USER_AGENT)
            .header("Accept", "application/json, */*")
            .build()
        NetClient.api().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("El servidor respondió HTTP ${r.code}")
            val body = r.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("Respuesta vacía del servidor")
            body
        }
    }

    // El JSON se lee fuera del hilo principal (Default): con catálogos grandes
    // leerlo en el hilo de la interfaz congelaba el control y Android cerraba la app.
    private suspend fun array(url: String): JSONArray {
        val text = get(url).trim()
        return if (text.startsWith("[")) JSONArray(text) else JSONArray()
    }

    /**
     * Lista grande (canales, películas, series) leída POR PARTES mientras llega: nunca se
     * guarda el texto completo en memoria (con catálogos de decenas de MB, eso llenaba la
     * memoria de los TV Box y la app se cerraba). De cada elemento solo se guardan [keys].
     */
    private suspend fun <T> streamList(url: String, keys: Set<String>, make: (Map<String, String>, Int) -> T?): List<T> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("User-Agent", NetClient.API_USER_AGENT)
                .header("Accept", "application/json, */*")
                .build()
            NetClient.api().newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("El servidor respondió HTTP ${r.code}")
                val body = r.body ?: throw IOException("Respuesta vacía del servidor")
                val out = ArrayList<T>()
                android.util.JsonReader(java.io.InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { jr ->
                    jr.isLenient = true
                    if (jr.peek() != android.util.JsonToken.BEGIN_ARRAY) { jr.skipValue(); return@use }
                    jr.beginArray()
                    var i = 0
                    val fields = HashMap<String, String>(keys.size * 2)
                    while (jr.hasNext()) {
                        if (jr.peek() != android.util.JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
                        fields.clear()
                        jr.beginObject()
                        while (jr.hasNext()) {
                            val name = jr.nextName()
                            if (name !in keys) { jr.skipValue(); continue }
                            when (jr.peek()) {
                                android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> fields[name] = jr.nextString()
                                android.util.JsonToken.BOOLEAN -> fields[name] = jr.nextBoolean().toString()
                                android.util.JsonToken.NULL -> { jr.nextNull(); fields[name] = "" }
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
        }

    // ── URLs de reproducción ──
    fun liveUrl(id: String) = "$host/live/${account.user}/${account.pass}/$id.m3u8"
    /** Guía completa del servidor (XMLTV: todos los canales, varios días). */
    fun xmltvUrl() = "$host/xmltv.php?username=${enc(account.user)}&password=${enc(account.pass)}"

    fun movieUrl(id: String, ext: String) = "$host/movie/${account.user}/${account.pass}/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$host/series/${account.user}/${account.pass}/$id.${ext.ifBlank { "mkv" }}"

    // ── Cuenta ──
    suspend fun serverInfo(): ServerInfo = withContext(Dispatchers.Default) {
        val json = JSONObject(get(apiUrl(null)))
        val u = json.optJSONObject("user_info") ?: throw IOException("Respuesta no válida del servidor")
        if (u.optInt("auth", 1) == 0) throw IOException("Usuario o contraseña incorrectos")
        val exp = u.optString("exp_date", "").toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        return@withContext ServerInfo(
            status = u.optString("status", ""),
            expires = exp,
            activeCons = u.optString("active_cons", "0"),
            maxCons = u.optString("max_connections", "?"),
        )
    }

    // ── Catálogo ──
    suspend fun categories(section: Section): List<Category> = withContext(Dispatchers.Default) {
        val arr = array(apiUrl(section.apiCategories))
        return@withContext (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("category_name", "?").trim().let { raw -> Category(o.optString("category_id"), Names.category(raw), raw) }
        }
    }

    suspend fun channels(categoryId: String? = null): List<Channel> {
        val extra = if (categoryId != null) "&category_id=${enc(categoryId)}" else ""
        return streamList(apiUrl("get_live_streams", extra), CH_KEYS) { o, i ->
            val id = o["stream_id"].orEmpty().ifBlank { return@streamList null }
            Channel(
                id = id,
                name = Names.channel(o["name"]?.trim()?.ifBlank { null } ?: "?"),
                logo = o["stream_icon"].orEmpty(),
                categoryId = o["category_id"].orEmpty(),
                num = o["num"]?.toDoubleOrNull()?.toInt() ?: (i + 1),
                epgId = o["epg_channel_id"].orEmpty().takeUnless { it == "null" }.orEmpty(),
            )
        }.distinctBy { it.id }   // algunos servidores repiten el mismo título (y una lista con claves repetidas cierra la app)
    }

    suspend fun movies(categoryId: String? = null): List<Movie> {
        val extra = if (categoryId != null) "&category_id=${enc(categoryId)}" else ""
        return streamList(apiUrl("get_vod_streams", extra), MOVIE_KEYS) { o, _ ->
            val id = o["stream_id"].orEmpty().ifBlank { return@streamList null }
            Movie(
                id = id,
                name = Names.clean(o["name"]?.trim()?.ifBlank { null } ?: "?", titles = true),
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
                name = Names.clean(o["name"]?.trim()?.ifBlank { null } ?: "?", titles = true),
                poster = o["cover"].orEmpty(),
                rating = o["rating"].orEmpty().takeUnless { it == "0" || it == "null" }.orEmpty(),
                categoryId = o["category_id"].orEmpty(),
                updated = o["last_modified"]?.toLongOrNull() ?: 0L,
            )
        }.distinctBy { it.id }
    }

    suspend fun movieInfo(id: String): MovieInfo = withContext(Dispatchers.Default) {
        val json = JSONObject(get(apiUrl("get_vod_info", "&vod_id=${enc(id)}")))
        val info = json.optJSONObject("info") ?: JSONObject()
        val data = json.optJSONObject("movie_data") ?: JSONObject()
        return@withContext MovieInfo(
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

    suspend fun seriesInfo(id: String): SeriesInfo = withContext(Dispatchers.Default) {
        val json = JSONObject(get(apiUrl("get_series_info", "&series_id=${enc(id)}")))
        val info = json.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, MutableList<Episode>>()
        fun addEpisode(o: JSONObject, seasonHint: Int) {
            val season = o.optInt("season", seasonHint)
            val epInfo = o.optJSONObject("info") ?: JSONObject()
            seasons.getOrPut(season) { mutableListOf() }.add(
                Episode(
                    id = o.optString("id"),
                    season = season,
                    num = o.optInt("episode_num", seasons[season]?.size?.plus(1) ?: 1),
                    title = o.optString("title", "").trim(),
                    plot = epInfo.str("plot"),
                    image = epInfo.str("movie_image", "cover_big"),
                    duration = epInfo.str("duration"),
                    ext = o.optString("container_extension", "mkv"),
                )
            )
        }
        // Los paneles devuelven "episodes" como objeto {"1":[...]} o como arreglo de arreglos
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
        seasons.values.forEach { list ->
            val unique = list.distinctBy { it.id }.sortedBy { it.num }
            list.clear(); list.addAll(unique)
        }
        return@withContext SeriesInfo(
            name = info.str("name"),
            plot = info.str("plot"),
            genre = info.str("genre"),
            year = info.str("releaseDate", "release_date", "year").take(4),
            rating = info.str("rating").takeUnless { it == "0" }.orEmpty(),
            backdrop = info.firstImage("backdrop_path"),
            poster = info.str("cover"),
            seasons = seasons,
        )
    }

    /** Guía corta de un canal: el programa actual y los siguientes. */
    suspend fun shortEpg(streamId: String, limit: Int = 4): List<EpgItem> = withContext(Dispatchers.Default) {
        val json = JSONObject(get(apiUrl("get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit")))
        val list = json.optJSONArray("epg_listings") ?: return@withContext emptyList()
        return@withContext (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val start = o.optString("start_timestamp", "").toLongOrNull()?.times(1000) ?: 0L
            val end = o.optString("stop_timestamp", "").toLongOrNull()?.times(1000) ?: 0L
            EpgItem(b64(o.optString("title")), b64(o.optString("description")), start, end)
        }.filter { it.title.isNotBlank() }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: Exception) { s }

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

    companion object {
        private val CH_KEYS = setOf("stream_id", "name", "stream_icon", "category_id", "num", "epg_channel_id")
        private val MOVIE_KEYS = setOf("stream_id", "name", "stream_icon", "rating", "container_extension", "category_id", "added")
        private val SERIES_KEYS = setOf("series_id", "name", "cover", "rating", "category_id", "last_modified")
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
                val uri = android.net.Uri.parse(host)
                val origin = "${uri.scheme}://${uri.authority}"
                val qUser = uri.getQueryParameter("username")
                val qPass = uri.getQueryParameter("password")
                val segs = uri.pathSegments.orEmpty()
                when {
                    qUser != null && qPass != null -> { host = origin; user = qUser; pass = qPass }
                    segs.size >= 3 -> { host = origin; user = segs[1]; pass = segs[2] }
                    segs.size == 2 && user.isEmpty() -> { host = origin; user = segs[0]; pass = segs[1] }
                    else -> host = origin
                }
            } catch (e: Exception) { /* se usa tal cual */ }
            if (user.isEmpty() || pass.isEmpty()) return null
            return Account(host, user, pass)
        }
    }

}
