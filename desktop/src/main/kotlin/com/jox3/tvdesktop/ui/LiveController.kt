package com.jox3.tvdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.TvRepo
import com.jox3.tvdesktop.player.Aspect
import com.jox3.tvdesktop.player.VideoPlayer
import kotlinx.coroutines.launch

/**
 * Cómo se ve el reproductor: en la vista previa, ocupando la ventana, en mini (ventana
 * pequeña siempre encima) o en PIP (ventanita en la esquina mientras navegas por el programa).
 */
enum class ViewMode { EMBEDDED, FULL, MINI, PIP }

/** Panel de opciones abierto sobre el video. */
enum class Panel { NONE, CHANNELS, AUDIO, SUBTITLES, PICTURE, SPEED, SLEEP, INFO, TOGETHER, PROGRAMS, GUIDE_PICK, RECORD }

/**
 * Película o episodio en reproducción. [next] = siguiente episodio (para "Siguiente" y
 * para seguir solo al terminar), [image] = carátula para la información.
 */
data class VodSession(
    val item: SavedItem,
    val title: String,
    val subtitle: String,
    val image: String,
    val url: String,
    val nextLabel: String? = null,
    val next: (() -> Unit)? = null,
    /** Repeticiones y grabaciones: no entran a "Seguir viendo" ni a "Porque viste…". */
    val noHistory: Boolean = false,
)

/** Pedido de abrir algo desde el buscador: un canal, la ficha de una película o de una serie. */
data class OpenRequest(val kind: String, val id: String, val name: String, val image: String,
                       val ext: String = "", val categoryId: String = "")

/** El servidor en uso, todos los guardados y cómo cambiar a otro (abriendo algo al llegar). */
data class ServerCtx(
    val account: com.jox3.tvdesktop.data.Account,
    val all: List<com.jox3.tvdesktop.data.Account>,
    val switchTo: (com.jox3.tvdesktop.data.Account, com.jox3.tvdesktop.data.ServerHub.Copy?) -> Unit,
)

val LocalServers = androidx.compose.runtime.staticCompositionLocalOf<ServerCtx?> { null }

/**
 * Lo que se está viendo y cómo: canal en vivo (con la lista para pasar de canal) o
 * película/episodio, modo de vista, panel abierto, apagado automático, número tecleado
 * y cuándo mostrar los controles.
 */
class LiveController(val repo: TvRepo, val player: VideoPlayer) {
    var current by mutableStateOf<Channel?>(null); private set
    var list by mutableStateOf<List<Channel>>(emptyList()); private set
    var listName by mutableStateOf(""); private set
    var mode by mutableStateOf(ViewMode.EMBEDDED)
    var panel by mutableStateOf(Panel.NONE)
    /** Hora (ms) del apagado automático, o null. */
    var sleepAt by mutableStateOf<Long?>(null)
    /** Número de canal que se está escribiendo con el teclado. */
    var typedNumber by mutableStateOf("")
    /** Mensaje corto que aparece un momento sobre el video ("Volumen 80 %", "Estirar"…). */
    var toast by mutableStateOf<Pair<String, Long>?>(null); private set
    /** Película o episodio en reproducción (null = en vivo o nada). */
    var vod by mutableStateOf<VodSession?>(null); private set
    /** Momento en que se pidieron los controles (clic, cambio de canal…): se ven 6 s. */
    var overlayAt by mutableLongStateOf(System.currentTimeMillis())

    val hasMedia: Boolean get() = current != null || vod != null
    /** La sección visible registra aquí cómo pasar de categoría (← →) y desplazar su lista (↑ ↓). */
    var categoryStep: ((Int) -> Unit)? = null
    var listScroll: ((Int) -> Unit)? = null

    /** Algo que hay que abrir (lo pide el buscador; lo atiende la sección que corresponde). */
    var openRequest by mutableStateOf<OpenRequest?>(null)

    /** Muestra la información y los controles durante unos segundos. */
    fun poke() { overlayAt = System.currentTimeMillis() }

    fun play(ch: Channel, list: List<Channel>, listName: String) {
        leaveTogether()
        saveVodProgress()
        if (mode == ViewMode.PIP) mode = ViewMode.EMBEDDED // el canal se ve en la vista previa
        vod = null
        this.list = list
        this.listName = listName
        current = ch
        player.play(repo.api.liveUrl(ch.id), ch.name, live = true)
        repo.library.addRecentChannel(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
        poke()
    }

    /** Reproduce una película o episodio en pantalla completa, desde [startMs]. */
    fun playVod(session: VodSession, startMs: Long) {
        saveVodProgress()
        current?.let { lastLive = Triple(it, list, listName) }
        current = null
        vod = session
        panel = Panel.NONE
        player.play(session.url, session.title, live = false, startMs = startMs)
        poke()
    }

    /** Cerrar la película = salir de Ver juntos (los demás siguen viendo). Igual que Android. */
    private fun leaveTogether() {
        if (vod != null && com.jox3.tvdesktop.player.WatchTogether.phase != com.jox3.tvdesktop.player.WatchTogether.Phase.Off)
            com.jox3.tvdesktop.player.WatchTogether.leave()
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /**
     * Ver un programa de la guía desde el inicio (o uno que ya pasó), si el canal guarda archivo.
     * Se ve como una película: se puede pausar, adelantar y retroceder. Igual que Android.
     */
    fun playCatchup(ch: Channel, p: com.jox3.tvdesktop.data.EpgItem) {
        scope.launch {
            val tz = repo.serverTimeZone()
            val minutes = (((p.end - p.start) / 60_000L).toInt() + 5).coerceIn(5, 600)
            val url = repo.api.timeshiftUrl(ch.id, p.start, minutes, tz)
            val item = SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId)
            playVod(VodSession(item, p.cleanTitle, "${ch.name} · ⏪ desde el inicio", p.icon.ifBlank { ch.logo }, url, noHistory = true), 0)
            mode = ViewMode.FULL
        }
    }

    /** Lo de "Desde el inicio" del reproductor: el programa de ahora, o por qué no se puede. */
    fun restartCurrent() {
        val ch = current ?: return
        val now = System.currentTimeMillis()
        val cur = repo.cachedEpg(ch)?.firstOrNull { now in it.start until it.end }
        val msg = when {
            ch.archiveDays <= 0 -> "Este canal no guarda programas pasados en tu servidor. Busca los canales con ⏪."
            cur == null -> "No encuentro qué programa dan ahora en la guía de este canal."
            !TvRepo.canCatchup(ch, cur) -> "El programa acaba de empezar. Prueba en un minuto."
            else -> null
        }
        if (msg != null) notice(msg) else playCatchup(ch, cur!!)
    }

    /** Ver una grabación (también mientras se sigue grabando). */
    fun playRecording(r: com.jox3.tvdesktop.data.Recorder.Rec) {
        val f = java.io.File(r.file)
        if (!f.exists() || f.length() == 0L) { notice("La grabación todavía no tiene video"); return }
        val fmt = java.text.SimpleDateFormat("d MMM · h:mm a", java.util.Locale.forLanguageTag("es"))
        val item = SavedItem("live", r.chId, r.chName, r.logo)
        playVod(VodSession(item, r.title, "${r.chName} · grabado ${fmt.format(java.util.Date(r.start))}", r.logo, f.absolutePath, noHistory = true), 0)
        mode = ViewMode.FULL
    }

    /** Guarda dónde va la película o episodio (cada pocos segundos y al salir). */
    fun saveVodProgress() {
        val v = vod ?: return
        if (v.item.kind == "trailer" || v.noHistory) return // tráileres, repeticiones y grabaciones no van a "continuar viendo"
        val len = player.lengthMs
        if (len <= 0) return
        val pos = if (player.state == com.jox3.tvdesktop.player.PlayState.Ended) len else player.timeMs
        repo.library.saveProgress(v.item, pos, len)
    }

    /**
     * ¿La terminó (o vio más del 90 %) y no hay otro episodio? → el título para "Porque viste…"
     * (la película, o la serie si era su último episodio). Igual que Android.
     */
    fun finishedItem(): SavedItem? {
        val v = vod ?: return null
        if (v.item.kind == "trailer" || v.noHistory || v.next != null) return null
        val len = player.lengthMs
        val ended = player.state == com.jox3.tvdesktop.player.PlayState.Ended
        val done = ended || (len > 0 && player.timeMs.toFloat() / len >= 0.9f)
        if (!done) return null
        return if (v.item.kind == "episode") {
            if (v.item.seriesId.isBlank()) null
            else SavedItem("series", v.item.seriesId, v.title, v.image, categoryId = v.item.categoryId)
        } else v.item
    }

    /** Canal siguiente (+1) o anterior (-1) de la lista actual, dando la vuelta. */
    fun step(delta: Int) {
        val l = list
        val cur = current ?: return
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
        val next = l[((i + delta) % l.size + l.size) % l.size]
        play(next, l, listName)
        flash("${next.num}  ${next.name}")
    }

    /** Adelanta o atrasa la película (en vivo no hace nada). */
    fun seekBy(deltaMs: Long) {
        if (player.isLive) return
        player.seekBy(deltaMs)
        flash(if (deltaMs > 0) "+${deltaMs / 1000} s" else "${deltaMs / 1000} s")
        poke()
    }

    fun cycleSpeed() {
        val speeds = SPEEDS
        val i = speeds.indexOfFirst { kotlin.math.abs(it - player.rate) < 0.01f }
        val n = speeds[(i + 1).mod(speeds.size)]
        player.setSpeed(n)
        flash("Velocidad ${speedLabel(n)}")
    }

    /** Ir al canal con ese número (de la lista actual). */
    fun jumpTo(number: Int): Boolean {
        val ch = list.firstOrNull { it.num == number } ?: return false
        play(ch, list, listName)
        flash("${ch.num}  ${ch.name}")
        return true
    }

    /** Último canal en vivo (para seguir viéndolo al volver a En vivo). */
    private var lastLive: Triple<Channel, List<Channel>, String>? = null

    /** Detiene el canal en vivo pero lo recuerda para retomarlo con [resumeLive]. */
    fun pauseLive() {
        val ch = current ?: return
        lastLive = Triple(ch, list, listName)
        stop()
    }

    /** Vuelve a poner el último canal en vivo, si se detuvo al cambiar de sección. */
    fun resumeLive() {
        if (hasMedia) return
        val (ch, l, n) = lastLive ?: return
        lastLive = null
        play(ch, l, n)
    }

    fun stop() {
        leaveTogether()
        saveVodProgress()
        player.stop()
        current = null
        vod = null
        mode = ViewMode.EMBEDDED
        panel = Panel.NONE
    }

    fun cycleAspect() {
        val all = Aspect.entries
        val next = all[(all.indexOf(player.aspect) + 1) % all.size]
        player.setAspectMode(next)
        flash("Imagen: ${next.label}")
    }

    fun cycleAudio() {
        val t = player.audioTracks
        if (t.size < 2) { flash(if (t.isEmpty()) "Sin pistas de audio" else "Solo hay un audio"); return }
        val next = t[(t.indexOfFirst { it.id == player.audioTrack } + 1).mod(t.size)]
        player.selectAudio(next.id)
        flash("Audio: ${next.name}")
    }

    fun cycleSubtitles() {
        val t = player.subTracks
        if (t.isEmpty()) { flash("No hay subtítulos disponibles"); return }
        val i = t.indexOfFirst { it.id == player.subTrack }
        if (i == t.size - 1) { player.selectSubtitle(-1); flash("Subtítulos: no") }
        else { val n = t[i + 1]; player.selectSubtitle(n.id); flash("Subtítulos: ${n.name}") }
    }

    fun changeVolume(delta: Int) {
        player.changeVolume(delta)
        flash("Volumen ${player.volume} %")
    }

    fun toggleMute() {
        player.applyMute(!player.muted)
        flash(if (player.muted) "Sin sonido" else "Sonido activado")
    }

    fun setSleep(minutes: Int?) {
        sleepAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        flash(if (minutes == null) "Apagado automático: no" else "Se apagará en $minutes min")
    }

    fun flash(text: String) { toast = text to System.currentTimeMillis() }

    /** Aviso más largo abajo al centro, encima de todo (recordatorios, grabaciones, desde el inicio). */
    var notice by mutableStateOf<Pair<String, Long>?>(null); private set
    fun notice(text: String) { notice = text to System.currentTimeMillis() }

    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        fun speedLabel(r: Float) = if (r == 1f) "normal" else "${"%.2f".format(r).trimEnd('0').trimEnd(',', '.')}×"
    }
}
