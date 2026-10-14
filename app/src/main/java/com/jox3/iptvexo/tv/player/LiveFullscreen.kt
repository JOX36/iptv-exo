package com.jox3.iptvexo.tv.player

import android.view.KeyEvent as AndroidKey
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.EpgItem
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.ActionButton
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.ThinProgress
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val timeFmt = SimpleDateFormat("h:mm a", Locale("es"))
private fun hhmm(ms: Long) = if (ms > 0) timeFmt.format(Date(ms)) else ""

/**
 * Capa de video: un solo PlayerView que se dibuja en la ventanita de vista
 * previa o a pantalla completa. Cambiar de tamaño no recarga el canal.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoLayer(live: LivePlayback, modifier: Modifier = Modifier) {
    val bounds = live.previewBounds
    val full = live.fullscreen
    if (live.current == null || (!full && bounds == null)) return
    val density = LocalDensity.current
    val box = if (full || bounds == null) modifier.fillMaxSize() else with(density) {
        modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(bounds.width.toDp(), bounds.height.toDp())
            .clip(RoundedCornerShape(12.dp))
    }
    Box(box.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)   // al cambiar de canal no parpadea en negro
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { v ->
                if (v.player !== live.player) v.player = live.player
                v.resizeMode = if (full) live.resizeMode else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize(),
        )
        val st = live.status
        if (st != null) {
            Box(Modifier.align(Alignment.Center).background(Color(0xCC070A0F), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(st, style = if (full) Type.Label else Type.Small, color = Jx.Text)
            }
        }
    }
}

private enum class Panel { None, Channels, Audio, Subtitles }

/**
 * Controles a pantalla completa, al estilo del reproductor de JOX3 Verifier:
 * arriba el canal y la guía (AHORA / DESPUÉS con avance), abajo los botones.
 *
 * Con los controles ocultos: ▲▼ cambian de canal, ◀▶ abren la lista de canales,
 * OK muestra los controles, los números van directo a un canal y Atrás vuelve
 * a la vista previa (el video sigue sin cortarse).
 */
@Composable
fun LiveFullscreen(live: LivePlayback, repo: TvRepo, onExit: () -> Unit) {
    val ch = live.current ?: return
    var bars by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.None) }
    var rootFocused by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootReq = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    val lib = repo.library
    val index = live.channels.indexOfFirst { it.id == ch.id }

    // Número de canal escrito con el control
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = digits.toIntOrNull() ?: 0
        digits = ""
        if (n in 1..live.channels.size) live.playIndex(n - 1)
    }

    // Al cambiar de canal se muestra la información unos segundos
    LaunchedEffect(ch.id) { bars = true; activity = System.currentTimeMillis() }
    // Los controles se ocultan solos tras 5 s sin tocar el control
    LaunchedEffect(bars, activity, panel) {
        if (!bars || panel != Panel.None) return@LaunchedEffect
        delay(5000)
        bars = false
        runCatching { rootReq.requestFocus() }
    }
    LaunchedEffect(panel) { if (panel == Panel.None) runCatching { rootReq.requestFocus() } }

    BackHandler {
        when {
            panel != Panel.None -> panel = Panel.None
            bars && !rootFocused -> { bars = false; runCatching { rootReq.requestFocus() } }
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { activity = System.currentTimeMillis(); false }
            .focusRequester(rootReq)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || !rootFocused || panel != Panel.None) return@onKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_CHANNEL_UP -> { live.zap(+1); true }
                    AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_CHANNEL_DOWN -> { live.zap(-1); true }
                    AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT,
                    AndroidKey.KEYCODE_GUIDE, AndroidKey.KEYCODE_TV_INPUT -> { panel = Panel.Channels; true }
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER,
                    AndroidKey.KEYCODE_INFO, AndroidKey.KEYCODE_MENU -> {
                        bars = true
                        runCatching { firstButton.requestFocus() }
                        true
                    }
                    AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKey.KEYCODE_MEDIA_PAUSE, AndroidKey.KEYCODE_MEDIA_PLAY -> {
                        live.player?.let { if (it.isPlaying) it.pause() else it.play() }; true
                    }
                    in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> {
                        if (digits.length < 4) digits += (code - AndroidKey.KEYCODE_0).toString(); true
                    }
                    else -> false
                }
            }
    ) {
        if (bars) {
            // ── Arriba: canal + guía ──
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
                    .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 48.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (index >= 0) "${index + 1}" else "", style = Type.H1, color = Jx.Accent)
                    RemoteImage(ch.logo, ch.name, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), scale = ContentScale.Fit)
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, style = Type.H2, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (live.group.isNotBlank()) Text(live.group, style = Type.Small, color = Jx.Muted, maxLines = 1)
                    }
                    Text("EN VIVO", style = Type.Eyebrow, color = Color.White,
                        modifier = Modifier.background(Jx.Live, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    if (live.quality.isNotEmpty()) {
                        Text(live.quality, style = Type.Small, color = Jx.Accent,
                            modifier = Modifier.background(Color(0x33081626), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Clock()
                }
                EpgLines(ch, repo)
            }
        }

        // ── Abajo: botones ──
        if (bars) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val isFav = lib.isFavorite("live", ch.id)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Lista de canales", { panel = Panel.Channels }, Modifier.focusRequester(firstButton), icon = R.drawable.tv_ic_list)
                    ActionButton("Audio", { panel = Panel.Audio }, icon = R.drawable.ic_audio)
                    ActionButton("Subtítulos", { panel = Panel.Subtitles }, icon = R.drawable.ic_subs)
                    ActionButton(resizeLabel(live.resizeMode), { live.resizeMode = nextResize(live.resizeMode) }, icon = R.drawable.ic_resize)
                    ActionButton(if (isFav) "En favoritos" else "Favorito", {
                        lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
                    }, icon = if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star)
                    ActionButton("Recargar", { live.reload() }, icon = R.drawable.tv_ic_restart)
                }
                Text("▲▼ cambiar canal   ◀▶ lista de canales   0-9 ir a un canal   Atrás: volver a la guía",
                    style = Type.Small, color = Jx.Muted)
            }
        }

        if (digits.isNotEmpty()) {
            Text(digits, style = Type.Hero, color = Jx.Text,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 48.dp)
                    .background(Color(0xCC070A0F), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 6.dp))
        }

        when (panel) {
            Panel.Channels -> ChannelPanel(live, repo, Modifier.align(Alignment.CenterStart)) { panel = Panel.None }
            Panel.Audio -> TrackPanel(live, C.TRACK_TYPE_AUDIO, Modifier.align(Alignment.CenterEnd)) { panel = Panel.None }
            Panel.Subtitles -> TrackPanel(live, C.TRACK_TYPE_TEXT, Modifier.align(Alignment.CenterEnd)) { panel = Panel.None }
            Panel.None -> Unit
        }
    }

    LaunchedEffect(Unit) { runCatching { rootReq.requestFocus() } }
}

@Composable
private fun Clock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(20_000) }
    }
    Text(hhmm(now), style = Type.H2, color = Jx.Text)
}

/** "AHORA 7:30–9:15  Título" + barra de avance + "DESPUÉS 9:15  Título". */
@Composable
private fun EpgLines(ch: Channel, repo: TvRepo) {
    val version by repo.epgVersion
    val epg by produceState<List<EpgItem>?>(repo.cachedEpg(ch), ch.id, version) {
        value = repo.epg(ch)
        // Se actualiza sola cada minuto (cambio de programa, avance de la barra)
        while (true) { delay(60_000); value = repo.epg(ch) }
    }
    val list = epg.orEmpty()
    val now = repo.nowPlaying(list) ?: return
    val next = repo.nextAfter(list, now)
    Column(Modifier.padding(start = 60.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AHORA", style = Type.Eyebrow, color = Jx.Accent)
            Text("${hhmm(now.start)} – ${hhmm(now.end)}", style = Type.Small, color = Jx.Muted)
            Text(now.title, style = Type.Label, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ThinProgress(now.progress(), Modifier.width(300.dp), height = 3.dp)
        if (next != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("DESPUÉS", style = Type.Eyebrow, color = Jx.Muted)
                Text(hhmm(next.start), style = Type.Small, color = Jx.Muted)
                Text(next.title, style = Type.Small, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Lista de canales a la izquierda, con lo que pasan ahora. OK cambia de canal sin salir. */
@Composable
private fun ChannelPanel(live: LivePlayback, repo: TvRepo, modifier: Modifier, onClose: () -> Unit) {
    val list = live.channels
    val currentIndex = list.indexOfFirst { it.id == live.current?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val currentReq = remember { FocusRequester() }
    Column(
        modifier.width(380.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF2070A0F), Color(0xE6070A0F))))
            .padding(top = 27.dp),
    ) {
        Text(live.group.ifBlank { "Canales" }, style = Type.H2, color = Jx.Text,
            modifier = Modifier.padding(start = 24.dp, bottom = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 27.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                val playing = c.id == live.current?.id
                PanelRow(i + 1, c, repo, playing,
                    if (i == currentIndex) Modifier.focusRequester(currentReq) else Modifier) {
                    live.play(c, list, live.group)
                    onClose()
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(30); runCatching { currentReq.requestFocus() } }
}

@Composable
private fun PanelRow(num: Int, c: Channel, repo: TvRepo, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(c), c.id, version) {
        if (value == null) { delay(250); value = repo.epg(c) }
    }
    val now = epg?.let { repo.nowPlaying(it) }
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(54.dp), focusedScale = 1.02f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$num", style = Type.Number, color = if (playing) Jx.Accent else Jx.Faint, modifier = Modifier.width(34.dp))
            RemoteImage(c.logo, c.name, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)), scale = ContentScale.Fit)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) Jx.Accent else androidx.tv.material3.LocalContentColor.current)
                if (now != null) Text(now.title, style = Type.Small, color = Jx.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Elegir pista de audio o subtítulos. */
@OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(live: LivePlayback, type: Int, modifier: Modifier, onClose: () -> Unit) {
    val p = live.player ?: return
    @Suppress("UNUSED_VARIABLE") val v = live.tracksVersion
    data class Opt(val label: String, val selected: Boolean, val apply: () -> Unit)
    val opts = buildList {
        if (type == C.TRACK_TYPE_TEXT) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            add(Opt("Desactivados", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        var n = 0
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                add(Opt(trackLabel(f, type, n), g.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                })
            }
        }
    }
    val firstReq = remember { FocusRequester() }
    Column(
        modifier.width(340.dp).fillMaxHeight().background(Color(0xF2070A0F)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulos", style = Type.H2, color = Jx.Text)
        Spacer(Modifier.height(6.dp))
        if (opts.isEmpty() || (type == C.TRACK_TYPE_TEXT && opts.size == 1)) {
            Text(if (type == C.TRACK_TYPE_AUDIO) "Este canal tiene un solo audio." else "Este canal no trae subtítulos.",
                style = Type.Body, color = Jx.Muted)
        }
        opts.forEachIndexed { i, o ->
            FocusSurface(
                onClick = { o.apply(); onClose() },
                modifier = Modifier.fillMaxWidth().height(44.dp).let { if (i == 0) it.focusRequester(firstReq) else it },
                focusedScale = 1.02f,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.label, style = Type.Label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (o.selected) JxIcon(R.drawable.tv_ic_check, 16.dp, Jx.Ok, "Seleccionado")
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(30); runCatching { firstReq.requestFocus() } }
}

private fun trackLabel(f: Format, type: Int, n: Int): String {
    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
        Locale(it).getDisplayLanguage(Locale("es")).replaceFirstChar { c -> c.uppercase() }
    }
    val name = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Pista $n"
    if (type != C.TRACK_TYPE_AUDIO) return name
    val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Estéreo"; 6 -> "5.1"; 8 -> "7.1"; else -> "" }
    return listOf(name, ch).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun nextResize(mode: Int) = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun resizeLabel(mode: Int) = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Imagen: ampliar"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Imagen: estirar"
    else -> "Imagen: ajustar"
}
