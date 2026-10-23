package com.jox3.iptvexo.tv.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Perfil dentro de una cuenta IPTV: cada persona de la casa tiene su propio
 * "Seguir viendo", favoritos, canales recientes y búsquedas.
 */
data class Profile(
    val id: String,
    val name: String,
    val emoji: String,
    val color: Long,
    val pinHash: String = "",
    val created: Long = 0L,
    val ts: Long = 0L,
    val deleted: Boolean = false,
) {
    val hasPin get() = pinHash.isNotEmpty()
    val isMain get() = id == Profiles.MAIN
}

object Profiles {
    /** El primer perfil: conserva todo lo que la cuenta ya tenía guardado. */
    const val MAIN = "principal"
    const val MAX = 5

    /** Colores llamativos para el fondo del avatar. */
    val COLORS = listOf(
        0xFFFF3B6BL, 0xFFFF7A00L, 0xFFFFC400L, 0xFF22C55EL,
        0xFF00C2FFL, 0xFF3B82F6L, 0xFF8B5CF6L, 0xFFEC4899L,
    )
    val EMOJIS = listOf(
        "😎", "🦁", "🐯", "🐼", "🦊", "🐸", "🦄", "🐶",
        "🌸", "⚽", "🎮", "🎸", "🍕", "🚀", "👑", "🔥",
    )

    /** Perfil elegido en esta sesión de la app (la TV pregunta solo al abrirla). */
    internal val chosenThisLaunch = HashMap<String, String>()
}

/** Perfiles de una cuenta, guardados en el equipo y compartidos por la nube. */
class ProfileStore(context: Context, val account: Account) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ns = account.key.hashCode().toString()
    private val all = LinkedHashMap<String, Profile>()

    /** Perfiles visibles, en el orden en que se crearon. */
    val list = mutableStateListOf<Profile>()

    /** Lo pone CloudSync: hay un cambio de perfiles para subir. */
    @Volatile internal var onChange: () -> Unit = {}

    init {
        runCatching {
            val o = JSONObject(prefs.getString("$ns.list", "{}") ?: "{}")
            o.keys().forEach { k -> o.optJSONObject(k)?.let { all[k] = fromJson(it) } }
        }
        if (all.values.none { it.isMain }) {
            // ts = 1: si otro equipo ya le cambió el nombre, gana el de la nube
            put(Profile(Profiles.MAIN, "Principal", "😎", Profiles.COLORS[4], created = 1L, ts = 1L), dirty = true)
        }
        refresh()
    }

    private fun refresh() {
        list.clear()
        list.addAll(all.values.filter { !it.deleted }.sortedWith(compareBy<Profile> { !it.isMain }.thenBy { it.created }))
    }

    private fun save() {
        val o = JSONObject()
        all.forEach { (k, p) -> o.put(k, toJson(p)) }
        prefs.edit().putString("$ns.list", o.toString()).apply()
    }

    private fun put(p: Profile, dirty: Boolean) {
        all[p.id] = p
        save()
        if (dirty) {
            val d = dirtyObj(); d.put(p.id, p.ts)
            prefs.edit().putString("$ns.dirty", d.toString()).apply()
            onChange()
        }
    }

    fun byId(id: String) = all[id]?.takeIf { !it.deleted }
    val canAdd get() = list.size < Profiles.MAX

    fun add(name: String, emoji: String, color: Long, pin: String?): Profile {
        val now = System.currentTimeMillis()
        val id = "p" + java.lang.Long.toHexString(now) + (100..999).random()
        val p = Profile(id, name.trim().ifBlank { "Perfil" }, emoji, color,
            pinHash = pin?.let { hashPin(id, it) }.orEmpty(), created = now, ts = now)
        put(p, dirty = true); refresh()
        return p
    }

    fun update(p: Profile, name: String, emoji: String, color: Long, pinHash: String) {
        put(p.copy(name = name.trim().ifBlank { p.name }, emoji = emoji, color = color, pinHash = pinHash,
            ts = System.currentTimeMillis()), dirty = true)
        refresh()
    }

    /** Borra el perfil y su historial en este equipo (el principal no se puede borrar). */
    fun delete(p: Profile) {
        if (p.isMain) return
        put(p.copy(deleted = true, ts = System.currentTimeMillis()), dirty = true)
        wipeLibrary(app, account.key, p.id)
        if (remembered == p.id) remembered = null
        refresh()
    }

    fun hashPin(id: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("jox3pin|${CloudSync.accountId(account)}|$id|$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun checkPin(p: Profile, pin: String) = !p.hasPin || hashPin(p.id, pin) == p.pinHash

    /** Celular: perfil con el que entra directo ("Recordar en este celular"). */
    var remembered: String?
        get() = prefs.getString("$ns.remember", null)
        set(v) { prefs.edit().apply { if (v == null) remove("$ns.remember") else putString("$ns.remember", v) }.apply() }

    /** Perfil con el que se entra sin preguntar (o null para mostrar "¿Quién está viendo?"). */
    fun autoPick(tv: Boolean): Profile? {
        Profiles.chosenThisLaunch[account.key]?.let { byId(it) }?.let { return it }
        if (!tv) remembered?.let { byId(it) }?.let { return it }
        return list.singleOrNull()?.takeIf { !it.hasPin }
    }

    // ── Sincronización (CloudSync) ──
    private fun dirtyObj() = runCatching { JSONObject(prefs.getString("$ns.dirty", "{}") ?: "{}") }.getOrDefault(JSONObject())

    internal var cursor: Long
        get() = prefs.getLong("$ns.cursor", 0L)
        set(v) { prefs.edit().putLong("$ns.cursor", v).apply() }

    internal fun pendingChanges(): List<JSONObject> {
        val d = dirtyObj()
        return d.keys().asSequence().mapNotNull { all[it] }.map { p ->
            JSONObject().put("list", "profile").put("item", "p:${p.id}").put("ts", p.ts).put("deleted", p.deleted)
                .apply { if (!p.deleted) put("data", toJson(p).toString()) }
        }.toList()
    }

    internal fun clearSent(sent: List<JSONObject>) {
        val d = dirtyObj()
        sent.forEach { c ->
            val id = c.optString("item").removePrefix("p:")
            if (d.optLong(id, -1) == c.optLong("ts")) d.remove(id)
        }
        prefs.edit().putString("$ns.dirty", d.toString()).apply()
    }

    internal fun applyRemote(items: List<JSONObject>) {
        var changed = false
        items.forEach { r ->
            if (r.optString("list") != "profile") return@forEach
            val id = r.optString("item").removePrefix("p:")
            val ts = r.optLong("ts")
            val local = all[id]
            if (local != null && local.ts >= ts) return@forEach
            if (r.optBoolean("deleted")) {
                if (local != null && !local.deleted) {
                    all[id] = local.copy(deleted = true, ts = ts)
                    wipeLibrary(app, account.key, id); changed = true
                }
            } else {
                val p = runCatching { fromJson(JSONObject(r.optString("data"))) }.getOrNull() ?: return@forEach
                if (p.id != id) return@forEach
                all[id] = p.copy(ts = ts, deleted = false); changed = true
            }
        }
        if (changed) { save(); refresh() }
    }

    private fun toJson(p: Profile) = JSONObject().put("id", p.id).put("name", p.name).put("emoji", p.emoji)
        .put("color", p.color).put("pin", p.pinHash).put("created", p.created).put("ts", p.ts).put("deleted", p.deleted)

    private fun fromJson(o: JSONObject) = Profile(
        id = o.optString("id"), name = o.optString("name").take(20), emoji = o.optString("emoji").ifBlank { "😎" }.take(8),
        color = o.optLong("color", Profiles.COLORS[4]), pinHash = o.optString("pin"),
        created = o.optLong("created"), ts = o.optLong("ts"), deleted = o.optBoolean("deleted"),
    )

    companion object {
        private const val PREFS = "tv_profiles"

        /** Espacio de nombres de la biblioteca de un perfil (el principal usa el de siempre). */
        fun libraryNs(accountKey: String, profileId: String): String =
            if (profileId == Profiles.MAIN) accountKey.hashCode().toString()
            else (accountKey + "#" + profileId).hashCode().toString()

        /** Archivo donde el reproductor guarda las posiciones de ese perfil. */
        fun progressFile(accountKey: String, profileId: String): String =
            if (profileId == Profiles.MAIN) "vod_progress" else "vod_progress_" + libraryNs(accountKey, profileId)

        /** Borra en este equipo el historial de un perfil. */
        fun wipeLibrary(ctx: Context, accountKey: String, profileId: String) {
            val ns = libraryNs(accountKey, profileId) + "."
            listOf("tv_library", "tv_sync").forEach { name ->
                val p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val ed = p.edit(); p.all.keys.filter { it.startsWith(ns) }.forEach { ed.remove(it) }; ed.apply()
            }
            if (profileId != Profiles.MAIN) ctx.getSharedPreferences(progressFile(accountKey, profileId), Context.MODE_PRIVATE).edit().clear().apply()
        }

        /** Al eliminar una cuenta: sus perfiles y el historial de cada uno. */
        fun wipeAccount(ctx: Context, acc: Account) {
            val store = ProfileStore(ctx, acc)
            store.all.keys.filter { it != Profiles.MAIN }.forEach { wipeLibrary(ctx, acc.key, it) }
            val ns = acc.key.hashCode().toString() + "."
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ed = p.edit(); p.all.keys.filter { it.startsWith(ns) }.forEach { ed.remove(it) }; ed.apply()
        }
    }
}
