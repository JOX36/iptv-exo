package com.jox3.iptvexo.tv.player

import android.content.Context
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.AccountStore
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.ServerHub
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.data.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Respaldo automático de canales en vivo: si un canal se cae o se queda cargando, la app pasa
 * sola a otra señal del MISMO canal (otra calidad del mismo servidor o el mismo canal en otro de
 * tus servidores), sin salir del reproductor. La señal que funcionó se recuerda: al volver a
 * abrir el canal arranca directo en ella ("siempre la mejor opción").
 */
object Failover {
    private const val PREFS = "jox3_player"
    private const val REMEMBER_MS = 12 * 3600_000L
    private const val LIST_TTL = 15 * 60_000L

    /** Otra señal del canal. */
    data class Alt(val account: Account, val id: String, val name: String, val tag: String) {
        val key get() = account.key + "|" + id
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_failover", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_failover", on).apply()

    // ── Mismo canal ──

    private val QUALITY = Regex(
        "\\b(4k|uhd|2160p?|fhd|1080[pi]?|hd|720p?|sd|480p?|hq|lq|hevc|h ?26[45]|x26[45]|50 ?fps|60 ?fps|fps|" +
            "backup|bkp|alt|alterno|op(cion)? ?\\d|server ?\\d|s\\d|vip|ott|direct[oa]?|multi ?audio)\\b"
    )
    private val COUNTRY = setOf("co", "col", "mx", "mex", "ar", "arg", "cl", "pe", "ec", "us", "usa", "es", "lat", "latam", "ve", "uy", "py", "bo", "cr", "pa", "do", "pr", "gt", "hn", "sv", "ni", "int")

    /**
     * Identidad de un canal para reconocer otras señales del mismo: sin calidad ("HD", "FHD"…),
     * sin marcas de respaldo ni país al principio. "CNN EN ESPAÑOL FHD" y "CO | CNN en Español HD"
     * dan lo mismo; "CNN International" y "ESPN 2" / "ESPN 3" no.
     */
    fun identity(name: String): String {
        var s = TvRepo.normalize(com.jox3.iptvexo.tv.data.Names.channel(name))
        s = s.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), " ").replace("+", " plus ").replace("&", " and ")
        s = s.replace(Regex("[^a-z0-9 ]+"), " ")
        s = QUALITY.replace(s, " ")
        val t = s.split(' ').filter { it.isNotBlank() }.toMutableList()
        while (t.size > 1 && t[0] in COUNTRY) t.removeAt(0)
        return t.joinToString("")
    }

    /** Preferencia de calidad (la de más arriba primero): FHD → HD → 4K → SD → sin etiqueta. */
    private fun rank(tag: String) = when {
        tag.startsWith("FHD") -> 0
        tag.startsWith("HD") -> 1
        tag.startsWith("4K") -> 2
        tag.isEmpty() -> 3
        else -> 4
    }

    // ── Listas de canales de cada servidor ──

    private val lists = HashMap<String, Pair<Long, List<Channel>>>()

    private suspend fun channelsOf(acc: Account, repo: TvRepo): List<Channel> {
        if (acc.key == repo.api.account.key) repo.peekIndex()?.channels?.let { return it }
        synchronized(lists) { lists[acc.key] }?.let { (t, l) -> if (System.currentTimeMillis() - t < LIST_TTL) return l }
        val l = withTimeoutOrNull(12_000) { runCatching { XtreamApi(acc).channels() }.getOrNull() } ?: return emptyList()
        synchronized(lists) { lists[acc.key] = System.currentTimeMillis() to l }
        return l
    }

    /**
     * Otras señales del canal [ch] (de [repo]), sin las de [skip]. Primero las del mismo servidor
     * (no gastan otra conexión), luego tus otros servidores en orden; dentro de cada uno, FHD → HD → 4K → SD.
     * Los servidores que están caídos (rojo) van al final.
     */
    suspend fun candidates(ctx: Context, repo: TvRepo, ch: Channel, skip: Set<String>): List<Alt> = withContext(Dispatchers.IO) {
        val id = identity(ch.name)
        if (id.length < 2) return@withContext emptyList()
        val me = repo.api.account
        val accounts = listOf(me) + AccountStore(ctx).shown().filter { it.key != me.key }
        val out = ArrayList<Alt>()
        accounts.sortedBy { a ->
            val st = ServerHub.status[a.key]
            when { a.key == me.key -> 0; st == null || st.ok -> 1; else -> 2 }
        }.forEach { acc ->
            val found = channelsOf(acc, repo).filter { c -> identity(c.name) == id }
                .map { c -> Alt(acc, c.id, c.name, ServerHub.tagOf(c.name)) }
                .filter { it.key !in skip && !(acc.key == me.key && it.id == ch.id) }
                .sortedBy { rank(it.tag) }
            out += found
        }
        out
    }

    // ── La señal que funcionó, por canal ──

    private fun memKey(acc: Account, ch: Channel) = "alt_" + acc.key.hashCode() + "_" + ch.id

    fun remember(ctx: Context, acc: Account, ch: Channel, alt: Alt) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(memKey(acc, ch), "${System.currentTimeMillis()}\u0001${alt.account.key}\u0001${alt.id}\u0001${alt.name}\u0001${alt.tag}")
            .apply()
    }

    fun forget(ctx: Context, acc: Account, ch: Channel) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(memKey(acc, ch)).apply()

    /** La señal de respaldo que funcionó la última vez (si fue hace menos de 12 horas y el servidor sigue). */
    fun remembered(ctx: Context, acc: Account, ch: Channel): Alt? {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(memKey(acc, ch), null) ?: return null
        val p = v.split('\u0001')
        if (p.size < 5 || System.currentTimeMillis() - (p[0].toLongOrNull() ?: 0) > REMEMBER_MS) return null
        val other = (AccountStore(ctx).shown() + acc).firstOrNull { it.key == p[1] } ?: return null
        return Alt(other, p[2], p[3], p[4])
    }

    /** "CNN EN ESPAÑOL FHD · servidor B" para los avisos. */
    fun label(ctx: Context, current: Account, alt: Alt): String {
        val tag = alt.tag.ifBlank { "otra señal" }
        if (alt.account.key == current.key) return tag
        val all = AccountStore(ctx).all()
        return "$tag · servidor ${ServerHub.letter(all, alt.account)} (${ServerHub.name(all, alt.account)})"
    }
}
