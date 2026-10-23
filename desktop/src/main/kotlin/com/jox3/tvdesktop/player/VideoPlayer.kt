package com.jox3.tvdesktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.Settings
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Formato de imagen. "Estirar" es el predeterminado (como en la app Android). */
enum class Aspect(val label: String) {
    STRETCH("Estirar"), FIT("Ajustar"), CROP("Recortar"), R16_9("16:9"), R4_3("4:3");
}

data class Track(val id: Int, val name: String)

sealed interface PlayState {
    data object Idle : PlayState
    data object Opening : PlayState
    data class Buffering(val percent: Int) : PlayState
    data object Playing : PlayState
    data object Paused : PlayState
    data class Reconnecting(val attempt: Int, val max: Int) : PlayState
    data class Failed(val message: String) : PlayState
    data object Ended : PlayState
}

/**
 * Reproductor único del programa, sobre libVLC (vlcj).
 *
 * - VLC decodifica y entrega cada cuadro; aquí se copia a una imagen de Skia que
 *   Compose dibuja ([frame]). Así se pueden poner controles encima del video.
 * - Todas las órdenes a VLC pasan por un solo hilo ("vlc-control"): VLC no tolera
 *   que se le llame desde sus propios avisos, y así nunca se traba la ventana.
 * - En vivo: si la señal se cae o se queda cargando, se reconecta sola (hasta 6 intentos).
 */
class VideoPlayer {
    // ── Estado visible para la interfaz ──
    var state by mutableStateOf<PlayState>(PlayState.Idle); private set
    var title by mutableStateOf(""); private set
    var url by mutableStateOf(""); private set
    var isLive by mutableStateOf(true); private set
    var frame by mutableStateOf<ImageBitmap?>(null); private set
    /** Sube con cada cuadro nuevo: el dibujo del video lo lee para repintarse. */
    var frameVersion by mutableLongStateOf(0L); private set
    var videoWidth by mutableIntStateOf(0); private set
    var videoHeight by mutableIntStateOf(0); private set
    /** Relación de aspecto real del video (corrige videos anamórficos). */
    var videoAspect by mutableFloatStateOf(16f / 9f); private set
    var volume by mutableIntStateOf(Settings.int("player.volume", 100).coerceIn(0, 200)); private set
    var muted by mutableStateOf(false); private set
    var aspect by mutableStateOf(runCatching { Aspect.valueOf(Settings.string("player.aspect", "STRETCH")) }.getOrDefault(Aspect.STRETCH))
        private set
    var audioTracks by mutableStateOf<List<Track>>(emptyList()); private set
    var audioTrack by mutableIntStateOf(-1); private set
    var subTracks by mutableStateOf<List<Track>>(emptyList()); private set
    var subTrack by mutableIntStateOf(-1); private set
    var timeMs by mutableLongStateOf(0L); private set
    var lengthMs by mutableLongStateOf(0L); private set
    var rate by mutableFloatStateOf(1f); private set

    val available: Boolean get() = mp != null
    val unavailableReason: String? get() = if (mp == null) VlcRuntime.error ?: "El reproductor no está disponible" else null

    // ── Internos ──
    private val ctl = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vlc-control").apply { isDaemon = true } }
    private val mp: EmbeddedMediaPlayer? = VlcRuntime.factory()?.let { f ->
        try { f.mediaPlayers().newEmbeddedMediaPlayer() } catch (e: Throwable) { Log.e("No se pudo crear el reproductor", e); null }
    }
    /** Cambia con cada video nuevo: anula reconexiones pendientes del video anterior. */
    @Volatile private var token = 0
    @Volatile private var options: Array<String> = emptyArray()
    private var retries = 0
    private var retryTask: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    @Volatile private var lastProgressAt = 0L
    @Volatile private var subitemTried = false

    // Triple búfer: VLC escribe en uno mientras la ventana dibuja otro (sin parpadeos).
    // Las imágenes viejas NUNCA se liberan a mano: si la ventana todavía estaba dibujando
    // una y se liberaba, el programa se cerraba de golpe. Las libera Java cuando ya nadie las usa.
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var back = 0
    private var bytes = ByteArray(0)
    private var info: ImageInfo? = null

    init {
        val player = mp
        if (player != null) {
            val surface = VlcRuntime.factory()!!.videoSurfaces().newVideoSurface(Formats(), Renderer(), true)
            player.videoSurface().set(surface)
            player.events().addMediaPlayerEventListener(Events())
            ctl.execute { runCatching { player.audio().setVolume(volume) } }
            // Vigilante: si un canal en vivo se queda cargando mucho tiempo, se reconecta.
            watchdog = ctl.scheduleWithFixedDelay({ checkStall() }, 3, 3, TimeUnit.SECONDS)
        }
    }

    // ═══ Órdenes ═══

    /** Reproduce [mrl]. [live] = canal en vivo (reconexión automática, poco búfer). */
    fun play(mrl: String, title: String, live: Boolean, startMs: Long = 0) {
        val player = mp ?: run { state = PlayState.Failed(unavailableReason.orEmpty()); return }
        token++
        val my = token
        this.title = title; this.url = mrl; this.isLive = live
        retries = 0
        subitemTried = false
        timeMs = 0; lengthMs = 0
        state = PlayState.Opening
        options = buildList {
            // Búfer de red: en vivo se elige en Ajustes (más búfer = menos cortes, pero más demora al cambiar)
            add(":network-caching=${if (live) liveBufferMs() else 3000}")
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
        }.toTypedArray()
        Log.i("Reproducir${if (live) " (en vivo)" else ""}: $title — $mrl")
        ctl.execute {
            if (my != token) return@execute
            retryTask?.cancel(false)
            lastProgressAt = System.currentTimeMillis()
            runCatching { player.controls().setRate(1f) }
            rate = 1f
            val ok = runCatching { player.media().play(mrl, *options) }.getOrDefault(false)
            if (!ok) state = PlayState.Failed("No se pudo abrir el video")
        }
    }

    fun stop() {
        token++
        state = PlayState.Idle
        title = ""; url = ""
        val player = mp ?: return
        ctl.execute {
            retryTask?.cancel(false)
            runCatching { player.controls().stop() }
            frame = null
        }
    }

    fun togglePause() {
        val player = mp ?: return
        when (state) {
            PlayState.Playing -> ctl.execute { runCatching { player.controls().setPause(true) } }
            PlayState.Paused -> ctl.execute { runCatching { player.controls().setPause(false) } }
            PlayState.Ended, is PlayState.Failed -> if (url.isNotEmpty()) play(url, title, isLive)
            else -> {}
        }
    }

    fun changeVolume(delta: Int) = setVolumeTo(volume + delta)

    fun setVolumeTo(v: Int) {
        val nv = v.coerceIn(0, 200)
        volume = nv
        if (muted && nv > 0) applyMute(false)
        Settings.put("player.volume", nv)
        mp?.let { p -> ctl.execute { runCatching { p.audio().setVolume(nv) } } }
    }

    fun applyMute(m: Boolean) {
        muted = m
        mp?.let { p -> ctl.execute { runCatching { p.audio().setMute(m) } } }
    }

    fun setAspectMode(a: Aspect) { aspect = a; Settings.put("player.aspect", a.name) }

    fun selectAudio(id: Int) {
        mp?.let { p -> ctl.execute { runCatching { p.audio().setTrack(id) }; refreshTracks(p) } }
    }

    fun selectSubtitle(id: Int) {
        mp?.let { p -> ctl.execute { runCatching { p.subpictures().setTrack(id) }; refreshTracks(p) } }
    }

    fun seekTo(ms: Long) {
        if (isLive) return
        val target = ms.coerceIn(0, (lengthMs - 1000).coerceAtLeast(0))
        timeMs = target
        mp?.let { p -> ctl.execute { runCatching { p.controls().setTime(target) } } }
    }

    fun seekBy(deltaMs: Long) = seekTo(timeMs + deltaMs)

    fun setSpeed(r: Float) {
        rate = r
        mp?.let { p -> ctl.execute { runCatching { p.controls().setRate(r) } } }
    }

    /** Datos técnicos de lo que se está viendo (para el panel "Información"). */
    fun techInfo(): List<Pair<String, String>> {
        val p = mp ?: return listOf("Reproductor" to (unavailableReason ?: "no disponible"))
        val out = ArrayList<Pair<String, String>>()
        // La resolución sale del cuadro que entrega VLC: siempre disponible cuando hay imagen
        if (videoWidth > 0 && videoHeight > 0) out += "Resolución" to "${videoWidth}×${videoHeight}  ·  ${qualityLabel(videoWidth, videoHeight)}"
        try {
            val info = p.media().info()
            info.videoTracks().firstOrNull()?.let { v ->
                val fps = if (v.frameRateBase() > 0) v.frameRate().toFloat() / v.frameRateBase() else 0f
                if (videoWidth <= 0 && v.width() > 0) out += "Resolución" to "${v.width()}×${v.height()}  ·  ${qualityLabel(v.width(), v.height())}"
                val line = listOfNotNull(
                    codecName(v.codecDescription(), v.codecName()),
                    if (fps > 0) "${Math.round(fps)} fps" else null,
                ).joinToString("  ·  ")
                if (line.isNotEmpty()) out += "Video" to line
            }
            info.audioTracks().firstOrNull()?.let { a ->
                out += "Audio" to listOfNotNull(
                    codecName(a.codecDescription(), a.codecName()),
                    when (a.channels()) { 0 -> null; 1 -> "Mono"; 2 -> "Estéreo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${a.channels()} canales" },
                    if (a.rate() > 0) "${a.rate() / 1000} kHz" else null,
                    a.language()?.takeIf { it.isNotBlank() && it != "und" },
                ).joinToString("  ·  ")
            }
        } catch (e: Throwable) {
            Log.w("No se pudo leer la información técnica", e)
        }
        if (out.isEmpty()) out += "Estado" to "Espera a que el video empiece a reproducirse"
        val host = runCatching { java.net.URI(url).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }.getOrDefault("")
        if (host.isNotBlank()) out += "Servidor" to host
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (ext.isNotEmpty() && ext.length <= 5) out += "Formato" to ext.uppercase()
        out += "Búfer de red" to "${(if (isLive) liveBufferMs() else 3000) / 1000} s"
        out += "Reproductor" to "VLC ${VlcRuntime.version()} (${VlcRuntime.source})"
        return out
    }

    fun release() {
        token++
        watchdog?.cancel(false)
        val p = mp ?: return
        try {
            ctl.submit { runCatching { p.controls().stop() }; runCatching { p.release() } }.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        ctl.shutdownNow()
    }

    // ═══ Reconexión ═══

    private fun scheduleRetry(reason: String) {
        if (!isLive || url.isEmpty()) return
        val my = token
        if (retries >= MAX_RETRIES) {
            state = PlayState.Failed("Se perdió la señal del canal ($reason). Intenta de nuevo o prueba otro canal.")
            Log.w("En vivo sin señal tras $MAX_RETRIES intentos: $title")
            return
        }
        retries++
        state = PlayState.Reconnecting(retries, MAX_RETRIES)
        val wait = minOf(1L shl (retries - 1), 8L) // 1, 2, 4, 8, 8, 8 s
        Log.w("Reconectando $title (intento $retries, motivo: $reason) en $wait s")
        retryTask?.cancel(false)
        retryTask = ctl.schedule({
            val p = mp ?: return@schedule
            if (my != token) return@schedule
            lastProgressAt = System.currentTimeMillis()
            runCatching { p.controls().stop() }
            runCatching { p.media().play(url, *options) }
        }, wait, TimeUnit.SECONDS)
    }

    private fun checkStall() {
        if (!isLive) return
        val s = state
        val stuck = s is PlayState.Opening || s is PlayState.Buffering
        if (stuck && System.currentTimeMillis() - lastProgressAt > STALL_MS) {
            lastProgressAt = System.currentTimeMillis()
            scheduleRetry("se quedó cargando")
        }
    }

    private fun refreshTracks(p: MediaPlayer) {
        try {
            audioTracks = p.audio().trackDescriptions().filter { it.id() >= 0 }.map { Track(it.id(), clean(it.description(), "Pista ${it.id()}")) }
            audioTrack = p.audio().track()
            subTracks = p.subpictures().trackDescriptions().filter { it.id() >= 0 }.map { Track(it.id(), clean(it.description(), "Subtítulo ${it.id()}")) }
            subTrack = p.subpictures().track()
            p.media().info().videoTracks().firstOrNull()?.let { v ->
                val w = v.width(); val h = v.height()
                if (w > 0 && h > 0) {
                    val sar = if (v.sampleAspectRatio() > 0 && v.sampleAspectRatioBase() > 0)
                        v.sampleAspectRatio().toFloat() / v.sampleAspectRatioBase() else 1f
                    videoAspect = (w * sar) / h
                }
            }
        } catch (e: Throwable) {
            Log.w("No se pudieron leer las pistas", e)
        }
    }

    // ═══ Avisos de VLC (llegan en hilos de VLC: nunca se le responde desde aquí directamente) ═══

    private inner class Events : MediaPlayerEventAdapter() {
        override fun opening(mediaPlayer: MediaPlayer) { state = PlayState.Opening; lastProgressAt = System.currentTimeMillis() }
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            lastProgressAt = System.currentTimeMillis()
            if (state !is PlayState.Paused) state = if (newCache >= 100f) PlayState.Playing else PlayState.Buffering(newCache.toInt())
        }
        override fun playing(mediaPlayer: MediaPlayer) {
            state = PlayState.Playing
            lastProgressAt = System.currentTimeMillis()
            ctl.execute {
                refreshTracks(mediaPlayer)
                // Al reconectar se conserva el volumen y el silencio elegidos
                runCatching { mediaPlayer.audio().setVolume(volume); mediaPlayer.audio().setMute(muted) }
            }
            // Si sigue bien 15 s, los intentos de reconexión vuelven a cero
            val my = token
            ctl.schedule({ if (my == token && state == PlayState.Playing) retries = 0 }, 15, TimeUnit.SECONDS)
        }
        override fun paused(mediaPlayer: MediaPlayer) { state = PlayState.Paused }
        override fun finished(mediaPlayer: MediaPlayer) {
            // YouTube (tráileres): VLC primero convierte el enlace en el video real (un "subitem")
            // y termina; aquí se reproduce ese video. Si no hubo, YouTube no lo dejó.
            if (!isLive && url.contains("youtube.com/") && !subitemTried) {
                subitemTried = true
                val my = token
                ctl.execute {
                    val ok = runCatching { mediaPlayer.subitems().controls().play(); true }.getOrDefault(false)
                    if (!ok) state = PlayState.Failed("YouTube no dejó reproducir el tráiler")
                }
                // Si en 15 s no empezó, se da por fallido (y se ofrece el navegador)
                ctl.schedule({
                    if (my == token && state != PlayState.Playing && state != PlayState.Paused && state != PlayState.Ended)
                        state = PlayState.Failed("YouTube no dejó reproducir el tráiler")
                }, 15, TimeUnit.SECONDS)
                return
            }
            if (isLive) scheduleRetry("terminó la transmisión") else state = PlayState.Ended
        }
        override fun error(mediaPlayer: MediaPlayer) {
            Log.w("VLC informó un error con: $title")
            if (isLive) scheduleRetry("error de reproducción")
            else state = PlayState.Failed("No se pudo reproducir este video")
        }
        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            timeMs = newTime
            lastProgressAt = System.currentTimeMillis()
        }
        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { lengthMs = newLength }
        override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) { ctl.execute { refreshTracks(mediaPlayer) } }
        override fun elementaryStreamDeleted(mediaPlayer: MediaPlayer, type: TrackType, id: Int) { ctl.execute { refreshTracks(mediaPlayer) } }
        override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: TrackType, id: Int) { ctl.execute { refreshTracks(mediaPlayer) } }
    }

    // ═══ Cuadros de video ═══

    private inner class Formats : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            synchronized(bitmaps) {
                info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                bytes = ByteArray(sourceWidth * sourceHeight * 4)
                for (i in bitmaps.indices) bitmaps[i] = Bitmap().apply { allocPixels(info!!) }
                back = 0
            }
            videoWidth = sourceWidth; videoHeight = sourceHeight
            if (sourceHeight > 0) videoAspect = sourceWidth.toFloat() / sourceHeight
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
    }

    private inner class Renderer : RenderCallback {
        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
            synchronized(bitmaps) {
                val bmp = bitmaps[back] ?: return
                val buf = nativeBuffers[0]
                if (buf.capacity() < bytes.size) return
                buf.rewind()
                buf.get(bytes, 0, bytes.size)
                bmp.installPixels(bytes)
                frame = bmp.asComposeImageBitmap()
                back = (back + 1) % bitmaps.size
            }
            frameVersion++
        }
    }

    companion object {
        fun liveBufferMs(): Int = Settings.int("live.buffer", 2).coerceIn(1, 10) * 1000
        private const val MAX_RETRIES = 6
        private const val STALL_MS = 20_000L

        /** Calidad en palabras: 4K, Full HD, HD o SD. */
        fun qualityLabel(w: Int, h: Int): String {
            val p = minOf(w, h).let { if (w >= h) h else w }
            return when {
                w >= 3200 || p >= 1800 -> "4K"
                w >= 1800 || p >= 1000 -> "Full HD"
                w >= 1200 || p >= 700 -> "HD"
                else -> "SD"
            }
        }

        private fun clean(desc: String?, fallback: String): String =
            desc?.replace(Regex("^Track\\s*\\d+\\s*-?\\s*", RegexOption.IGNORE_CASE), "")?.trim()?.ifBlank { null } ?: fallback

        private fun codecName(desc: String?, name: String?): String? {
            val d = (desc ?: name ?: return null).lowercase()
            return when {
                "h264" in d || "avc" in d || "h.264" in d -> "H.264"
                "hevc" in d || "h265" in d || "h.265" in d -> "H.265"
                "mpeg-2" in d || "mpgv" in d || "mpeg2" in d -> "MPEG-2"
                "vp9" in d -> "VP9"
                "av1" in d -> "AV1"
                "e-ac3" in d || "eac3" in d -> "Dolby Digital+"
                "a52" in d || "ac3" in d || "ac-3" in d -> "Dolby Digital"
                "aac" in d || "mp4a" in d -> "AAC"
                "dts" in d -> "DTS"
                "mpga" in d || "mp3" in d || "mpeg audio" in d -> "MP3/MP2"
                "opus" in d -> "Opus"
                else -> desc ?: name
            }
        }
    }
}
