package com.jox3.iptvexo.tv.player

import android.view.KeyEvent as AndroidKey
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Opciones de la barra inferior. Las que tienen panel lo muestran al recibir el foco. */
private enum class VOpt(val label: String, val panel: Boolean, val enter: Boolean = panel) {
    Play("Pausa", false),
    Next("Siguiente", false),
    Audio("Audio", true),
    Subtitles("Subtítulos", true),
    Speed("Velocidad", true),
    Sleep("Apagado", true),
    Picture("Imagen", true),
    Info("Información", true, enter = false),
    Favorite("Favorito", false),
    Close("Cerrar", false),
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEPS = listOf(0, 15, 30, 45, 60, 90, 120, -1)
private val clockFmt = SimpleDateFormat("h:mm a", Locale("es"))

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
private fun speedLabel(s: Float) = if (abs(s - 1f) < 0.01f) "Normal" else "${s.toString().trimEnd('0').trimEnd('.')}x"
private fun sleepLabel(min: Int) = when (min) { 0 -> "Desactivado"; -1 -> "Al terminar este video"; else -> "En $min minutos" }

/**
 * Películas y series a pantalla completa (estilo del reproductor en vivo).
 *
 * Con los controles ocultos: ◀▶ retroceden/adelantan 10 s, OK o ▲▼ muestran los
 * controles, Atrás sale. En la barra de tiempo: ◀▶ mueven la posición (mantenido
 * va más rápido) y OK pausa. Debajo, las opciones: su panel se abre al pasar por ellas.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodFullscreen(vod: VodPlayback, lib: LibraryStore, onExit: () -> Unit, onMinimize: () -> Unit = onExit) {
    val cur = vod.item ?: return
    var bars by remember { mutableStateOf(true) }
    var option by remember { mutableStateOf<VOpt?>(null) }
    var inPanel by remember { mutableStateOf(false) }
    var rootFocused by remember { mutableStateOf(false) }
    var anyFocus by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootReq = remember { FocusRequester() }
    val seekReq = remember { FocusRequester() }
    val panelReq = remember { FocusRequester() }
    val dialogReq = remember { FocusRequester() }
    val optReqs = remember { VOpt.values().associateWith { FocusRequester() } }
    var focusSeekTick by remember { mutableIntStateOf(0) }
    var focusRootTick by remember { mutableIntStateOf(0) }
    var focusOptTick by remember { mutableIntStateOf(0) }

    // Diálogos: error, siguiente episodio, apagado automático
    val nextPending = vod.ended && vod.hasNext && !vod.sleepFired
    val dialog = vod.failed || nextPending || vod.sleepFired

    LaunchedEffect(focusSeekTick) { if (focusSeekTick > 0) { delay(30); runCatching { seekReq.requestFocus() } } }
    LaunchedEffect(focusRootTick) { if (focusRootTick > 0) { delay(16); runCatching { rootReq.requestFocus() } } }
    LaunchedEffect(focusOptTick) {
        if (focusOptTick > 0) { delay(16); option?.let { o -> runCatching { optReqs.getValue(o).requestFocus() } } }
    }

    fun showBars() { activity = System.currentTimeMillis(); if (!bars) bars = true; focusSeekTick++ }
    fun hideAll() { bars = false; option = null; inPanel = false; focusRootTick++ }
    fun goBack() {
        when {
            dialog -> onExit()
            inPanel && option != null -> focusOptTick++
            bars -> hideAll()
            // Atrás con los controles ocultos: sigue en un reproductor pequeño mientras eliges otra cosa
            else -> onMinimize()
        }
    }

    // Se ocultan solos tras 5 s sin tocar el control (no si está en pausa)
    LaunchedEffect(bars, activity, vod.playing, option) {
        if (!bars || !vod.playing || dialog) return@LaunchedEffect
        delay(if (option != null) 8000 else 5000)
        hideAll()
    }
    // Al abrir: controles visibles con el foco en la barra de tiempo
    LaunchedEffect(cur.id) { bars = true; activity = System.currentTimeMillis(); focusSeekTick++ }
    // Siguiente episodio con cuenta regresiva; al terminar el último, se sale
    var countdown by remember { mutableIntStateOf(10) }
    LaunchedEffect(vod.ended, vod.index, vod.sleepFired) {
        if (!vod.ended || vod.sleepFired) return@LaunchedEffect
        if (vod.hasNext) {
            countdown = 10
            while (countdown > 0) { delay(1000); countdown-- }
            vod.next()
        } else { delay(1200); onExit() }
    }
    LaunchedEffect(dialog) { if (dialog) { bars = false; option = null; delay(40); runCatching { dialogReq.requestFocus() } } }
    LaunchedEffect(anyFocus) {
        if (anyFocus) return@LaunchedEffect
        delay(120)
        if (!anyFocus) runCatching { rootReq.requestFocus() }
    }
    BackHandler { goBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                activity = System.currentTimeMillis()
                val code = ev.nativeKeyEvent.keyCode
                when (code) {
                    AndroidKey.KEYCODE_BACK, AndroidKey.KEYCODE_ESCAPE -> {
                        if (ev.type == KeyEventType.KeyUp && !ev.nativeKeyEvent.isCanceled) goBack()
                        true
                    }
                    AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKey.KEYCODE_MEDIA_PLAY, AndroidKey.KEYCODE_MEDIA_PAUSE -> {
                        if (ev.type == KeyEventType.KeyDown) { vod.togglePlay(); showBars() }; true
                    }
                    AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> { if (ev.type == KeyEventType.KeyDown) { vod.seekBy(30_000); showBars() }; true }
                    AndroidKey.KEYCODE_MEDIA_REWIND -> { if (ev.type == KeyEventType.KeyDown) { vod.seekBy(-30_000); showBars() }; true }
                    AndroidKey.KEYCODE_MEDIA_NEXT -> { if (ev.type == KeyEventType.KeyDown) vod.next(); true }
                    else -> false
                }
            }
            .onFocusChanged { anyFocus = it.hasFocus }
            .focusRequester(rootReq)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || !rootFocused || dialog) return@onKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    AndroidKey.KEYCODE_DPAD_LEFT -> { vod.seekBy(-10_000); showBars(); true }
                    AndroidKey.KEYCODE_DPAD_RIGHT -> { vod.seekBy(10_000); showBars(); true }
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER,
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN,
                    AndroidKey.KEYCODE_INFO, AndroidKey.KEYCODE_MENU -> { showBars(); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { v ->
                if (v.player !== vod.player) v.player = vod.player
                v.resizeMode = vod.resizeMode
                v.keepScreenOn = vod.playing
            },
            modifier = Modifier.fillMaxSize(),
        )

        val st = vod.status
        if (st != null && !vod.failed) {
            Box(Modifier.align(Alignment.Center).background(Color(0xCC070A0F), RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(st, style = Type.Label, color = Jx.Text)
            }
        }

        if (bars && !dialog && option != VOpt.Info) {
            // ── Arriba: título (con Información abierta, el panel ya lo trae) ──
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 40.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cur.title, style = Type.H1, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cur.subtitle.isNotBlank()) Text(cur.subtitle, style = Type.Small, color = Jx.Muted, maxLines = 1)
                }
                if (vod.quality.isNotEmpty()) Badge(vod.quality)
                if (abs(vod.speed - 1f) > 0.01f) Badge(speedLabel(vod.speed))
                if (vod.sleepAt != 0L) Badge("Apagado: " + if (vod.sleepAt < 0) "al terminar"
                    else "${((vod.sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min")
                Clock()
            }
        }

        // ── Panel de la opción enfocada ──
        val o = option
        if (bars && !dialog && o != null && o.panel) {
            Box(
                (if (o == VOpt.Info)
                    Modifier.align(Alignment.TopStart).padding(bottom = 150.dp).fillMaxWidth(0.66f).fillMaxHeight()
                else Modifier.align(Alignment.BottomStart).padding(bottom = 150.dp).width(340.dp).fillMaxHeight(0.66f))
                    .onFocusChanged { f -> inPanel = f.hasFocus }
                    .focusProperties {
                        exit = { d ->
                            when (d) {
                                FocusDirection.Down -> optReqs.getValue(o)
                                FocusDirection.Left, FocusDirection.Right, FocusDirection.Up -> FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup()
            ) {
                when (o) {
                    VOpt.Audio -> TrackPanel(vod.player, vod.tracksVersion, C.TRACK_TYPE_AUDIO, panelReq, "Este video")
                    VOpt.Subtitles -> TrackPanel(vod.player, vod.tracksVersion, C.TRACK_TYPE_TEXT, panelReq, "Este video")
                    VOpt.Picture -> PicturePanel(vod.resizeMode, { m -> vod.resizeMode = m }, panelReq)
                    VOpt.Speed -> PanelBox("Velocidad") {
                        SPEEDS.forEach { s ->
                            val sel = abs(s - vod.speed) < 0.01f
                            ChoiceRow(speedLabel(s), sel, if (sel) Modifier.focusRequester(panelReq) else Modifier) { vod.setPlaybackSpeed(s) }
                        }
                    }
                    VOpt.Sleep -> PanelBox("Apagado automático") {
                        val cur = when { vod.sleepAt == 0L -> 0; vod.sleepAt < 0 -> -1; else -> null }
                        SLEEPS.forEach { m ->
                            val sel = cur == m
                            ChoiceRow(sleepLabel(m), sel, if (m == 0) Modifier.focusRequester(panelReq) else Modifier) { vod.setSleep(m) }
                        }
                    }
                    VOpt.Info -> InfoPanel(vod)
                    else -> Unit
                }
            }
        }

        // ── Abajo: barra de tiempo + opciones ──
        if (bars && !dialog) {
            val isFav = lib.isFavorite(cur.fav.kind, cur.fav.id)
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                    .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SeekBar(vod, Modifier.focusRequester(seekReq).focusProperties {
                    up = FocusRequester.Cancel
                    down = optReqs.getValue(option ?: VOpt.Play)
                }, onFocused = { option = null }, onActivity = { activity = System.currentTimeMillis() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VOpt.values().filter { op -> op != VOpt.Next || vod.hasNext }.forEach { opt ->
                        OptionTab(
                            label = when (opt) {
                                VOpt.Play -> if (vod.playing) "Pausa" else "Reproducir"
                                VOpt.Favorite -> if (isFav) "En favoritos" else "Favorito"
                                VOpt.Speed -> if (abs(vod.speed - 1f) > 0.01f) speedLabel(vod.speed) else opt.label
                                else -> opt.label
                            },
                            icon = when (opt) {
                                VOpt.Play -> if (vod.playing) R.drawable.ic_pause else R.drawable.ic_play
                                VOpt.Next -> R.drawable.ic_next
                                VOpt.Audio -> R.drawable.ic_audio
                                VOpt.Subtitles -> R.drawable.ic_subs
                                VOpt.Speed -> R.drawable.ic_ffw10
                                VOpt.Sleep -> R.drawable.ic_stop
                                VOpt.Picture -> R.drawable.ic_resize
                                VOpt.Info -> R.drawable.tv_ic_info
                                VOpt.Favorite -> if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star
                                VOpt.Close -> R.drawable.ic_close
                            },
                            selected = option == opt,
                            modifier = Modifier.focusRequester(optReqs.getValue(opt)).focusProperties {
                                up = if (opt.enter) panelReq else seekReq
                            },
                            onFocused = { option = opt },
                            onClick = {
                                when (opt) {
                                    VOpt.Play -> vod.togglePlay()
                                    VOpt.Next -> vod.next()
                                    VOpt.Favorite -> lib.toggleFavorite(cur.fav)
                                    VOpt.Close -> onExit()
                                    else -> if (opt.enter) runCatching { panelReq.requestFocus() }
                                }
                            },
                        )
                    }
                }
            }
        }

        // ── Diálogos ──
        if (dialog) {
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp)
                    .background(Color(0xE6070A0F), RoundedCornerShape(16.dp)).padding(28.dp)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    vod.sleepFired -> {
                        Text("Apagado automático", style = Type.H1, color = Jx.Text)
                        Text("La reproducción quedó en pausa.", style = Type.Body, color = Jx.TextSoft)
                        DialogButtons("Seguir viendo", { vod.dismissSleep(true) }, "Salir", onExit, dialogReq)
                    }
                    vod.failed -> {
                        Text("No se pudo reproducir", style = Type.H1, color = Jx.Text)
                        Text(vod.status.orEmpty().removePrefix("No se pudo reproducir. "), style = Type.Body, color = Jx.TextSoft)
                        DialogButtons("Reintentar", { vod.retry() }, "Salir", onExit, dialogReq)
                    }
                    else -> {
                        val next = vod.queue.getOrNull(vod.index + 1)
                        Text("SIGUIENTE EPISODIO EN $countdown s", style = Type.Eyebrow, color = Jx.Accent)
                        Text(next?.title.orEmpty(), style = Type.H1, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        DialogButtons("Ver ahora", { vod.next() }, "Salir", onExit, dialogReq)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { rootReq.requestFocus() } }
}

@Composable
private fun Badge(text: String) {
    Text(text, style = Type.Small, color = Jx.Accent,
        modifier = Modifier.background(Color(0x33081626), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun DialogButtons(first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit, req: FocusRequester) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(first to onFirst, second to onSecond).forEachIndexed { i, (label, action) ->
            FocusSurface(
                onClick = action,
                modifier = (if (i == 0) Modifier.focusRequester(req) else Modifier).height(44.dp),
                shape = RoundedCornerShape(22.dp),
                focusedScale = 1.05f,
                container = Color(0x66101722),
                focusedContainer = Jx.Text,
                focusedContent = Jx.Bg,
                ring = false,
            ) {
                Text(label, style = Type.Label, modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp))
            }
        }
    }
}

/** Barra de tiempo enfocable: ◀▶ mueven la posición (mantenido acelera), OK pausa. */
@Composable
private fun SeekBar(vod: VodPlayback, modifier: Modifier, onFocused: () -> Unit, onActivity: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val dur = vod.duration
    val pos = vod.position
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { f -> focused = f.isFocused; if (f.isFocused) onFocused() }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                val step = when { repeat > 20 -> 120_000L; repeat > 6 -> 30_000L; else -> 10_000L }
                when (ev.nativeKeyEvent.keyCode) {
                    AndroidKey.KEYCODE_DPAD_LEFT -> { vod.seekBy(-step); onActivity(); true }
                    AndroidKey.KEYCODE_DPAD_RIGHT -> { vod.seekBy(step); onActivity(); true }
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER -> {
                        vod.togglePlay(); onActivity(); true
                    }
                    else -> false
                }
            }
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 8.dp else 5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF))) {
            if (dur > 0) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((vod.buffered.toFloat() / dur).coerceIn(0f, 1f)).background(Color(0x55FFFFFF)))
                Box(Modifier.fillMaxHeight().fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f))
                    .background(if (focused) Jx.Accent else Jx.Text))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(pos), style = Type.Label, color = Jx.Text)
            Spacer(Modifier.weight(1f))
            if (focused) Text("◀▶ mover   OK: ${if (vod.playing) "pausa" else "reproducir"}", style = Type.Small, color = Jx.Muted)
            Spacer(Modifier.weight(1f))
            if (dur > 0) {
                val left = ((dur - pos) / vod.speed).toLong()
                Text("-${fmt(dur - pos)} · termina ${clockFmt.format(Date(System.currentTimeMillis() + left))}",
                    style = Type.Label, color = Jx.TextSoft)
            }
        }
    }
}

/** Información: sinopsis y datos del título + información técnica (de JOX3 Verifier). */
@OptIn(UnstableApi::class)
@Composable
private fun InfoPanel(vod: VodPlayback) {
    @Suppress("UNUSED_VARIABLE") val tick = vod.position   // se refresca con el avance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cur = vod.item ?: return
    InfoBox(
        title = cur.title,
        meta = listOf(cur.subtitle, vod.metaOf(cur)).filter { it.isNotBlank() }.joinToString(" · "),
        eyebrow = null, progress = null,
        body = vod.plotOf(cur),
        after = null,
        tech = techInfo(ctx, vod.player, cur.url),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = Type.Label, color = Jx.Muted, modifier = Modifier.width(130.dp))
        Text(value.ifBlank { "—" }, style = Type.Label, color = Jx.Text)
    }
}


/**
 * Reproductor pequeño (esquina superior derecha, sin tapar la información) al pulsar Atrás en una película o
 * serie: sigue reproduciendo mientras se navega. No recibe el foco; para volver a
 * pantalla completa se abre de nuevo el mismo título (o ⏯ / ▶ del control).
 */
@OptIn(UnstableApi::class)
@Composable
fun VodMini(vod: VodPlayback, modifier: Modifier = Modifier) {
    val cur = vod.item ?: return
    Column(
        modifier.width(272.dp).background(Color(0xF2070A0F), RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(153.dp).background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                update = { v ->
                    if (v.player !== vod.player) v.player = vod.player
                    v.keepScreenOn = vod.playing
                },
                modifier = Modifier.fillMaxSize(),
            )
            val dur = vod.duration
            if (dur > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((vod.position.toFloat() / dur).coerceIn(0f, 1f)).background(Jx.Accent))
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(cur.title, style = Type.Label, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (vod.playing) "Ábrelo de nuevo para verlo en grande · ⏯ pausa" else "En pausa · ⏯ para seguir",
                style = Type.Small, color = Jx.Faint, maxLines = 1)
        }
    }
}
