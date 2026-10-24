package com.jox3.iptvexo.tv.data

import android.content.Context

/**
 * Qué servidores ve cada perfil en ESTE equipo.
 *
 * - Un perfil con servidores propios (los de su código JOX3 o los que agregó estando en su
 *   perfil) ve SOLO esos. No se le suman los de la casa.
 * - Los demás perfiles ven los servidores "de la casa" (sin dueño) y nunca los de otra persona.
 * - Un servidor puede ser de la casa y estar también en el código de alguien (lo ven todos).
 */
object ServerAccess {
    private const val PREFS = "tv_owners"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Perfil dueño del servidor en este equipo (null = de la casa). */
    fun owner(ctx: Context, accKey: String): String? = prefs(ctx).getString("own.$accKey", null)

    fun setOwner(ctx: Context, accKey: String, profileId: String?) {
        prefs(ctx).edit().apply { if (profileId == null) remove("own.$accKey") else putString("own.$accKey", profileId) }.apply()
    }

    /** Servidores del código JOX3 del perfil (por servidor|usuario), según lo último que se vio aquí. */
    fun vaultKeys(ctx: Context, profileId: String): Set<String> =
        prefs(ctx).getStringSet("vk.$profileId", emptySet()).orEmpty()

    fun setVaultKeys(ctx: Context, profileId: String, keys: Set<String>) {
        prefs(ctx).edit().putStringSet("vk.$profileId", keys).apply()
    }

    private fun isMine(ctx: Context, a: Account, p: Profile) = owner(ctx, a.key) == p.id || a.key in vaultKeys(ctx, p.id)

    /** Servidores que ve el perfil (sin perfil: todos). */
    fun visible(ctx: Context, all: List<Account>, p: Profile?): List<Account> {
        if (p == null) return all
        val mine = all.filter { isMine(ctx, it, p) }
        if (mine.isNotEmpty()) return mine
        return all.filter { owner(ctx, it.key).let { o -> o == null || o == p.id } }
    }

    /** Servidores que el perfil puede elegir para su código: los suyos y los de la casa (nunca los de otro). */
    fun candidates(ctx: Context, all: List<Account>, p: Profile): List<Account> =
        all.filter { owner(ctx, it.key).let { o -> o == null || o == p.id } }

    /** Perfil con servidores propios en este equipo. */
    fun hasOwn(ctx: Context, all: List<Account>, p: Profile) = all.any { isMine(ctx, it, p) }

    /**
     * Se agregó un servidor nuevo estando en un perfil que tiene servidores propios:
     * queda como suyo (y entra a su código JOX3 la próxima vez que se sincronice).
     */
    fun claimNew(ctx: Context, accounts: AccountStore, acc: Account) {
        val p = Profiles.current ?: return
        if (!hasOwn(ctx, accounts.all().filter { it.key != acc.key }, p)) return
        setOwner(ctx, acc.key, p.id)
    }

    /**
     * Servidores que llegaron del código JOX3 de [p]: los que no estaban en el equipo quedan como
     * suyos. Una sola vez por perfil y equipo también se ordenan los que ya se habían agregado antes
     * de esta versión: son suyos si ningún otro perfil de este equipo los ha usado.
     */
    fun claimFromVault(ctx: Context, p: Profile, vault: List<Account>, newlyAdded: Set<String>, accounts: AccountStore) {
        setVaultKeys(ctx, p.id, vault.map { it.key }.toSet())
        vault.filter { it.key in newlyAdded && owner(ctx, it.key) == null }.forEach { setOwner(ctx, it.key, p.id) }
        val flag = "migrated.${p.id}"
        if (prefs(ctx).getBoolean(flag, false)) return
        val device = accounts.all()
        val lib = ctx.applicationContext.getSharedPreferences("tv_library", Context.MODE_PRIVATE).all
        vault.forEach { v ->
            val acc = device.firstOrNull { it.key == v.key } ?: return@forEach
            if (owner(ctx, acc.key) != null) return@forEach
            val others = ProfileStore(ctx.applicationContext, acc).list.filter { it.id != p.id }
            val usedByOthers = others.any { o ->
                val ns = ProfileStore.libraryNs(acc.key, o.id) + "."
                lib.any { (k, value) -> k.startsWith(ns) && value is String && value.length > 2 && value != "[]" && value != "{}" }
            }
            if (!usedByOthers) setOwner(ctx, acc.key, p.id)
        }
        prefs(ctx).edit().putBoolean(flag, true).apply()
    }

    /** Al dejar de guardar el código: sus servidores vuelven a ser de la casa en este equipo. */
    fun release(ctx: Context, p: Profile) {
        val pr = prefs(ctx)
        val ed = pr.edit()
        pr.all.forEach { (k, v) -> if (k.startsWith("own.") && v == p.id) ed.remove(k) }
        ed.remove("vk.${p.id}")
        ed.apply()
    }

    fun forget(ctx: Context, accKey: String) = setOwner(ctx, accKey, null)
}
