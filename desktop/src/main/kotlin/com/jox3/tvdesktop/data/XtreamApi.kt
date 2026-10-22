package com.jox3.tvdesktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.SocketTimeoutException

/**
 * Cliente de la API Xtream Codes (player_api.php). Misma lógica que la app Android;
 * en esta etapa solo el inicio de sesión. Las listas llegan en la etapa 2.
 */
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
            .header("User-Agent", Net.API_USER_AGENT)
            .header("Accept", "application/json, */*")
            .build()
        try {
            Net.api.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("El servidor respondió HTTP ${r.code}")
                val body = r.body?.string().orEmpty()
                if (body.isBlank()) throw IOException("Respuesta vacía del servidor")
                body
            }
        } catch (e: UnknownHostException) {
            throw IOException("No se encontró el servidor. Revisa la dirección.")
        } catch (e: SocketTimeoutException) {
            throw IOException("El servidor no respondió a tiempo.")
        }
    }

    // ── URLs de reproducción ──
    fun liveUrl(id: String) = "$host/live/${account.user}/${account.pass}/$id.m3u8"
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
            ServerInfo(
                status = u.optString("status", ""),
                expires = exp,
                activeCons = u.optString("active_cons", "0"),
                maxCons = u.optString("max_connections", "?"),
            )
        }
    }

    companion object {
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
