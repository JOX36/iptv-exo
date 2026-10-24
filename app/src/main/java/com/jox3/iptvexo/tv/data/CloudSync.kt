package com.jox3.iptvexo.tv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Historial en la nube: "Seguir viendo" (con el minuto exacto), favoritos y canales
 * recientes se comparten entre todos los equipos que usan la misma cuenta IPTV.
 *
 * A internet solo sale una huella (SHA-256) de servidor + usuario + contraseña: nadie puede
 * sacar de ahí la cuenta, y sin la contraseña tampoco se puede adivinar la huella.
 * Si no hay internet o el servidor falla, la app sigue igual que antes (todo queda en el equipo)
 * y se sube después.
 */
object CloudSync {
    const val SERVER = "https://jox3-sync.jox3tv.workers.dev"
    /** Espacio en el servidor donde se guarda la lista de perfiles de la cuenta. */
    private const val PROFILES_SCOPE = "_perfiles"
    private const val EVERY_MS = 45_000L
    private const val PREFS = "tv_sync"

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()

    /** Huella de la cuenta (lo único que identifica al usuario en el servidor). */
    fun accountId(acc: Account): String {
        val host = acc.host.trim().lowercase().removeSuffix("/")
        val raw = "jox3|$host|${acc.user.trim()}|${acc.pass}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Sincroniza mientras la pantalla está abierta: al empezar, cada 45 s y un momento después
     * de cada cambio (favorito, canal, cerrar el reproductor). Al salir sube lo pendiente.
     */
    suspend fun run(ctx: Context, acc: Account, lib: LibraryStore, profiles: ProfileStore? = null) {
        if (!enabled(ctx)) return
        val kick = Channel<Unit>(Channel.CONFLATED)
        lib.onLocalChange = { kick.trySend(Unit) }
        profiles?.onChange = { kick.trySend(Unit) }
        val id = accountId(acc)
        try {
            while (true) {
                syncOnce(id, lib)
                if (profiles != null) syncProfiles(ctx, profiles)
                // Espera hasta 45 s o hasta que haya un cambio (y junta los cambios de 3 s)
                withTimeoutOrNull(EVERY_MS) { kick.receive() }?.let { delay(3_000) }
            }
        } finally {
            lib.onLocalChange = {}
            profiles?.onChange = {}
            withContext(NonCancellable) { withTimeoutOrNull(8_000) { syncOnce(id, lib) } }
        }
    }

    private suspend fun syncOnce(id: String, lib: LibraryStore) {
        runCatching {
            if (!withContext(Dispatchers.Main) { lib.syncStarted }) {
                // Primera vez en este equipo: primero se baja lo de la nube y luego se sube lo local
                pull(id, lib, 0L)
                withContext(Dispatchers.Main) { lib.markInitialUpload() }
            }
            var guard = 0
            do {
                val (changes, since) = withContext(Dispatchers.Main) {
                    lib.collectProgress()
                    lib.pendingChanges() to lib.syncCursor
                }
                val res = post(id, lib.profileId, (since - 10_000).coerceAtLeast(0), changes.take(400)) ?: return
                withContext(Dispatchers.Main) {
                    lib.applyRemote(res.items)
                    lib.clearSent(changes.take(400))
                    lib.syncCursor = res.cursor
                }
            } while ((res.more || changes.size > 400) && ++guard < 10)
        }.onFailure { Log.w("JOX3", "Sincronización: ${it.message}") }
    }

    private suspend fun pull(id: String, lib: LibraryStore, from: Long) {
        var since = from
        repeat(10) {
            val res = post(id, lib.profileId, since, emptyList()) ?: return
            withContext(Dispatchers.Main) { lib.applyRemote(res.items); lib.syncCursor = res.cursor }
            if (!res.more) return
            since = res.cursor
        }
    }

    /** Perfiles de la cuenta: sube los cambios de este equipo y baja los de los demás. */
    suspend fun syncProfiles(ctx: Context, store: ProfileStore) {
        if (!enabled(ctx)) return
        runCatching {
            val (changes, since) = withContext(Dispatchers.Main) { store.pendingChanges() to store.cursor }
            val res = post(accountId(store.account), PROFILES_SCOPE, (since - 10_000).coerceAtLeast(0), changes) ?: return
            withContext(Dispatchers.Main) {
                store.applyRemote(res.items)
                store.clearSent(changes)
                store.cursor = res.cursor
            }
        }.onFailure { Log.w("JOX3", "Perfiles: ${it.message}") }
    }

    internal class Result(val cursor: Long, val more: Boolean, val items: List<JSONObject>)

    internal suspend fun post(id: String, profile: String, since: Long, changes: List<JSONObject>): Result? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("acc", id).put("profile", profile).put("since", since)
            .put("changes", JSONArray(changes)).toString()
        val req = Request.Builder().url("$SERVER/v1/sync")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w("JOX3", "Sincronización HTTP ${r.code}"); return@withContext null }
            val o = JSONObject(r.body?.string().orEmpty())
            val arr = o.optJSONArray("items") ?: JSONArray()
            Result(o.optLong("now"), o.optBoolean("more"), (0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
        }
    }
}
