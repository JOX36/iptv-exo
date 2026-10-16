package com.jox3.iptvexo.phone

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.player.VodPlayback
import com.jox3.iptvexo.tv.player.trackLabel
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val clockFmt = SimpleDateFormat("h:mm a", Locale("es"))
internal fun clock(ms: Long): String = if (ms > 0) clockFmt.format(Date(ms)) else ""
internal fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
internal fun speedLabel(s: Float) = if (abs(s - 1f) < 0.01f) "Normal" else "${s.toString().trimEnd('0').trimEnd('.')}x"

/** Video de ExoPlayer (sin controles propios: los dibuja la app). */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(player: ExoPlayer?, modifier: Modifier = Modifier, resizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, keepOn: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { v ->
            if (v.player !== player) v.player = player
            v.resizeMode = resizeMode
            v.keepScreenOn = keepOn
        },
        modifier = modifier.background(Color.Black),
    )
}

/**
 * Capa de gestos de pantalla completa: un toque muestra/oculta los controles,
 * doble toque a los lados ±10 s (si [onSeek]), deslizar en vertical a la izquierda
 * cambia el brillo y a la derecha el volumen.
 */
@Composable
fun GestureLayer(onTap: () -> Unit, onSeek: ((Long) -> Unit)?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var hud by remember { mutableStateOf<Pair<String, Float>?>(null) }   // "Brillo"/"Volumen" + nivel
    var hudTick by remember { mutableIntStateOf(0) }
    var seekHud by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hudTick) { if (hudTick > 0) { delay(900); hud = null; seekHud = null } }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(onSeek) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { o ->
                        val s = onSeek ?: return@detectTapGestures
                        val forward = o.x > size.width / 2
                        s(if (forward) 10_000 else -10_000)
                        seekHud = if (forward) "+10 s" else "-10 s"; hudTick++
                    },
                )
            }
            .pointerInput(Unit) {
                var left = true
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        left = o.x < size.width / 2
                        level = if (left) {
                            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                        } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    },
                ) { _, dy ->
                    level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                    if (left) {
                        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.02f) } }
                        hud = "Brillo" to level
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                        hud = "Volumen" to level
                    }
                    hudTick++
                }
            },
    ) {
        val h = hud
        if (h != null) {
            Column(
                Modifier.align(if (h.first == "Brillo") Alignment.CenterStart else Alignment.CenterEnd).padding(28.dp)
                    .background(Color(0xCC070A0F), RoundedCornerShape(14.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(h.first, style = PType.Tiny, color = if (h.first == "Brillo") Jx.Gold else Jx.Accent)
                Box(Modifier.width(6.dp).height(110.dp).clip(RoundedCornerShape(3.dp)).background(Jx.Line), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(h.second).background(if (h.first == "Brillo") Jx.Gold else Jx.Accent))
                }
                Text("${(h.second * 100).toInt()}%", style = PType.Tiny, color = Jx.Text)
            }
        }
        seekHud?.let {
            Text(it, style = PType.H1, color = Jx.Text,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC070A0F), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
        }
    }
}

/** Barra de tiempo táctil (arrastrar para mover). */
@Composable
fun SeekSlider(vod: VodPlayback, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = vod.duration
    val pos = if (dragging) (dragValue * dur).toLong() else vod.position
    Column(modifier) {
        Slider(
            value = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { vod.player?.seekTo((dragValue * dur).toLong()); dragging = false },
            enabled = dur > 0,
            colors = SliderDefaults.colors(thumbColor = Jx.Accent, activeTrackColor = Jx.Accent, inactiveTrackColor = Color(0x55FFFFFF)),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(pos), style = PType.Tiny, color = Jx.TextSoft)
            Spacer(Modifier.weight(1f))
            if (dur > 0) {
                val left = ((dur - pos) / vod.speed).toLong()
                Text("-${fmtTime(dur - pos)} · termina ${clock(System.currentTimeMillis() + left)}", style = PType.Tiny, color = Jx.TextSoft)
            }
        }
    }
}

/** Controles sobre el video de película/serie (vertical u horizontal). */
@Composable
fun VodVideo(vod: VodPlayback, fullscreen: Boolean, onFullscreen: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(controls, touched, vod.playing) {
        if (controls && vod.playing) { delay(3500); controls = false }
    }
    val cur = vod.item
    Box(modifier.background(Color.Black)) {
        PlayerSurface(vod.player, Modifier.fillMaxSize(), vod.resizeMode, keepOn = vod.playing)
        GestureLayer(
            onTap = { controls = !controls; touched = System.currentTimeMillis() },
            onSeek = if (fullscreen) { ms -> vod.seekBy(ms) } else null,
        )
        vod.status?.let { st ->
            Text(st, style = PType.Small, color = Jx.Text, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xCC070A0F), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color.Transparent, Color(0xB3000000)))))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(if (fullscreen) R.drawable.ic_back else R.drawable.ic_chevron_down, if (fullscreen) "Salir de pantalla completa" else "Cerrar", onClick = onBack)
                if (fullscreen && cur != null) Column(Modifier.weight(1f)) {
                    Text(cur.subtitle.ifBlank { cur.title }, style = PType.Label, color = Jx.Text, maxLines = 1)
                    if (cur.subtitle.isNotBlank()) Text(cur.title, style = PType.Tiny, color = Jx.TextSoft, maxLines = 1)
                } else Spacer(Modifier.weight(1f))
                if (vod.quality.isNotEmpty()) Text(vod.quality, style = PType.Tiny, color = Jx.Accent, modifier = Modifier.padding(end = 8.dp))
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(R.drawable.ic_rew10, "Atrasar 10 segundos", bg = Color(0x80070A0F)) { vod.seekBy(-10_000); touched = System.currentTimeMillis() }
                RoundIcon(if (vod.playing) R.drawable.ic_pause else R.drawable.ic_play, if (vod.playing) "Pausa" else "Reproducir",
                    size = 62.dp, bg = Color(0xEEEAF4FA), tint = Jx.Bg, iconSize = 26.dp) { vod.togglePlay(); touched = System.currentTimeMillis() }
                RoundIcon(R.drawable.ic_ffw10, "Adelantar 10 segundos", bg = Color(0x80070A0F)) { vod.seekBy(10_000); touched = System.currentTimeMillis() }
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SeekSlider(vod, Modifier.weight(1f))
                RoundIcon(R.drawable.ic_fullscreen, if (fullscreen) "Salir de pantalla completa" else "Pantalla completa",
                    onClick = if (fullscreen) onBack else onFullscreen)
            }
        }
    }
}

/**
 * Película o serie en vertical: video arriba; debajo título, opciones rápidas,
 * siguiente episodio y la lista de episodios. Al girar el celular → pantalla completa.
 */
@Composable
fun VodScreen(vod: VodPlayback, lib: LibraryStore, onFullscreen: () -> Unit, onClose: () -> Unit) {
    val cur = vod.item ?: return
    var sheet by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Jx.Bg)) {
        VodVideo(vod, fullscreen = false, onFullscreen = onFullscreen, onBack = onClose,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (cur.subtitle == "Película") cur.title else cur.subtitle, style = PType.H1, color = Jx.Text)
                    Text(if (cur.subtitle == "Película") "Película" else cur.title, style = PType.Small, color = Jx.Muted, maxLines = 2)
                }
            }
            item {
                val isFav = lib.isFavorite(cur.fav.kind, cur.fav.id)
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Pill("Audio", false, icon = R.drawable.ic_audio) { sheet = "audio" } }
                    item { Pill("Subtítulos", false, icon = R.drawable.ic_subs) { sheet = "subs" } }
                    item { Pill(speedLabel(vod.speed).let { if (it == "Normal") "Velocidad" else it }, abs(vod.speed - 1f) > 0.01f) { sheet = "speed" } }
                    item { Pill(if (vod.sleepAt != 0L) "Apagado activo" else "Apagado", vod.sleepAt != 0L, icon = R.drawable.ic_stop) { sheet = "sleep" } }
                    item { Pill(if (isFav) "En favoritos" else "Favorito", isFav,
                        icon = if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star) { lib.toggleFavorite(cur.fav) } }
                }
            }
            val next = vod.queue.getOrNull(vod.index + 1)
            if (next != null) item {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Jx.Surface).clickable { vod.next() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RemoteImage(next.image, next.title, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text("SIGUIENTE", style = PType.Eyebrow, color = Jx.Accent)
                        Text(next.title, style = PType.Label, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    RoundIcon(R.drawable.ic_next, "Ver siguiente episodio", bg = Jx.Text, tint = Jx.Bg) { vod.next() }
                }
            }
            if (vod.queue.size > 1) {
                item { SectionTitle("Episodios", Modifier.padding(top = 6.dp, bottom = 8.dp)) }
                itemsIndexed(vod.queue, key = { _, it -> it.id }) { i, ep ->
                    val playing = i == vod.index
                    val (p, d) = lib.positionOf(ep.id)
                    Row(
                        Modifier.fillMaxWidth().clickable { if (!playing) vod.open(vod.queue, i) }.padding(horizontal = 18.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                            RemoteImage(ep.image, ep.title, Modifier.fillMaxSize())
                            val frac = if (playing && vod.duration > 0) vod.position.toFloat() / vod.duration else if (d > 0) p.toFloat() / d else 0f
                            if (frac > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(Jx.Accent))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(ep.title, style = PType.Label, color = if (playing) Jx.Accent else Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (playing) "Reproduciendo" else "", style = PType.Tiny, color = Jx.Muted)
                        }
                    }
                }
            }
        }
    }
    when (sheet) {
        "audio" -> TrackSheet(vod.player, vod.tracksVersion, C.TRACK_TYPE_AUDIO) { sheet = null }
        "subs" -> TrackSheet(vod.player, vod.tracksVersion, C.TRACK_TYPE_TEXT) { sheet = null }
        "speed" -> ChoiceSheet("Velocidad", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { s ->
            Triple(speedLabel(s), abs(s - vod.speed) < 0.01f) { vod.setPlaybackSpeed(s) } }) { sheet = null }
        "sleep" -> ChoiceSheet("Apagado automático", listOf(0, 15, 30, 45, 60, 90, 120, -1).map { m ->
            Triple(when (m) { 0 -> "Desactivado"; -1 -> "Al terminar este video"; else -> "En $m minutos" },
                (m == 0 && vod.sleepAt == 0L) || (m == -1 && vod.sleepAt < 0)) { vod.setSleep(m) } }) { sheet = null }
    }
}

/** Hoja inferior con opciones (la elegida lleva ✓). */
@Composable
fun ChoiceSheet(title: String, options: List<Triple<String, Boolean, () -> Unit>>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Jx.Surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(title, style = PType.H2, color = Jx.Text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            options.forEach { (label, sel, action) ->
                Row(
                    Modifier.fillMaxWidth().clickable { action(); onDismiss() }.padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = PType.Body, color = if (sel) Jx.Accent else Jx.Text, modifier = Modifier.weight(1f))
                    if (sel) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.tv_ic_check), "Elegido",
                        Modifier.size(18.dp), tint = Jx.Ok)
                }
            }
        }
    }
}

/** Audio o subtítulos de un reproductor. */
@OptIn(UnstableApi::class)
@Composable
fun TrackSheet(player: ExoPlayer?, tracksVersion: Int, type: Int, onDismiss: () -> Unit) {
    val p = player ?: return onDismiss()
    @Suppress("UNUSED_VARIABLE") val v = tracksVersion
    val options = buildList<Triple<String, Boolean, () -> Unit>> {
        if (type == C.TRACK_TYPE_TEXT) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            add(Triple("Desactivados", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        var n = 0
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                add(Triple(trackLabel(g.getTrackFormat(i), type, n), g.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                })
            }
        }
        if (isEmpty()) add(Triple(if (type == C.TRACK_TYPE_AUDIO) "Audio principal" else "Sin subtítulos", true) {})
    }
    ChoiceSheet(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulos", options, onDismiss)
}
