package com.jox3.iptvexo.tv.player

import android.content.Context
import com.jox3.iptvexo.PlayerSafety
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
    /** true mientras el video avanza: mantiene la pantalla encendida (sin protector de pantalla). */
    var playingNow by mutableStateOf(false); private set

    /** Lo pone la interfaz: guardar dónde estaba el foco antes de ir a pantalla completa. */
    var beforeFullscreen: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { load() }
    private var retries = 0
    // ── Formato del canal (traído de JOX3 Verifier) ──
    // Muchos servidores entregan HLS aunque la dirección diga .ts, o la cuenta solo
    // permite uno de los dos. Si un canal no abre por formato se prueban las otras
    // variantes y la que funcione se recuerda POR SERVIDOR: los demás canales de ese
    // servidor abren a la primera.
    private val formatPrefs = context.getSharedPreferences("jox3_player", Context.MODE_PRIVATE)
    /** 0 = formato preferido, 1 = la otra extensión, 2 = la dirección original leída a la fuerza con el otro tipo. */
    private var altStep = 0
    private var playingUrl = ""
    private var playingMime: String? = null
    private var startingMarked = false
    private var paused = false

    fun isPlaying(ch: Channel) = current?.id == ch.id && status == null

    /** Reproduce el canal (en la vista previa, o en pantalla completa si ya lo está). */
    fun play(ch: Channel, list: List<Channel>, groupName: String) {
        channels = list
        group = groupName
        if (current?.id == ch.id && exo?.playbackState != Player.STATE_IDLE) return
        current = ch
        retries = 0
        altStep = 0
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

    fun reload() { retries = 0; altStep = 0; load() }

    fun stop() {
        main.removeCallbacks(retryRunnable)
        exo?.stop()
        exo?.clearMediaItems()
        current = null
        fullscreen = false
        status = null
        playingNow = false
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

    private fun ensurePlayer(): ExoPlayer = exo ?: run {
        // Arranque seguro: si esto se cae, la próxima vez abre sin FFmpeg
        PlayerSafety.markStarting(context); startingMarked = true
        buildPlayer()
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(NetClient.player())))
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                // FFmpeg (AC-3, DTS…) salvo que un arranque anterior se haya caído por su culpa
                .setExtensionRendererMode(
                    if (PlayerSafety.ffmpegAllowed(context)) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                )
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

    private fun fmtKey(url: String) = "fmt_" + (try { java.net.URI(url).host } catch (e: Exception) { null } ?: "")

    private fun swapFormat(url: String): String? = when {
        url.endsWith(".ts", true) -> url.dropLast(3) + ".m3u8"
        url.endsWith(".m3u8", true) -> url.dropLast(5) + ".ts"
        else -> null
    }

    private fun mimeFor(url: String): String? = when {
        url.endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
        url.endsWith(".ts", true) -> MimeTypes.VIDEO_MP2T
        else -> null
    }

    /** Dirección y tipo según lo que ya funcionó en este servidor: "m3u8", "ts" o "ts-hls". */
    private fun preferred(url: String): Pair<String, String?> {
        val pref = formatPrefs.getString(fmtKey(url), null) ?: return url to mimeFor(url)
        val isTs = url.endsWith(".ts", true)
        return when {
            pref == "m3u8" && isTs -> (url.dropLast(3) + ".m3u8") to MimeTypes.APPLICATION_M3U8
            pref == "ts-hls" -> (if (isTs) url else url.dropLast(5) + ".ts") to MimeTypes.APPLICATION_M3U8
            pref == "ts" && url.endsWith(".m3u8", true) -> (url.dropLast(5) + ".ts") to MimeTypes.VIDEO_MP2T
            else -> url to mimeFor(url)
        }
    }

    private fun rememberFormat() {
        if (altStep == 0 || playingUrl.isEmpty()) return
        val fmt = when {
            playingUrl.endsWith(".m3u8", true) -> "m3u8"
            playingUrl.endsWith(".ts", true) && playingMime == MimeTypes.APPLICATION_M3U8 -> "ts-hls"
            playingUrl.endsWith(".ts", true) -> "ts"
            else -> return
        }
        formatPrefs.edit().putString(fmtKey(playingUrl), fmt).apply()
    }

    /**
     * true si se lanzó otro intento por un problema de formato. Orden: (1) la otra
     * extensión (.ts ↔ .m3u8); (2) la dirección original leída a la fuerza como HLS
     * (o TS): pasa cuando el servidor redirige el .ts a una lista .m3u8 de otra fuente.
     */
    private fun tryOtherFormat(error: PlaybackException): Boolean {
        val ch = current ?: return false
        if (altStep >= 2) return false
        val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        val formatProblem = error.errorCode in 3000..3999 || http == 403 || http == 404 || http == 406 || http == 415
        if (!formatProblem) return false
        val orig = repo.api.liveUrl(ch.id)
        val next: Pair<String, String?> = if (altStep == 0) {
            val alt = swapFormat(playingUrl) ?: return false
            alt to mimeFor(alt)
        } else {
            orig to (if (orig.endsWith(".ts", true)) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
        }
        altStep++
        status = "Probando otro formato… ($altStep/2)"
        start(next.first, next.second)
        return true
    }

    private fun load() {
        val ch = current ?: return
        main.removeCallbacks(retryRunnable)
        status = if (retries == 0) "Cargando…" else "Reconectando ($retries/$MAX_RETRIES)…"
        altStep = 0
        val (u, mime) = preferred(repo.api.liveUrl(ch.id))
        start(u, mime)
    }

    private fun start(url: String, mime: String?) {
        val ch = current ?: return
        playingUrl = url
        playingMime = mime
        quality = ""
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId(ch.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).setArtist(group).build())
            .apply { if (mime != null) setMimeType(mime) }
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
                Player.STATE_READY -> {
                    status = null; retries = 0
                    rememberFormat()
                    if (startingMarked) { startingMarked = false; PlayerSafety.startedOk(context) }
                }
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
            // Primero otro formato (si el error es de formato); si no, reintentos con espera creciente
            if (tryOtherFormat(error)) return
            scheduleRetry()
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

        override fun onIsPlayingChanged(isPlaying: Boolean) { playingNow = isPlaying }
    }

    companion object {
        private const val MAX_RETRIES = 5
    }
}
