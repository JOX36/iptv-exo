package com.jox3.iptvexo.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.jox3.iptvexo.NetClient
import com.jox3.iptvexo.PlayerSafety
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.data.SavedItem

/** Una película o un episodio listo para reproducir. */
data class VodItem(
    val url: String,
    val title: String,
    val subtitle: String,
    val image: String,
    /** Clave del progreso guardado (id de la película o del episodio). */
    val id: String,
    /** Lo que se guarda en "Continuar viendo". */
    val resume: SavedItem,
    /** Lo que se marca como favorito (la película, o la serie completa). */
    val fav: SavedItem,
    /** Temporada (episodios; 0 = película). */
    val season: Int = 0,
    /** Sinopsis y datos cortos (año · género · duración), si se conocen al abrir. */
    val plot: String = "",
    val meta: String = "",
)

/**
 * Reproductor de películas y series de la interfaz TV (Compose).
 *
 * Retoma donde se dejó, guarda el progreso (lo usa "Continuar viendo"), pasa solo
 * al siguiente episodio y trae lo del reproductor de JOX3 Verifier: velocidad,
 * apagado automático e información técnica.
 */
@OptIn(UnstableApi::class)
class VodPlayback(private val context: Context, private val lib: LibraryStore) {

    private var exo: ExoPlayer? = null
    val player: ExoPlayer? get() = exo
    private val main = Handler(Looper.getMainLooper())
    private val progress = context.getSharedPreferences("vod_progress", Context.MODE_PRIVATE)

    var queue by mutableStateOf<List<VodItem>>(emptyList()); private set
    var index by mutableIntStateOf(0); private set
    val item: VodItem? get() = queue.getOrNull(index)
    val open: Boolean get() = item != null
    val hasNext: Boolean get() = index + 1 < queue.size

    /** null = reproduciendo; texto = cargando / error. */
    var status by mutableStateOf<String?>(null); private set
    var failed by mutableStateOf(false); private set
    var position by mutableLongStateOf(0L); private set
    var duration by mutableLongStateOf(0L); private set
    var buffered by mutableLongStateOf(0L); private set
    var playing by mutableStateOf(false); private set
    var ended by mutableStateOf(false); private set
    var quality by mutableStateOf(""); private set
    var speed by mutableFloatStateOf(1f); private set
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    var tracksVersion by mutableIntStateOf(0); private set
    /** Hora (ms) del apagado automático; 0 = desactivado; -1 = al terminar este video. */
    var sleepAt by mutableLongStateOf(0L); private set
    /** El apagado automático se cumplió: la reproducción quedó en pausa. */
    var sleepFired by mutableStateOf(false); private set
    /** Reproductor pequeño en una esquina: sigue sonando mientras se elige otra cosa. */
    var mini by mutableStateOf(false); private set
    /** Pantalla completa (abierto y no minimizado). */
    val full: Boolean get() = open && !mini

    fun minimize() { if (open) mini = true }
    fun maximize() { if (open) mini = false }

    /** Sinopsis y datos que llegan después de abrir (p. ej. la ficha de la película). */
    val extras = androidx.compose.runtime.mutableStateMapOf<String, Pair<String, String>>()
    fun setExtras(id: String, plot: String, meta: String) { extras[id] = plot to meta }
    fun plotOf(it: VodItem): String = extras[it.id]?.first?.ifBlank { null } ?: it.plot
    fun metaOf(it: VodItem): String = extras[it.id]?.second?.ifBlank { null } ?: it.meta

    /** Lo llama la interfaz antes de abrir (detener el canal en vivo, recordar el foco). */
    var beforeOpen: () -> Unit = {}

    private var startingMarked = false
    private var retries = 0
    private var pausedByApp = false

    fun open(items: List<VodItem>, start: Int) {
        if (items.isEmpty()) return
        beforeOpen()
        saveProgress()
        mini = false
        queue = items
        index = start.coerceIn(0, items.lastIndex)
        load(resume = true)
    }

    fun close() {
        if (startingMarked) { startingMarked = false; PlayerSafety.startedOk(context) }
        saveProgress()
        main.removeCallbacks(ticker)
        exo?.stop()
        exo?.clearMediaItems()
        queue = emptyList()
        mini = false
        status = null; failed = false; ended = false; playing = false
        sleepAt = 0L; sleepFired = false
        lib.refreshProgress()
    }

    fun next() {
        if (!hasNext) return
        saveProgress()
        index++
        load(resume = true)
    }

    fun togglePlay() {
        val p = exo ?: return
        if (ended) { p.seekTo(0); ended = false; p.play(); return }
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBy(ms: Long) {
        val p = exo ?: return
        val d = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val to = (p.currentPosition + ms).coerceIn(0, (d - 1000).coerceAtLeast(0))
        p.seekTo(to)
        position = to
        ended = false
    }

    fun retry() {
        retries = 0
        val p = exo ?: return load(resume = true)
        failed = false
        status = "Cargando…"
        p.prepare()
        p.play()
    }

    fun setPlaybackSpeed(s: Float) {
        speed = s
        exo?.setPlaybackSpeed(s)
    }

    /** minutes: 0 desactiva, -1 = al terminar este video. */
    fun setSleep(minutes: Int) {
        sleepFired = false
        sleepAt = when {
            minutes == 0 -> 0L
            minutes < 0 -> -1L
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
    }

    fun dismissSleep(resume: Boolean) {
        sleepFired = false
        if (resume) exo?.play()
    }

    fun onPause() {
        saveProgress()
        val p = exo ?: return
        if (p.isPlaying) { pausedByApp = true; p.pause() }
    }

    fun onResume() {
        if (pausedByApp) { pausedByApp = false; exo?.play() }
    }

    fun release() {
        close()
        exo?.release()
        exo = null
    }

    // ── Interno ──

    private fun load(resume: Boolean) {
        val it = item ?: return
        lib.rememberPlayback(it.resume)
        retries = 0
        failed = false; ended = false; quality = ""
        status = "Cargando…"
        position = 0; duration = 0
        val p = ensurePlayer()
        val media = MediaItem.Builder()
            .setUri(it.url)
            .setMediaId(it.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.subtitle).build())
            .build()
        // Retomar donde se dejó (si no estaba casi terminado)
        val pos = progress.getLong("pos_${it.id}", 0)
        val dur = progress.getLong("dur_${it.id}", 0)
        val start = if (resume && pos > 5_000 && (dur <= 0 || pos.toFloat() / dur < 0.95f)) pos else 0L
        p.setMediaItem(media, start)
        p.setPlaybackSpeed(speed)
        p.prepare()
        p.playWhenReady = true
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun ensurePlayer(): ExoPlayer = exo ?: run {
        PlayerSafety.markStarting(context); startingMarked = true
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(NetClient.player())))
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(
                        if (PlayerSafety.ffmpegAllowed(context)) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    )
                    .setEnableDecoderFallback(true)
            )
            // Películas: más colchón que en vivo (no hace falta ir pegado al directo)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_500, 3_000).build())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { p -> p.addListener(listener); exo = p }
    }

    /** Guarda la posición; si ya casi terminó, la borra (sale de "Continuar viendo"). */
    private fun saveProgress() {
        val it = item ?: return
        val p = exo ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur <= 0 || pos <= 0) return
        val e = progress.edit()
        if (pos.toFloat() / dur > 0.95f) e.remove("pos_${it.id}").remove("dur_${it.id}")
        else e.putLong("pos_${it.id}", pos).putLong("dur_${it.id}", dur)
        e.apply()
    }

    private var lastSave = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val p = exo ?: return
            if (item == null) return
            position = p.currentPosition.coerceAtLeast(0)
            duration = p.duration.takeIf { it > 0 } ?: 0
            buffered = p.bufferedPosition.coerceAtLeast(0)
            val now = System.currentTimeMillis()
            if (now - lastSave > 10_000) { lastSave = now; saveProgress() }
            if (sleepAt > 0 && now >= sleepAt) { sleepAt = 0L; sleepFired = true; p.pause(); saveProgress() }
            main.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    status = null; retries = 0; failed = false
                    if (startingMarked) { startingMarked = false; PlayerSafety.startedOk(context) }
                }
                Player.STATE_BUFFERING -> if (status == null && !failed) status = "Cargando…"
                Player.STATE_ENDED -> {
                    val it = item ?: return
                    progress.edit().remove("pos_${it.id}").remove("dur_${it.id}").apply()
                    ended = true
                    status = null
                    if (sleepAt == -1L) { sleepAt = 0L; sleepFired = true }
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }

        override fun onPlayerError(error: PlaybackException) {
            if (startingMarked) { startingMarked = false; PlayerSafety.startedOk(context) }
            val p = exo ?: return
            // Cortes de red: reintentar desde el mismo punto, con espera creciente
            if (retries < 3) {
                retries++
                status = "Reconectando ($retries/3)…"
                val at = p.currentPosition
                main.postDelayed({ if (item != null) { p.seekTo(at); p.prepare(); p.play() } }, 1500L * retries)
                return
            }
            failed = true
            status = "No se pudo reproducir. " + when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "El formato del video no es compatible con este equipo."
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "El servidor rechazó el video."
                else -> "Revisa la conexión e inténtalo de nuevo."
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            val h = videoSize.height
            quality = when {
                h >= 2000 -> "4K"
                h >= 1000 -> "1080p"
                h >= 700 -> "720p"
                h > 0 -> "${h}p"
                else -> ""
            }
        }

        override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }
    }
}
