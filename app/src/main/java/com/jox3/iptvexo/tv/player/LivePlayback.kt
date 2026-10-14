package com.jox3.iptvexo.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.jox3.iptvexo.NetClient
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo

/** Acceso al reproductor en vivo desde cualquier pantalla. */
val LocalLive = staticCompositionLocalOf<LivePlayback> { error("LivePlayback no disponible") }

/**
 * Reproductor de TV en vivo de la interfaz TV.
 *
 * Es UNO solo para la vista previa y la pantalla completa: el video que se ve
 * en pequeño simplemente se agranda, sin volver a conectar ni cargar el canal.
 * - OK sobre un canal → se reproduce en la vista previa.
 * - OK otra vez sobre el mismo canal → pantalla completa al instante.
 */
@OptIn(UnstableApi::class)
class LivePlayback(private val context: Context, private val repo: TvRepo) {

    private var exo: ExoPlayer? = null
    val player: ExoPlayer? get() = exo

    var current by mutableStateOf<Channel?>(null); private set
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var group by mutableStateOf(""); private set
    var fullscreen by mutableStateOf(false); private set
    /** Dónde está la ventanita de vista previa (la pone la pantalla En vivo). */
    var previewBounds by mutableStateOf<Rect?>(null)
    /** null = reproduciendo; texto = cargando / reconectando / error. */
    var status by mutableStateOf<String?>(null); private set
    var quality by mutableStateOf(""); private set
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    /** Sube cuando cambian las pistas de audio/subtítulos disponibles. */
    var tracksVersion by mutableIntStateOf(0); private set

    /** Lo pone la interfaz: guardar dónde estaba el foco antes de ir a pantalla completa. */
    var beforeFullscreen: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { load() }
    private var retries = 0
    /** Algunos servidores fallan con .m3u8 y funcionan con .ts (o al revés). */
    private var altFormat = false
    private var paused = false

    fun isPlaying(ch: Channel) = current?.id == ch.id && status == null

    /** Reproduce el canal (en la vista previa, o en pantalla completa si ya lo está). */
    fun play(ch: Channel, list: List<Channel>, groupName: String) {
        channels = list
        group = groupName
        if (current?.id == ch.id && exo?.playbackState != Player.STATE_IDLE) return
        current = ch
        retries = 0
        altFormat = false
        repo.library.addRecentChannel(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
        load()
    }

    /** Pasa a pantalla completa con el mismo video (sin recargar). */
    fun enterFullscreen() {
        if (current == null) return
        beforeFullscreen()
        fullscreen = true
    }

    /** Desde Inicio, Favoritos o Buscar: directo a pantalla completa. */
    fun playFullscreen(list: List<Channel>, index: Int, groupName: String) {
        val ch = list.getOrNull(index) ?: return
        play(ch, list, groupName)
        enterFullscreen()
    }

    fun exitFullscreen() { fullscreen = false }

    /** Canal siguiente (+1) o anterior (-1) de la lista actual. */
    fun zap(dir: Int) {
        val list = channels
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        play(list[(i + dir).mod(list.size)], list, group)
    }

    fun playIndex(i: Int) { channels.getOrNull(i)?.let { play(it, channels, group) } }

    fun reload() { retries = 0; load() }

    fun stop() {
        main.removeCallbacks(retryRunnable)
        exo?.stop()
        exo?.clearMediaItems()
        current = null
        fullscreen = false
        status = null
        quality = ""
    }

    /** La app pasa a segundo plano (o se abre otra pantalla): se corta la conexión. */
    fun onPause() {
        paused = true
        main.removeCallbacks(retryRunnable)
        exo?.stop()
    }

    fun onResume() {
        if (!paused) return
        paused = false
        if (current != null) load()
    }

    fun release() {
        stop()
        exo?.release()
        exo = null
    }

    // ── Interno ──

    private fun ensurePlayer(): ExoPlayer = exo ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(NetClient.player())))
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
        )
        // Arranque rápido (1,5 s) y colchón amplio contra cortes
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_500, 3_000).build())
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { p -> p.addListener(listener); exo = p }

    private fun url(ch: Channel): String {
        val base = repo.api.liveUrl(ch.id)
        return if (altFormat) base.removeSuffix(".m3u8") + ".ts" else base
    }

    private fun load() {
        val ch = current ?: return
        main.removeCallbacks(retryRunnable)
        status = if (retries == 0) "Cargando…" else "Reconectando ($retries/$MAX_RETRIES)…"
        quality = ""
        val item = MediaItem.Builder()
            .setUri(url(ch))
            .setMediaId(ch.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).setArtist(group).build())
            .apply { if (altFormat) setMimeType(MimeTypes.VIDEO_MP2T) }
            .build()
        val p = ensurePlayer()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = !paused
    }

    private fun scheduleRetry() {
        if (retries >= MAX_RETRIES) {
            status = "No se pudo conectar con el canal. Pulsa Recargar para intentarlo de nuevo."
            return
        }
        retries++
        status = "Reconectando ($retries/$MAX_RETRIES)…"
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, minOf(1500L * retries, 8000L))
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> { status = null; retries = 0 }
                Player.STATE_BUFFERING -> if (status == null) status = "Cargando…"
                // Un canal en vivo no "termina": el servidor cortó la transmisión
                Player.STATE_ENDED -> scheduleRetry()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = exo ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Se quedó atrás del directo: volver al punto en vivo sin reconectar
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Probar el otro formato (.m3u8 ↔ .ts) y, si sigue fallando, esperar cada vez más
            altFormat = !altFormat
            if (retries == 0) { retries = 1; load() } else scheduleRetry()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
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

    companion object {
        private const val MAX_RETRIES = 5
    }
}
