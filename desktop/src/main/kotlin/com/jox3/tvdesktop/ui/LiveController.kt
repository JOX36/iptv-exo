package com.jox3.tvdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.TvRepo
import com.jox3.tvdesktop.player.Aspect
import com.jox3.tvdesktop.player.VideoPlayer

/** Cómo se ve el reproductor: en la vista previa, ocupando la ventana, o en mini (siempre encima). */
enum class ViewMode { EMBEDDED, FULL, MINI }

/** Panel de opciones abierto sobre el video. */
enum class Panel { NONE, CHANNELS, AUDIO, SUBTITLES, PICTURE, SLEEP, INFO }

/**
 * Lo que se está viendo en vivo y cómo: canal actual, la lista para pasar de canal,
 * modo de vista, panel abierto, apagado automático y el número que se está tecleando.
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

    fun play(ch: Channel, list: List<Channel>, listName: String) {
        this.list = list
        this.listName = listName
        current = ch
        player.play(repo.api.liveUrl(ch.id), ch.name, live = true)
        repo.library.addRecentChannel(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
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

    /** Ir al canal con ese número (de la lista actual). */
    fun jumpTo(number: Int): Boolean {
        val ch = list.firstOrNull { it.num == number } ?: return false
        play(ch, list, listName)
        flash("${ch.num}  ${ch.name}")
        return true
    }

    fun stop() {
        player.stop()
        current = null
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
        if (t.isEmpty()) { flash("Este canal no tiene subtítulos"); return }
        val i = t.indexOfFirst { it.id == player.subTrack }
        if (i == t.size - 1) { player.selectSubtitle(-1); flash("Subtítulos: no") }
        else { val n = t[i + 1]; player.selectSubtitle(n.id); flash("Subtítulos: ${n.name}") }
    }

    fun changeVolume(delta: Int) {
        player.changeVolume(delta)
        flash("Volumen ${player.volume} %")
    }

    fun toggleMute() {
        player.setMuted(!player.muted)
        flash(if (player.muted) "Sin sonido" else "Sonido activado")
    }

    fun setSleep(minutes: Int?) {
        sleepAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        flash(if (minutes == null) "Apagado automático: no" else "Se apagará en $minutes min")
    }

    fun flash(text: String) { toast = text to System.currentTimeMillis() }
}
