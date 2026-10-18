package com.jox3.iptvexo.phone

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.ui.Jx
import kotlinx.coroutines.delay

/** Lo que se manda al Chromecast. */
data class CastMedia(val url: String, val title: String, val image: String, val live: Boolean, val startMs: Long = 0L)

/**
 * Chromecast para la interfaz del celular (el mismo SDK de Google Cast que usaba el
 * reproductor anterior). El selector de dispositivos es propio (una hoja de la app),
 * así combina con el resto y no necesita las pantallas de AppCompat.
 *
 * Mientras se transmite, el video del celular queda en pausa y en silencio; al cortar,
 * sigue en el celular donde iba (en películas y series, en el minuto del Chromecast).
 *
 * Si el equipo no tiene Servicios de Google Play, [ready] queda en false y el botón
 * no aparece: la app funciona igual.
 */
class PhoneCast(context: Context) {
    private val app = context.applicationContext
    private val castContext: CastContext? = try { CastContext.getSharedInstance(app) } catch (e: Exception) { null }
    private val router: MediaRouter? = if (castContext != null) runCatching { MediaRouter.getInstance(app) }.getOrNull() else null
    private val selector: MediaRouteSelector? = castContext?.mergedSelector

    val ready: Boolean get() = castContext != null && router != null && selector != null
    /** Hay al menos un Chromecast en la red (o ya hay uno conectado). */
    var available by mutableStateOf(false); private set
    /** Nombre del Chromecast conectado, o null. */
    var connectedTo by mutableStateOf<String?>(null); private set
    var connecting by mutableStateOf(false); private set
    var remotePlaying by mutableStateOf(false); private set
    var lastError by mutableStateOf<String?>(null)
    /** Dispositivos encontrados mientras la hoja de selección está abierta. */
    val routes = mutableStateListOf<MediaRouter.RouteInfo>()

    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    /** Lo último que se mandó (para no recargar lo mismo). */
    private var loadedUrl: String? = null
    /** Lo llama la interfaz al conectarse / desconectarse. */
    var onDisconnected: (positionMs: Long) -> Unit = {}

    private val clientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { remotePlaying = client?.isPlaying == true }
    }

    private val stateListener = CastStateListener { state ->
        available = state != CastState.NO_DEVICES_AVAILABLE
        connecting = state == CastState.CONNECTING
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = connected(s)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = connected(s)
        override fun onSessionEnded(s: CastSession, error: Int) = disconnected()
        override fun onSessionSuspended(s: CastSession, reason: Int) = disconnected()
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            connecting = false; lastError = "No se pudo conectar al Chromecast"
        }
        override fun onSessionStarting(s: CastSession) { connecting = true }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
    }

    fun start() {
        val cc = castContext ?: return
        cc.addCastStateListener(stateListener)
        stateListener.onCastStateChanged(cc.castState)
        cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        cc.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { connected(it) }
    }

    fun release() {
        val cc = castContext ?: return
        stopScan()
        cc.removeCastStateListener(stateListener)
        cc.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        client?.unregisterCallback(clientCallback)
    }

    /** Búsqueda activa de dispositivos (solo mientras la hoja está abierta: gasta batería). */
    fun startScan() {
        val r = router ?: return; val sel = selector ?: return
        r.addCallback(sel, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refreshRoutes()
    }

    fun stopScan() { router?.removeCallback(routerCallback) }

    private fun refreshRoutes() {
        val r = router ?: return; val sel = selector ?: return
        routes.clear()
        routes.addAll(r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) })
    }

    /** Elegir un dispositivo: el SDK de Cast abre la sesión solo. */
    fun select(route: MediaRouter.RouteInfo) {
        lastError = null
        connecting = true
        runCatching { router?.selectRoute(route) }.onFailure { connecting = false; lastError = "No se pudo conectar" }
    }

    /** Cortar la transmisión (cierra la app en el Chromecast). */
    fun disconnect() { runCatching { castContext?.sessionManager?.endCurrentSession(true) } }

    private fun connected(s: CastSession) {
        session = s
        connecting = false
        connectedTo = runCatching { s.castDevice?.friendlyName }.getOrNull() ?: "Chromecast"
        client?.unregisterCallback(clientCallback)
        client = s.remoteMediaClient?.also { it.registerCallback(clientCallback) }
        loadedUrl = null
    }

    private fun disconnected() {
        if (connectedTo == null) return
        val pos = runCatching { client?.approximateStreamPosition ?: 0L }.getOrDefault(0L)
        client?.unregisterCallback(clientCallback)
        client = null; session = null
        connectedTo = null; connecting = false; remotePlaying = false
        loadedUrl = null
        onDisconnected(pos)
    }

    /**
     * Manda el video al Chromecast. El receptor de Google pide la dirección directo
     * desde su red: funciona con M3U8 (HLS) y MP4; algunos MKV o TS no los abre.
     */
    fun load(m: CastMedia) {
        val c = client ?: return
        if (loadedUrl == m.url) return
        loadedUrl = m.url
        val type = when {
            m.url.contains(".m3u8", true) -> "application/x-mpegURL"
            m.url.contains(".mp4", true) -> "video/mp4"
            m.url.contains(".mkv", true) -> "video/x-matroska"
            m.url.contains(".ts", true) -> "video/mp2t"
            m.url.contains(".webm", true) -> "video/webm"
            else -> "video/mp4"
        }
        val meta = MediaMetadata(if (m.live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, m.title.ifBlank { "JOX3 TV" })
            if (m.image.startsWith("http")) runCatching { addImage(WebImage(Uri.parse(m.image))) }
        }
        val info = MediaInfo.Builder(m.url)
            .setStreamType(if (m.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(type)
            .setMetadata(meta)
            .build()
        runCatching {
            c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
                .setCurrentTime(if (m.live) 0L else m.startMs).build())
                .setResultCallback { r -> if (!r.status.isSuccess) lastError = "El Chromecast no pudo abrir este video" }
        }
    }

    fun togglePlay() { client?.let { if (it.isPlaying) it.pause() else it.play() } }

    fun seekBy(ms: Long) {
        val c = client ?: return
        val dur = c.streamDuration
        var p = (c.approximateStreamPosition + ms).coerceAtLeast(0)
        if (dur > 0) p = p.coerceAtMost(dur)
        c.seek(MediaSeekOptions.Builder().setPosition(p).build())
    }

    fun position(): Long = runCatching { client?.approximateStreamPosition ?: 0L }.getOrDefault(0L)
    fun duration(): Long = runCatching { client?.streamDuration ?: 0L }.getOrDefault(0L)
}

/** El Chromecast de la sesión (null fuera de la interfaz del celular). */
val LocalCast = staticCompositionLocalOf<PhoneCast?> { null }

/** Botón de Chromecast: solo aparece si hay dispositivos en la red o ya se transmite. */
@Composable
fun CastButton(onClick: () -> Unit) {
    val cast = LocalCast.current ?: return
    if (!cast.ready || (!cast.available && cast.connectedTo == null)) return
    RoundIcon(if (cast.connectedTo != null) R.drawable.ic_cast_connected else R.drawable.ic_cast,
        if (cast.connectedTo != null) "Chromecast conectado" else "Transmitir a Chromecast",
        tint = if (cast.connectedTo != null) Jx.Accent else Jx.Text, onClick = onClick)
}

/** Hoja para elegir el Chromecast o cortar la transmisión. */
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val cast = LocalCast.current ?: return
    DisposableEffect(Unit) { cast.startScan(); onDispose { cast.stopScan() } }
    PlayerSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val name = cast.connectedTo
            if (name != null) {
                Text("Transmitiendo a $name", style = PType.H2, color = Jx.Text)
                Text("El video se ve en el TV; el celular queda como control.", style = PType.Small, color = Jx.Muted)
                PButton("Dejar de transmitir", Modifier.fillMaxWidth(), icon = R.drawable.ic_stop, primary = false) {
                    cast.disconnect(); onDismiss()
                }
            } else {
                Text("Transmitir a", style = PType.H2, color = Jx.Text)
                if (cast.connecting) Text("Conectando…", style = PType.Small, color = Jx.Accent)
                cast.lastError?.let { Text(it, style = PType.Small, color = Jx.Live) }
                if (cast.routes.isEmpty()) {
                    Text("Buscando dispositivos en tu WiFi…", style = PType.Body, color = Jx.TextSoft)
                    Text("El Chromecast o TV con Chromecast debe estar en la misma red WiFi que el celular.",
                        style = PType.Small, color = Jx.Faint)
                }
                cast.routes.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Jx.Surface)
                            .clickable { cast.select(r) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_cast), null, Modifier.size(22.dp), tint = Jx.Accent)
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = PType.Label, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            r.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = PType.Tiny, color = Jx.Muted, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
    // Al conectarse, la hoja se cierra sola
    val name = cast.connectedTo
    androidx.compose.runtime.LaunchedEffect(name) { if (name != null && cast.routes.isNotEmpty()) { delay(600); onDismiss() } }
}

/**
 * Lo que se ve en lugar del video mientras se transmite: a dónde, qué, y los
 * controles del Chromecast (pausa, ±10 s en películas y series, cortar).
 */
@Composable
fun CastingOverlay(title: String, live: Boolean, onOpenSheet: () -> Unit, modifier: Modifier = Modifier) {
    val cast = LocalCast.current ?: return
    val name = cast.connectedTo ?: return
    val tick by produceState(0L) { while (true) { value = cast.position(); delay(1000) } }
    Box(modifier.fillMaxSize().background(Color(0xFF05080D))) {
        Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_cast_connected), null, Modifier.size(34.dp), tint = Jx.Accent)
            Text("Transmitiendo a $name", style = PType.Label, color = Jx.Text)
            Text(title, style = PType.Small, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!live) {
                val dur = cast.duration()
                if (dur > 0) Text("${fmt(tick)} / ${fmt(dur)}", style = PType.Tiny, color = Jx.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!live) RoundIcon(R.drawable.ic_rew10, "Atrasar 10 segundos", bg = Color(0x33FFFFFF)) { cast.seekBy(-10_000) }
                RoundIcon(if (cast.remotePlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    if (cast.remotePlaying) "Pausa" else "Reproducir", size = 52.dp, bg = Color(0xEEEAF4FA), tint = Jx.Bg, iconSize = 22.dp) {
                    cast.togglePlay()
                }
                if (!live) RoundIcon(R.drawable.ic_ffw10, "Adelantar 10 segundos", bg = Color(0x33FFFFFF)) { cast.seekBy(10_000) }
                RoundIcon(R.drawable.ic_stop, "Dejar de transmitir", bg = Color(0x33FFFFFF)) { cast.disconnect() }
            }
            cast.lastError?.let { Text(it, style = PType.Tiny, color = Jx.Live) }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { CastButton(onOpenSheet) }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
