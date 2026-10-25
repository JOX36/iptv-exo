package com.jox3.iptvexo.tv.player

import android.view.KeyEvent as AndroidKey
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                // Mientras el canal se reproduce, el TV no entra en reposo ni en protector de pantalla
                v.keepScreenOn = live.playingNow
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

/** Opciones de la barra inferior. Las que tienen panel lo muestran apenas reciben el foco. */
private enum class Opt(val label: String, val hasPanel: Boolean) {
    Channels("Canales", true),
    Audio("Audio", true),
    Subtitles("Subtítulos", true),
    Picture("Imagen", true),
    Info("Información", true),
    Guide("Elegir guía", true),
    Favorite("Favorito", false),
    Reload("Recargar", false),
}

/** Fondo translúcido de los paneles: se sigue viendo el video detrás. */
internal val PanelBrush = Brush.horizontalGradient(listOf(Color(0xB3070A0F), Color(0x80070A0F), Color(0x33070A0F)))

/**
 * Controles a pantalla completa, al estilo del reproductor de JOX3 Verifier:
 * arriba el canal y la guía (AHORA / DESPUÉS con avance), abajo una barra de
 * opciones. Al moverte por las opciones, su panel (canales, audio, subtítulos,
 * imagen) aparece al instante; con ▲ u OK entras en él.
 *
 * Con los controles ocultos: ▲▼ cambian de canal, ◀▶ abren la lista de canales,
 * OK muestra las opciones, los números van directo a un canal y Atrás vuelve a
 * la guía (el video sigue sin cortarse).
 */
@Composable
fun LiveFullscreen(live: LivePlayback, repo: TvRepo, onExit: () -> Unit) {
    val ch = live.current ?: return
    var bars by remember { mutableStateOf(true) }
    var option by remember { mutableStateOf<Opt?>(null) }        // opción con el foco (decide el panel)
    var inPanel by remember { mutableStateOf(false) }            // el foco está dentro del panel
    var rootFocused by remember { mutableStateOf(false) }
    var anyFocus by remember { mutableStateOf(false) }      // el reproductor o algo dentro tiene el foco
    var activity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootReq = remember { FocusRequester() }
    val optReqs = remember { Opt.values().associateWith { FocusRequester() } }
    val panelReq = remember { FocusRequester() }
    var pendingPanelFocus by remember { mutableStateOf(false) }
    // El panel de la opción solo se abre al elegirla (OK o ▲). Con OK en el video
    // aparecen primero la información del canal y los botones, sin panel.
    var panelOpen by remember { mutableStateOf(false) }
    val lib = repo.library
    val index = live.channels.indexOfFirst { it.id == ch.id }

    // Pedidos de foco "diferidos": se cumplen en el cuadro siguiente, cuando lo que
    // iba a desaparecer (un panel, una opción) ya no está. Así el foco nunca queda
    // en un elemento borrado (era lo que a veces dejaba el OK sin responder).
    var focusRootTick by remember { mutableIntStateOf(0) }
    var focusOptTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusRootTick) {
        if (focusRootTick == 0) return@LaunchedEffect
        delay(16); runCatching { rootReq.requestFocus() }
    }
    LaunchedEffect(focusOptTick) {
        if (focusOptTick == 0) return@LaunchedEffect
        delay(16); option?.let { o -> runCatching { optReqs.getValue(o).requestFocus() } }
    }

    fun hideAll() {
        bars = false; option = null; inPanel = false; panelOpen = false
        runCatching { rootReq.requestFocus() }
        focusRootTick++
    }

    /** Atrás, de adentro hacia afuera: panel → opciones → información → volver a la guía. */
    fun goBack() {
        when {
            inPanel && option != null -> { panelOpen = false; focusOptTick++ }
            panelOpen && option != null -> panelOpen = false
            option != null || bars -> hideAll()
            else -> onExit()
        }
    }
    fun openOptions(focus: Opt, enterPanel: Boolean = false) {
        bars = true
        option = focus
        panelOpen = enterPanel
        pendingPanelFocus = enterPanel
        // El foco se mueve en el siguiente cuadro, cuando la barra ya existe
    }

    // Número de canal escrito con el control
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = digits.toIntOrNull() ?: 0
        digits = ""
        if (n in 1..live.channels.size) live.playIndex(n - 1)
    }

    // Al cambiar de canal se muestra la información unos segundos (sin opciones)
    LaunchedEffect(ch.id) {
        if (option == null) { bars = true; activity = System.currentTimeMillis() }
    }
    // Todo se oculta solo tras 6 s sin tocar el control
    LaunchedEffect(bars, activity) {
        if (!bars) return@LaunchedEffect
        delay(if (option != null) 8000 else 5000)
        hideAll()
    }
    // Llevar el foco a la opción (o dentro de su panel) cuando se abren las opciones
    LaunchedEffect(bars, option, pendingPanelFocus) {
        val o = option ?: return@LaunchedEffect
        if (!bars) return@LaunchedEffect
        delay(50)
        if (pendingPanelFocus) {
            pendingPanelFocus = false
            runCatching { panelReq.requestFocus() }
        } else if (rootFocused) {
            runCatching { optReqs.getValue(o).requestFocus() }
        }
    }

    // Red de seguridad: si el foco se pierde (un panel desapareció con el foco dentro),
    // vuelve al reproductor para que OK y las flechas sigan respondiendo.
    LaunchedEffect(anyFocus) {
        if (anyFocus) return@LaunchedEffect
        delay(120)
        if (!anyFocus) runCatching { rootReq.requestFocus() }
    }

    // Respaldo: si el foco se perdiera, Atrás igual llega por aquí
    BackHandler { goBack() }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                activity = System.currentTimeMillis()
                val code = ev.nativeKeyEvent.keyCode
                if (code == AndroidKey.KEYCODE_BACK || code == AndroidKey.KEYCODE_ESCAPE) {
                    // Se atiende al soltar la tecla y se consume entera (bajada y subida)
                    if (ev.type == KeyEventType.KeyUp && !ev.nativeKeyEvent.isCanceled) goBack()
                    true
                } else false
            }
            .onFocusChanged { anyFocus = it.hasFocus }
            .focusRequester(rootReq)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || !rootFocused) return@onKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_CHANNEL_UP -> { live.zap(+1); true }
                    AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_CHANNEL_DOWN -> { live.zap(-1); true }
                    AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT,
                    AndroidKey.KEYCODE_GUIDE, AndroidKey.KEYCODE_TV_INPUT -> { openOptions(Opt.Channels, enterPanel = true); true }
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER,
                    AndroidKey.KEYCODE_INFO, AndroidKey.KEYCODE_MENU -> { openOptions(option ?: Opt.Channels); true }
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
        if (bars && !(panelOpen && option == Opt.Info)) {
            // ── Arriba: canal + guía (con Información abierta, el panel ya trae todo) ──
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
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

        // ── Panel de la opción enfocada (translúcido, encima del video) ──
        val o = option
        if (bars && o != null && o.hasPanel && panelOpen) {
            Box(
                (if (o == Opt.Info)
                    // Información: ocupa toda la altura (de arriba hasta la barra de opciones)
                    Modifier.align(Alignment.TopStart).padding(bottom = 96.dp).fillMaxWidth(0.66f).fillMaxHeight()
                else Modifier.align(Alignment.BottomStart).padding(bottom = 96.dp)
                    .width(if (o == Opt.Channels) 400.dp else 340.dp)
                    .fillMaxHeight(if (o == Opt.Channels) 0.72f else 0.5f))
                    .onFocusChanged { inPanel = it.hasFocus }
                    // Al salir del panel hacia abajo se vuelve a SU opción (no a la más cercana);
                    // por los costados o arriba no se sale.
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
                    Opt.Channels -> ChannelPanel(live, repo, panelReq) { hideAll() }
                    Opt.Audio -> TrackPanel(live.player, live.tracksVersion, C.TRACK_TYPE_AUDIO, panelReq)
                    Opt.Subtitles -> TrackPanel(live.player, live.tracksVersion, C.TRACK_TYPE_TEXT, panelReq)
                    Opt.Picture -> PicturePanel(live.resizeMode, { live.resizeMode = it }, panelReq)
                    Opt.Info -> LiveInfoPanel(live, repo, ch)
                    Opt.Guide -> GuidePickPanel(repo, ch, panelReq)
                    else -> Unit
                }
            }
        }

        // ── Abajo: barra de opciones ──
        if (bars && option != null) {
            val isFav = lib.isFavorite("live", ch.id)
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    // ▲ sobre un botón con panel lo abre y entra en él (si el panel aún no está abierto)
                    .onPreviewKeyEvent { ev ->
                        val k = ev.nativeKeyEvent.keyCode
                        if (k == AndroidKey.KEYCODE_DPAD_UP && !panelOpen && option?.hasPanel == true) {
                            if (ev.type == KeyEventType.KeyDown) { panelOpen = true; pendingPanelFocus = true }
                            true
                        } else false
                    }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Opt.values().forEach { opt ->
                        OptionTab(
                            label = when (opt) {
                                Opt.Favorite -> if (isFav) "En favoritos" else "Favorito"
                                Opt.Picture -> resizeLabel(live.resizeMode)
                                else -> opt.label
                            },
                            icon = when (opt) {
                                Opt.Channels -> R.drawable.tv_ic_list
                                Opt.Audio -> R.drawable.ic_audio
                                Opt.Subtitles -> R.drawable.ic_subs
                                Opt.Picture -> R.drawable.ic_resize
                                Opt.Favorite -> if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star
                                Opt.Reload -> R.drawable.tv_ic_restart
                                Opt.Info -> R.drawable.tv_ic_info
                                Opt.Guide -> R.drawable.ic_guide
                            },
                            selected = option == opt,
                            modifier = Modifier.focusRequester(optReqs.getValue(opt)),
                            onFocused = { option = opt },
                            onClick = {
                                when (opt) {
                                    Opt.Favorite -> lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
                                    Opt.Reload -> live.reload()
                                    // OK abre el panel y entra en él
                                    else -> { panelOpen = true; pendingPanelFocus = true }
                                }
                            },
                        )
                    }
                }
                Text(if (panelOpen) "◀▶ opciones   ▲: entrar al panel   Atrás: cerrar" else "◀▶ elegir   OK o ▲: abrir   Atrás: ocultar",
                style = Type.Small, color = Jx.Muted)
            }
        }

        if (digits.isNotEmpty()) {
            Text(digits, style = Type.Hero, color = Jx.Text,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 48.dp)
                    .background(Color(0xCC070A0F), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 6.dp))
        }
    }

    LaunchedEffect(Unit) { runCatching { rootReq.requestFocus() } }
}

/** Pestaña de la barra de opciones: al recibir el foco muestra su panel. */
@Composable
internal fun OptionTab(
    label: String, icon: Int, selected: Boolean, modifier: Modifier,
    onFocused: () -> Unit, onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(42.dp).onFocusChanged { if (it.isFocused) onFocused() },
        shape = RoundedCornerShape(21.dp),
        focusedScale = 1.06f,
        container = if (selected) Color(0x4000D4FF) else Color(0x66101722),
        content = if (selected) Jx.Text else Jx.TextSoft,
        focusedContainer = Jx.Text,
        focusedContent = Jx.Bg,
        ring = false,
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JxIcon(icon, 16.dp)
            Text(label, style = Type.Label, maxLines = 1)
        }
    }
}

@Composable
internal fun Clock() {
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
            Text(now.label, style = Type.Label, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ThinProgress(now.progress(), Modifier.width(300.dp), height = 3.dp)
        if (next != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("DESPUÉS", style = Type.Eyebrow, color = Jx.Muted)
                Text(hhmm(next.start), style = Type.Small, color = Jx.Muted)
                Text(next.label, style = Type.Small, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Lista de canales con lo que pasan ahora. OK cambia de canal. */
@Composable
private fun ChannelPanel(live: LivePlayback, repo: TvRepo, entryReq: FocusRequester, onPicked: () -> Unit) {
    val list = live.channels
    val currentIndex = list.indexOfFirst { it.id == live.current?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    Column(Modifier.fillMaxSize().background(PanelBrush, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)).padding(top = 14.dp)) {
        Text(live.group.ifBlank { "Canales" }, style = Type.H2, color = Jx.Text,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                val playing = c.id == live.current?.id
                PanelRow(i + 1, c, repo, playing,
                    if (i == currentIndex) Modifier.focusRequester(entryReq) else Modifier) {
                    live.play(c, list, live.group)
                    onPicked()
                }
            }
        }
    }
}

@Composable
private fun PanelRow(num: Int, c: Channel, repo: TvRepo, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(c), c.id, version) {
        if (value.isNullOrEmpty()) { delay(250); value = repo.epg(c) }
    }
    val now = epg?.let { repo.nowPlaying(it) }
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(54.dp), focusedScale = 1.02f,
        focusedContainer = Color(0xCC152131)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$num", style = Type.Number, color = if (playing) Jx.Accent else Jx.Muted, modifier = Modifier.width(34.dp))
            RemoteImage(c.logo, c.name, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)), scale = ContentScale.Fit)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) Jx.Accent else androidx.tv.material3.LocalContentColor.current)
                if (now != null) Text(now.label, style = Type.Small, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Opción elegible dentro de un panel (pista, formato de imagen). */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(40.dp), focusedScale = 1.02f,
        focusedContainer = Color(0xCC152131)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.Label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) JxIcon(R.drawable.tv_ic_check, 16.dp, Jx.Ok, "Seleccionado")
        }
    }
}

@Composable
internal fun PanelBox(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(PanelBrush, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
            // Con muchas opciones (velocidad, apagado) el panel se desplaza: ninguna queda cortada
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = Type.H2, color = Jx.Text)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/** Audio o subtítulos: la pista elegida se aplica al instante y el panel sigue abierto. */
@OptIn(UnstableApi::class)
@Composable
internal fun TrackPanel(
    player: androidx.media3.exoplayer.ExoPlayer?, tracksVersion: Int, type: Int, entryReq: FocusRequester,
    what: String = "Este canal",
) {
    val p = player ?: return
    @Suppress("UNUSED_VARIABLE") val v = tracksVersion
    data class Choice(val label: String, val selected: Boolean, val apply: () -> Unit)
    val opts = buildList {
        if (type == C.TRACK_TYPE_TEXT) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            add(Choice("Desactivados", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        var n = 0
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                add(Choice(trackLabel(f, type, n), g.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                })
            }
        }
    }
    PanelBox(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulos") {
        if (opts.isEmpty()) {
            // Siempre hay algo enfocable: así ▲ desde la opción nunca apunta a un panel vacío
            ChoiceRow(if (type == C.TRACK_TYPE_AUDIO) "Audio principal" else "Sin subtítulos", true,
                Modifier.focusRequester(entryReq)) { }
        } else if (type == C.TRACK_TYPE_TEXT && opts.size == 1) {
            Text("$what no trae subtítulos.", style = Type.Body, color = Jx.TextSoft)
        }
        opts.forEachIndexed { i, o ->
            ChoiceRow(o.label, o.selected, if (o.selected || (i == 0 && opts.none { it.selected })) Modifier.focusRequester(entryReq) else Modifier) { o.apply() }
        }
    }
}

/** Formato de imagen: ajustar, ampliar o estirar. */
@Composable
internal fun PicturePanel(mode: Int, onSet: (Int) -> Unit, entryReq: FocusRequester) {
    val modes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar (sin recortar)",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Ampliar (llenar recortando)",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Estirar",
    )
    PanelBox("Imagen") {
        modes.forEach { (m, label) ->
            val sel = mode == m
            ChoiceRow(label, sel, if (sel) Modifier.focusRequester(entryReq) else Modifier) { onSet(m) }
        }
    }
}

internal fun trackLabel(f: Format, type: Int, n: Int): String {
    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
        Locale(it).getDisplayLanguage(Locale("es")).replaceFirstChar { c -> c.uppercase() }
    }
    val name = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Pista $n"
    if (type != C.TRACK_TYPE_AUDIO) return name
    val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Estéreo"; 6 -> "5.1"; 8 -> "7.1"; else -> "" }
    return listOf(name, ch).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun resizeLabel(mode: Int) = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Imagen: ampliar"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Imagen: estirar"
    else -> "Imagen"
}

/** Información del canal: lo que dan ahora y después, y la información técnica. */
@Composable
private fun LiveInfoPanel(live: LivePlayback, repo: TvRepo, ch: Channel) {
    @Suppress("UNUSED_VARIABLE") val v = live.tracksVersion
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) { value = repo.epg(ch) }
    val list = epg.orEmpty()
    val now = repo.nowPlaying(list)
    val next = repo.nextAfter(list, now)
    // Refresco cada 2 s (búfer, resolución)
    val tick by produceState(0) { while (true) { delay(2000); value++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    InfoBox(
        title = now?.title ?: ch.name,
        meta = buildString {
            append(ch.name)
            if (live.group.isNotBlank()) append(" · ").append(live.group)
        },
        eyebrow = now?.let { "AHORA · ${hhmm(it.start)} – ${hhmm(it.end)}" },
        progress = now?.let { ((System.currentTimeMillis() - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
        body = now?.description?.takeIf { it.isNotBlank() } ?: if (now == null) "Este canal no tiene guía." else "",
        after = next?.let { "DESPUÉS · ${hhmm(it.start)}  ${it.title}" },
        tech = techInfo(ctx, live.player, repo.api.liveUrl(ch.id)),
    )
}


/**
 * Información a pantalla completa (izquierda, de arriba a abajo, translúcida):
 * a la izquierda el programa o título con su sinopsis completa y a la derecha la
 * información técnica. Así nada queda cortado como en el panel pequeño.
 */
@Composable
internal fun InfoBox(
    title: String, meta: String, eyebrow: String?, progress: Float?, body: String, after: String?,
    tech: List<Pair<String, String>>,
) {
    Row(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color(0xF0070A0F), Color(0xD9070A0F), Color(0x99070A0F))),
                RoundedCornerShape(bottomEnd = 16.dp))
            .padding(start = 48.dp, top = 30.dp, end = 28.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Type.H1, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) Text(meta, style = Type.Small, color = Jx.Muted)
            if (eyebrow != null) Text(eyebrow, style = Type.Eyebrow, color = Jx.Accent)
            if (progress != null) {
                Box(Modifier.width(220.dp).height(3.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Jx.Accent, RoundedCornerShape(2.dp)))
                }
            }
            if (body.isNotBlank()) Text(body, style = Type.Body, color = Jx.TextSoft)
            if (after != null) Text(after, style = Type.Small, color = Jx.Muted, modifier = Modifier.padding(top = 4.dp))
        }
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("INFORMACIÓN TÉCNICA", style = Type.Eyebrow, color = Jx.Muted)
            Spacer(Modifier.height(2.dp))
            tech.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(k, style = Type.Label, color = Jx.Muted, modifier = Modifier.width(130.dp))
                    Text(v.ifBlank { "—" }, style = Type.Label, color = Jx.Text)
                }
            }
        }
    }
}


/** Elegir a mano qué guía usa este canal (cuando la automática no acierta o no hay). */
@Composable
private fun GuidePickPanel(repo: TvRepo, ch: com.jox3.iptvexo.tv.data.Channel, req: FocusRequester) {
    val version by repo.epgVersion
    val choices by produceState<List<TvRepo.GuideChoice>?>(null, ch.id, version) { value = repo.guideChoices(ch) }
    PanelBox("Elegir guía") {
        val list = choices
        if (list == null) { Text("Buscando guías parecidas…", style = Type.Small, color = Jx.Muted); return@PanelBox }
        Text("Si la guía no corresponde al canal, elige la correcta. Queda guardado.", style = Type.Small, color = Jx.Muted)
        list.forEachIndexed { i, c ->
            ChoiceRow(c.label, c.selected, if (i == 0) Modifier.focusRequester(req) else Modifier) { repo.chooseGuide(ch, c.key) }
        }
    }
}
