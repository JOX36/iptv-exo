package com.jox3.tvdesktop.data

import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Historial en la nube: "Seguir viendo" (con el minuto exacto), favoritos y canales recientes
 * se comparten entre todos los equipos con la misma cuenta IPTV (PC, TV Box y celular).
 * Mismo servidor y mismo formato que la app Android (tv/data/CloudSync.kt).
 *
 * A internet solo sale una huella (SHA-256) de servidor + usuario + contraseña: nadie puede
 * sacar de ahí la cuenta. Sin internet el programa sigue igual y sube lo pendiente después.
 */
object CloudSync {
    const val SERVER = "https://jox3-sync.jox3tv.workers.dev"
    private const val EVERY_MS = 45_000L
    private const val BATCH = 400

    fun enabled() = Settings.bool("sync.enabled", true)

    /** Cliente con verificación normal de certificados (es nuestro servidor, no un panel IPTV). */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS).build()
    }

    /** Huella de la cuenta (lo único que identifica al usuario en el servidor). Idéntica a Android. */
    fun accountId(acc: Account): String {
        val host = acc.host.trim().lowercase().removeSuffix("/")
        val raw = "jox3|$host|${acc.user.trim()}|${acc.pass}"
        return sha256Hex(raw)
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Sincroniza mientras el programa está abierto: al empezar, cada 45 s y 3 s después de cada
     * cambio (favorito, canal, cerrar la película). Se llama desde el hilo de la ventana.
     */
    suspend fun run(acc: Account, lib: LibraryStore) {
        if (!enabled()) return
        val kick = Channel<Unit>(Channel.CONFLATED)
        lib.onLocalChange = { kick.trySend(Unit) }
        val id = accountId(acc)
        try {
            while (true) {
                if (enabled()) syncOnce(id, lib)
                // Espera hasta 45 s o hasta que haya un cambio (y junta los cambios de 3 s)
                withTimeoutOrNull(EVERY_MS) { kick.receive() }?.let { delay(3_000) }
            }
        } finally {
            lib.onLocalChange = {}
            withContext(NonCancellable) { withTimeoutOrNull(8_000) { syncOnce(id, lib) } }
        }
    }

    /** Una vuelta: (la primera vez) baja todo, luego sube lo pendiente y baja lo nuevo. */
    suspend fun syncOnce(id: String, lib: LibraryStore) {
        runCatching {
            if (!lib.syncStarted) {
                // Primera vez en este equipo: primero se baja lo de la nube y luego se sube lo local
                pull(id, lib, 0L)
                lib.markInitialUpload()
            }
            var guard = 0
            do {
                val changes = lib.pendingChanges()
                val since = lib.syncCursor
                val chunk = changes.take(BATCH)
                val res = post(id, lib.profileId, (since - 10_000).coerceAtLeast(0), chunk) ?: return
                lib.applyRemote(res.items)
                lib.clearSent(chunk)
                lib.updateCursor(res.cursor)
            } while ((res.more || changes.size > BATCH) && ++guard < 10)
        }.onFailure { Log.w("Nube: ${it.message}") }
    }

    private suspend fun pull(id: String, lib: LibraryStore, from: Long) {
        var since = from
        repeat(10) {
            val res = post(id, lib.profileId, since, emptyList()) ?: return
            lib.applyRemote(res.items)
            lib.updateCursor(res.cursor)
            if (!res.more) return
            since = res.cursor
        }
    }

    /**
     * Al cerrar el programa: sube lo pendiente sin esperar respuesta en la ventana
     * (como mucho [timeoutMs]). Se llama desde el hilo de la ventana.
     */
    fun flushBlocking(acc: Account, lib: LibraryStore, timeoutMs: Long = 4_000) {
        if (!enabled()) return
        val changes = lib.pendingChanges().take(BATCH)
        if (changes.isEmpty()) return
        val id = accountId(acc)
        val profile = lib.profileId
        val since = lib.syncCursor
        val t = Thread {
            runCatching { kotlinx.coroutines.runBlocking { post(id, profile, (since - 10_000).coerceAtLeast(0), changes) } }
        }.apply { isDaemon = true; start() }
        t.join(timeoutMs)
    }

    class Result(val cursor: Long, val more: Boolean, val items: List<JSONObject>)

    /** POST /v1/sync {acc, profile, since, changes} → {now, more, items}. */
    suspend fun post(id: String, profile: String, since: Long, changes: List<JSONObject>): Result? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("acc", id).put("profile", profile).put("since", since)
            .put("changes", JSONArray(changes)).toString()
        val req = Request.Builder().url("$SERVER/v1/sync")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w("Nube: HTTP ${r.code}"); return@withContext null }
            val o = JSONObject(r.body?.string().orEmpty())
            val arr = o.optJSONArray("items") ?: JSONArray()
            Result(o.optLong("now"), o.optBoolean("more"), (0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
        }
    }
}
