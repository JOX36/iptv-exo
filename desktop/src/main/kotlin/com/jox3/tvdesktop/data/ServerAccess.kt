package com.jox3.tvdesktop.data

import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Qué servidores ve cada perfil en ESTE equipo (misma lógica que Android, tv/data/ServerAccess.kt).
 *
 * - Un perfil con servidores propios (los de su código JOX3 o los que agregó estando en su
 *   perfil) ve SOLO esos. No se le suman los de la casa.
 * - Los demás perfiles ven los servidores "de la casa" (sin dueño) y nunca los de otra persona.
 * - Un servidor puede ser de la casa y estar también en el código de alguien (lo ven todos).
 *
 * Se guarda en owners.json: {own: {servidor → perfil}, vk: {perfil → [servidores del código]}, migrated: {perfil: true}}.
 */
object ServerAccess {
    private val file get() = File(AppDirs.data, "owners.json")

    @Synchronized private fun read(): JSONObject = SafeFile.read(file) { JSONObject(it) } ?: JSONObject()
    @Synchronized private fun write(o: JSONObject) {
        try { SafeFile.write(file, o.toString()) } catch (e: Exception) { Log.w("No se pudo guardar de quién es cada servidor", e) }
    }
    private fun JSONObject.obj(k: String) = optJSONObject(k) ?: JSONObject().also { put(k, it) }

    /** Perfil dueño del servidor en este equipo (null = de la casa). */
    fun owner(accKey: String): String? = read().optJSONObject("own")?.optString(accKey)?.ifEmpty { null }

    @Synchronized
    fun setOwner(accKey: String, profileId: String?) {
        val o = read()
        if (profileId == null) o.obj("own").remove(accKey) else o.obj("own").put(accKey, profileId)
        write(o)
    }

    /** Servidores del código JOX3 del perfil (por servidor|usuario), según lo último que se vio aquí. */
    fun vaultKeys(profileId: String): Set<String> {
        val a = read().optJSONObject("vk")?.optJSONArray(profileId) ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    @Synchronized
    fun setVaultKeys(profileId: String, keys: Set<String>) {
        val o = read()
        o.obj("vk").put(profileId, JSONArray(keys.toList()))
        write(o)
    }

    private fun isMine(a: Account, p: Profile, own: JSONObject, vk: Set<String>) =
        own.optString(a.key) == p.id || a.key in vk

    /** Servidores que ve el perfil (sin perfil: todos). */
    fun visible(all: List<Account>, p: Profile?): List<Account> {
        if (p == null) return all
        val own = read().optJSONObject("own") ?: JSONObject()
        val vk = vaultKeys(p.id)
        val mine = all.filter { isMine(it, p, own, vk) }
        if (mine.isNotEmpty()) return mine
        return all.filter { own.optString(it.key).let { o -> o.isEmpty() || o == p.id } }
    }

    /** Servidores que el perfil puede elegir para su código: los suyos y los de la casa (nunca los de otro). */
    fun candidates(all: List<Account>, p: Profile): List<Account> {
        val own = read().optJSONObject("own") ?: JSONObject()
        return all.filter { own.optString(it.key).let { o -> o.isEmpty() || o == p.id } }
    }

    /** Perfil con servidores propios en este equipo. */
    fun hasOwn(all: List<Account>, p: Profile): Boolean {
        val own = read().optJSONObject("own") ?: JSONObject()
        val vk = vaultKeys(p.id)
        return all.any { isMine(it, p, own, vk) }
    }

    /**
     * Se agregó un servidor nuevo estando en un perfil que tiene servidores propios:
     * queda como suyo (y entra a su código JOX3 la próxima vez que se sincronice).
     */
    fun claimNew(accounts: AccountStore, acc: Account) {
        val p = Profiles.current ?: return
        if (!hasOwn(accounts.all().filter { it.key != acc.key }, p)) return
        setOwner(acc.key, p.id)
    }

    /**
     * Servidores que llegaron del código JOX3 de [p]: los que no estaban en el equipo quedan como
     * suyos. Una sola vez por perfil y equipo también se ordenan los que ya estaban: son suyos si
     * ningún otro perfil de este equipo los ha usado.
     */
    fun claimFromVault(p: Profile, vault: List<Account>, newlyAdded: Set<String>, accounts: AccountStore) {
        setVaultKeys(p.id, vault.map { it.key }.toSet())
        vault.filter { it.key in newlyAdded && owner(it.key) == null }.forEach { setOwner(it.key, p.id) }
        if (read().optJSONObject("migrated")?.optBoolean(p.id) == true) return
        val device = accounts.all()
        vault.forEach { v ->
            val acc = device.firstOrNull { it.key == v.key } ?: return@forEach
            if (owner(acc.key) != null) return@forEach
            val others = ProfileStore(acc).list.filter { it.id != p.id }
            val usedByOthers = others.any { o -> libraryUsed(acc.key, o.id) }
            if (!usedByOthers) setOwner(acc.key, p.id)
        }
        val o = read(); o.obj("migrated").put(p.id, true); write(o)
    }

    /** ¿Ese perfil ya tiene historial en ese servidor (favoritos, recientes o seguir viendo)? */
    private fun libraryUsed(accKey: String, profileId: String): Boolean {
        val f = File(AppDirs.data, "library/" + LibraryStore.fileName(accKey, profileId))
        if (!f.exists()) return false
        return runCatching {
            val o = JSONObject(f.readText())
            listOf("fav", "recent", "progress").any { (o.optJSONArray(it)?.length() ?: 0) > 0 }
        }.getOrDefault(false)
    }

    /** Al dejar de guardar el código: sus servidores vuelven a ser de la casa en este equipo. */
    @Synchronized
    fun release(p: Profile) {
        val o = read()
        val own = o.obj("own")
        own.keys().asSequence().toList().forEach { k -> if (own.optString(k) == p.id) own.remove(k) }
        o.obj("vk").remove(p.id)
        write(o)
    }

    fun forget(accKey: String) = setOwner(accKey, null)
}
