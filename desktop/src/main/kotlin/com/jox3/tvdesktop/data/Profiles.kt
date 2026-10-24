package com.jox3.tvdesktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import org.json.JSONObject
import java.io.File

/**
 * Perfil dentro de una cuenta IPTV: cada persona de la casa tiene su propio "Seguir viendo",
 * favoritos, canales recientes y búsquedas. Mismo formato que la app Android (tv/data/Profiles.kt),
 * así los perfiles se comparten con el TV Box y el celular.
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
    /** Casillero del código JOX3 de esta persona ("" = no guarda servidores en la nube). */
    val vaultId: String = "",
    /** Cuántos servidores tiene guardados (para avisar en otros equipos). */
    val vaultCount: Int = 0,
) {
    val hasPin get() = pinHash.isNotEmpty()
    val isMain get() = id == Profiles.MAIN
}

object Profiles {
    /** El primer perfil: conserva todo lo que la cuenta ya tenía guardado. */
    const val MAIN = LibraryStore.PROFILE_MAIN
    const val MAX = 5

    /** Colores llamativos para el fondo del avatar (ARGB, iguales a Android). */
    val COLORS = listOf(
        0xFFFF3B6BL, 0xFFFF7A00L, 0xFFFFC400L, 0xFF22C55EL,
        0xFF00C2FFL, 0xFF3B82F6L, 0xFF8B5CF6L, 0xFFEC4899L,
    )
    val EMOJIS = listOf(
        "😎", "🦁", "🐯", "🐼", "🦊", "🐸", "🦄", "🐶",
        "🌸", "⚽", "🎮", "🎸", "🍕", "🚀", "👑", "🔥",
    )

    /**
     * Perfil en uso mientras el programa está abierto. Es el mismo para TODOS los servidores:
     * al cambiar de servidor (o abrir un resultado de otro) se sigue con el mismo perfil.
     */
    var current by mutableStateOf<Profile?>(null)

    /** PIN: igual en todos los servidores y equipos ("v2:" + SHA-256 de "jox3pin2|id|pin"). */
    fun hashPin(id: String, pin: String): String = "v2:" + CloudSync.sha256Hex("jox3pin2|$id|$pin")
}

/** Perfiles de una cuenta, guardados en el equipo (profiles/<cuenta>.json) y compartidos por la nube. */
class ProfileStore(val account: Account, private val dir: File = File(AppDirs.data, "profiles")) {
    private val file = File(dir, LibraryStore.keyHash(account.key) + ".json")
    private val all = LinkedHashMap<String, Profile>()
    /** id → ts de los cambios que faltan por subir. */
    private val dirty = LinkedHashMap<String, Long>()
    internal var cursor = 0L
        private set

    /** Perfiles visibles: el principal primero y luego en el orden en que se crearon. */
    val list = mutableStateListOf<Profile>()

    /** Lo pone CloudSync: hay un cambio de perfiles para subir. */
    @Volatile internal var onChange: () -> Unit = {}

    init {
        SafeFile.read(file) { JSONObject(it) }?.let { o ->
            o.optJSONObject("list")?.let { l -> l.keys().forEach { k -> l.optJSONObject(k)?.let { all[k] = fromJson(it) } } }
            o.optJSONObject("dirty")?.let { d -> d.keys().forEach { k -> dirty[k] = d.optLong(k) } }
            cursor = o.optLong("cursor")
        }
        if (all.values.none { it.isMain }) {
            // ts = 1: si otro equipo ya le cambió el nombre, gana el de la nube
            put(Profile(Profiles.MAIN, "Principal", "😎", Profiles.COLORS[4], created = 1L, ts = 1L), dirty = true)
        }
        mergeFromDevice()
        refresh()
    }

    /**
     * Una sola lista de perfiles para todos los servidores del equipo: lo que se creó,
     * cambió o borró con otro servidor se copia aquí (gana el cambio más reciente), y
     * así también se sube a la nube de este servidor.
     */
    private fun mergeFromDevice() {
        val best = HashMap<String, Profile>()
        dir.listFiles { f -> f.name.endsWith(".json") && f.name != file.name }?.forEach { f ->
            runCatching {
                val l = JSONObject(f.readText()).optJSONObject("list") ?: return@runCatching
                l.keys().forEach { id ->
                    val p = l.optJSONObject(id)?.let(::fromJson) ?: return@forEach
                    if (p.id != id) return@forEach
                    if ((best[id]?.ts ?: -1L) < p.ts) best[id] = p
                }
            }
        }
        best.values.forEach { p ->
            val local = all[p.id]
            if (local != null && local.ts >= p.ts) return@forEach
            if (p.deleted) {
                if (local != null && !local.deleted) { put(p, dirty = true); wipeLibrary(account.key, p.id) }
            } else put(p, dirty = true)
        }
    }

    private fun refresh() {
        list.clear()
        list.addAll(all.values.filter { !it.deleted }.sortedWith(compareBy<Profile> { !it.isMain }.thenBy { it.created }))
    }

    private fun save() {
        val o = JSONObject()
            .put("list", JSONObject().apply { all.forEach { (k, p) -> put(k, toJson(p)) } })
            .put("dirty", JSONObject().apply { dirty.forEach { (k, v) -> put(k, v) } })
            .put("cursor", cursor)
        try { SafeFile.write(file, o.toString()) } catch (e: Exception) { Log.e("No se pudieron guardar los perfiles", e) }
    }

    private fun put(p: Profile, dirty: Boolean) {
        all[p.id] = p
        if (dirty) this.dirty[p.id] = p.ts
        save()
        if (dirty) onChange()
    }

    fun byId(id: String) = all[id]?.takeIf { !it.deleted }
    val canAdd get() = list.size < Profiles.MAX

    fun add(name: String, emoji: String, color: Long, pin: String?): Profile {
        val now = System.currentTimeMillis()
        val id = "p" + java.lang.Long.toHexString(now) + (100..999).random()
        val p = Profile(id, name.trim().ifBlank { "Perfil" }.take(20), emoji, color,
            pinHash = pin?.let { Profiles.hashPin(id, it) }.orEmpty(), created = now, ts = now)
        put(p, dirty = true); refresh()
        return p
    }

    fun update(p: Profile, name: String, emoji: String, color: Long, pinHash: String) {
        val cur = all[p.id] ?: p
        put(cur.copy(name = name.trim().ifBlank { cur.name }.take(20), emoji = emoji, color = color, pinHash = pinHash,
            ts = System.currentTimeMillis()), dirty = true)
        refresh()
        if (Profiles.current?.id == p.id) Profiles.current = byId(p.id)
    }

    /** Marca que el perfil guarda (o deja de guardar) sus servidores con un código JOX3. */
    fun setVault(p: Profile, vaultId: String, count: Int) {
        val cur = all[p.id] ?: p
        put(cur.copy(vaultId = vaultId, vaultCount = count, ts = System.currentTimeMillis()), dirty = true)
        refresh()
        if (Profiles.current?.id == p.id) Profiles.current = byId(p.id)
    }

    /** Borra el perfil y su historial en este equipo (el principal no se puede borrar). */
    fun delete(p: Profile) {
        if (p.isMain) return
        put(p.copy(deleted = true, ts = System.currentTimeMillis()), dirty = true)
        wipeLibrary(account.key, p.id)
        refresh()
    }

    fun checkPin(p: Profile, pin: String): Boolean {
        if (!p.hasPin) return true
        if (p.pinHash.startsWith("v2:")) return Profiles.hashPin(p.id, pin) == p.pinHash
        // PIN puesto con una versión vieja de la app (dependía del servidor): se pasa al formato nuevo
        val legacy = CloudSync.sha256Hex("jox3pin|${CloudSync.accountId(account)}|${p.id}|$pin")
        if (legacy != p.pinHash) return false
        val cur = all[p.id] ?: p
        put(cur.copy(pinHash = Profiles.hashPin(p.id, pin), ts = System.currentTimeMillis()), dirty = true)
        refresh()
        return true
    }

    /** Usa en este servidor el perfil que se eligió con otro (lo crea aquí si aún no existe). */
    fun adopt(p: Profile): Profile {
        val local = all[p.id]
        if (local == null || local.deleted || local.ts < p.ts) { put(p.copy(deleted = false), dirty = true); refresh() }
        return byId(p.id) ?: p
    }

    // ── Sincronización (CloudSync) ──

    internal fun updateCursor(v: Long) { cursor = v; save() }

    internal fun pendingChanges(): List<JSONObject> = dirty.keys.mapNotNull { all[it] }.map { p ->
        JSONObject().put("list", "profile").put("item", "p:${p.id}").put("ts", p.ts).put("deleted", p.deleted)
            .apply { if (!p.deleted) put("data", toJson(p).toString()) }
    }

    internal fun clearSent(sent: List<JSONObject>) {
        sent.forEach { c ->
            val id = c.optString("item").removePrefix("p:")
            if (dirty[id] == c.optLong("ts")) dirty.remove(id)
        }
        save()
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
                    wipeLibrary(account.key, id); changed = true
                }
            } else {
                val p = runCatching { fromJson(JSONObject(r.optString("data"))) }.getOrNull() ?: return@forEach
                if (p.id != id) return@forEach
                all[id] = p.copy(ts = ts, deleted = false); changed = true
            }
        }
        if (changed) {
            save(); refresh()
            Profiles.current?.let { cur -> if (all[cur.id]?.let { it.ts > cur.ts && !it.deleted } == true) Profiles.current = all[cur.id] }
        }
    }

    private fun toJson(p: Profile) = JSONObject().put("id", p.id).put("name", p.name).put("emoji", p.emoji)
        .put("color", p.color).put("pin", p.pinHash).put("created", p.created).put("ts", p.ts).put("deleted", p.deleted)
        .put("vault", p.vaultId).put("vaultN", p.vaultCount)

    private fun fromJson(o: JSONObject) = Profile(
        id = o.optString("id"), name = o.optString("name").take(20), emoji = o.optString("emoji").ifBlank { "😎" }.take(8),
        color = o.optLong("color", Profiles.COLORS[4]), pinHash = o.optString("pin"),
        created = o.optLong("created"), ts = o.optLong("ts"), deleted = o.optBoolean("deleted"),
        vaultId = o.optString("vault").filter { it in "0123456789abcdef" }.take(64), vaultCount = o.optInt("vaultN"),
    )

    companion object {
        /** Borra en este equipo el historial de un perfil (el principal nunca se borra así). */
        fun wipeLibrary(accountKey: String, profileId: String) {
            if (profileId == Profiles.MAIN) return
            val name = LibraryStore.fileName(accountKey, profileId)
            val dir = File(AppDirs.data, "library")
            listOf(name, "$name.bak", "$name.tmp").forEach { File(dir, it).delete() }
        }
    }
}
