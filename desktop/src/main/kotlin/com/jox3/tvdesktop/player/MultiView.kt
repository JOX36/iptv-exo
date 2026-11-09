package com.jox3.tvdesktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.Settings
import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Multipantalla (solo PC): hasta 4 canales en vivo a la vez, cada uno con su propio reproductor
 * VLC. Suena solo el resaltado. Cada cuadro usa una conexión de la cuenta, así que nunca se
 * pasa del máximo que permite el servidor.
 */
object MultiView {
    const val MAX = 4

    enum class Layout(val label: String, val slots: Int) { TWO("2 lado a lado", 2), FOUR("4 en cuadrícula", 4), BIG("1 grande + 3", 4) }

    class Tile(val channel: Channel, val player: VideoPlayer)

    /** ¿Está abierta? */
    var open by mutableStateOf(false); private set
    var layout by mutableStateOf(Layout.FOUR); private set
    /** Cuadros en orden; null = libre. */
    val tiles = mutableStateListOf<Tile?>()
    /** Cuadro que suena. */
    var audio by mutableIntStateOf(0); private set
    /** Cuadro agrandado (doble clic), -1 = cuadrícula. */
    var expanded by mutableIntStateOf(-1); private set

    private var account: Account? = null
    private var urlOf: (Channel) -> String = { "" }

    /** Conexiones que permite la cuenta (lo que informa el servidor; si no informa, 4). */
    var maxConnections = MAX; private set

    fun used() = tiles.count { it != null }
    fun limit() = minOf(MAX, maxConnections, layout.slots)
    fun anyPlaying() = open && tiles.any { it?.player?.state == PlayState.Playing }

    /**
     * Abre la multipantalla. [first]: canal que se estaba viendo (queda en el primer cuadro).
     * Si no viene ninguno, se recupera la última combinación guardada de esta cuenta.
     */
    fun start(acc: Account, maxCons: String, url: (Channel) -> String, first: Channel?) {
        if (open) { first?.let { add(it) }; return }
        account = acc
        urlOf = url
        maxConnections = maxCons.trim().toIntOrNull()?.takeIf { it > 0 } ?: MAX
        val saved = load(acc)
        layout = saved.first
        tiles.clear()
        repeat(layout.slots) { tiles.add(null) }
        audio = 0; expanded = -1
        open = true
        val channels = (listOfNotNull(first) + saved.second.filter { it.id != first?.id }).take(limit())
        channels.forEachIndexed { i, ch -> put(i, ch) }
        applyAudio()
    }

    /** Al cerrar el programa: suelta los reproductores esperando a que terminen (antes de apagar VLC). */
    fun closeNow() {
        if (!open && tiles.isEmpty()) return
        tiles.forEach { t -> runCatching { t?.player?.release() } }
        tiles.clear()
        open = false
        expanded = -1
    }

    fun close() {
        tiles.forEach { t -> t?.player?.let { p -> Thread { runCatching { p.release() } }.apply { isDaemon = true }.start() } }
        tiles.clear()
        open = false
        expanded = -1
    }

    /** Canal que sonaba (para seguir viéndolo en grande al salir). */
    fun audioChannel(): Channel? = tiles.getOrNull(audio)?.channel ?: tiles.firstOrNull { it != null }?.channel

    fun changeLayout(l: Layout) {
        if (l == layout) return
        layout = l
        // Si sobran cuadros (de 4 a 2), se cierran los últimos
        while (tiles.size > l.slots) { tiles.removeAt(tiles.size - 1)?.player?.let { p -> Thread { p.release() }.start() } }
        while (tiles.size < l.slots) tiles.add(null)
        if (audio >= tiles.size || tiles[audio] == null) audio = tiles.indexOfFirst { it != null }.coerceAtLeast(0)
        expanded = -1
        applyAudio(); save()
    }

    /** Agrega en el primer cuadro libre. Devuelve un mensaje si no se puede. */
    fun add(ch: Channel): String? {
        if (tiles.any { it?.channel?.id == ch.id }) return "Ese canal ya está en la multipantalla"
        val free = tiles.indexOfFirst { it == null }
        if (free < 0 || used() >= limit()) return limitMessage()
        put(free, ch)
        return null
    }

    /** Pone [ch] en el cuadro [i] (reemplaza lo que había). */
    fun put(i: Int, ch: Channel): String? {
        if (i !in tiles.indices) return null
        if (tiles[i] == null && used() >= limit()) return limitMessage()
        if (tiles.withIndex().any { (k, t) -> k != i && t?.channel?.id == ch.id }) return "Ese canal ya está en la multipantalla"
        val player = tiles[i]?.player ?: VideoPlayer()
        player.play(urlOf(ch), ch.name, live = true)
        tiles[i] = Tile(ch, player)
        if (tiles.count { it != null } == 1) audio = i
        applyAudio(); save()
        return null
    }

    fun remove(i: Int) {
        val t = tiles.getOrNull(i) ?: return
        tiles[i] = null
        Thread { runCatching { t.player.release() } }.apply { isDaemon = true }.start()
        if (expanded == i) expanded = -1
        if (audio == i) audio = tiles.indexOfFirst { it != null }.coerceAtLeast(0)
        applyAudio(); save()
    }

    fun focusAudio(i: Int) { if (tiles.getOrNull(i) != null) { audio = i; applyAudio() } }

    fun toggleExpand(i: Int) {
        if (tiles.getOrNull(i) == null) return
        expanded = if (expanded == i) -1 else i
        focusAudio(i)
    }

    fun collapse() { expanded = -1 }

    private fun applyAudio() {
        tiles.forEachIndexed { k, t -> t?.player?.applyMute(k != audio) }
    }

    private fun limitMessage(): String =
        if (maxConnections < MAX && used() >= maxConnections)
            "Tu cuenta permite $maxConnections conexión${if (maxConnections == 1) "" else "es"} a la vez: quita un canal para poner otro"
        else "No caben más canales en este diseño"

    // ── Última combinación (por cuenta) ──

    private fun key(acc: Account) = "multi." + com.jox3.tvdesktop.data.LibraryStore.keyHash(acc.key)

    private fun save() {
        val acc = account ?: return
        val arr = JSONArray()
        tiles.forEach { t ->
            t?.channel?.let { c ->
                arr.put(JSONObject().put("id", c.id).put("name", c.name).put("logo", c.logo).put("cat", c.categoryId)
                    .put("num", c.num).put("epg", c.epgId).put("arch", c.archiveDays))
            }
        }
        Settings.put(key(acc), JSONObject().put("layout", layout.name).put("ch", arr).toString())
    }

    private fun load(acc: Account): Pair<Layout, List<Channel>> = runCatching {
        val o = JSONObject(Settings.string(key(acc)).ifBlank { "{}" })
        val l = runCatching { Layout.valueOf(o.optString("layout")) }.getOrDefault(Layout.FOUR)
        val arr = o.optJSONArray("ch") ?: JSONArray()
        l to (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { c ->
            Channel(c.optString("id"), c.optString("name"), c.optString("logo"), c.optString("cat"), c.optInt("num"),
                epgId = c.optString("epg"), archiveDays = c.optInt("arch"))
        }
    }.getOrElse { Log.w("Multipantalla: no se pudo leer la última combinación"); Layout.FOUR to emptyList() }
}
