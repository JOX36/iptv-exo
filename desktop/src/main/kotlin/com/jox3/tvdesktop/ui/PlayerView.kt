package com.jox3.tvdesktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.EpgItem
import com.jox3.tvdesktop.data.TvRepo
import com.jox3.tvdesktop.player.Aspect
import com.jox3.tvdesktop.player.PlayState
import kotlinx.coroutines.delay
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFmt = SimpleDateFormat("h:mm a", Locale.forLanguageTag("es"))
fun hhmm(ms: Long): String = if (ms > 0) clockFmt.format(Date(ms)) else ""

/** Cursor invisible (en pantalla completa, cuando los controles se ocultan). */
private val hiddenCursor: PointerIcon by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "oculto"))
}

/**
 * El reproductor en vivo con sus controles. Se usa en los tres modos:
 * vista previa (controles mínimos), ventana completa y mini reproductor.
 *
 * Mouse: mover = mostrar controles · doble clic = pantalla completa · rueda = volumen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerView(live: LiveController, modifier: Modifier = Modifier, onToggleFull: () -> Unit, onToggleMini: () -> Unit) {
    val player = live.player
    val ch = live.current
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }

    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val embedded = live.mode == ViewMode.EMBEDDED
    val paused = player.state == PlayState.Paused
    val showControls = live.panel != Panel.NONE || paused || now - lastMove < 3000

    Box(
        modifier
            .background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .onPointerEvent(PointerEventType.Enter) { lastMove = System.currentTimeMillis() }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) { live.changeVolume(if (dy < 0) 5 else -5); lastMove = System.currentTimeMillis() }
            }
            .pointerInput(live.mode) {
                detectTapGestures(
                    onDoubleTap = { onToggleFull() },
                    onTap = {
                        focus.clearFocus() // deja de escribir en el buscador: vuelven los atajos
                        if (live.panel != Panel.NONE) live.panel = Panel.NONE else lastMove = System.currentTimeMillis()
                    },
                )
            }
            .then(if (!embedded && !showControls) Modifier.pointerHoverIcon(hiddenCursor) else Modifier)
    ) {
        VideoSurface(player, Modifier.fillMaxSize())
        StatusLayer(live)

        // Número tecleado / aviso corto
        Column(Modifier.align(Alignment.TopEnd).padding(if (embedded) 10.dp else 28.dp), horizontalAlignment = Alignment.End) {
            if (live.typedNumber.isNotEmpty()) {
                Text(live.typedNumber, color = Jx.Accent, fontSize = if (embedded) 28.sp else 56.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Exo2,
                    modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 4.dp))
            }
            val t = live.toast
            if (t != null && now - t.second < 1800) {
                Spacer(Modifier.height(8.dp))
                Text(t.first, color = Jx.Text, fontSize = if (embedded) 13.sp else 17.sp, fontFamily = Exo2,
                    modifier = Modifier.background(Color(0xCC070A0F), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }

        if (embedded) {
            // Vista previa: solo una pista discreta al pasar el mouse
            AnimatedVisibility(ch != null && showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(if (paused) Ico.PLAY else Ico.PAUSE, "Pausa (espacio)", small = true) { player.togglePause() }
                    RoundButton(Ico.FULLSCREEN, "Pantalla completa (F)", small = true) { onToggleFull() }
                    RoundButton(Ico.MINI, "Mini reproductor (T)", small = true) { onToggleMini() }
                    Text("Doble clic: pantalla completa", color = Jx.TextSoft, fontSize = 11.sp, fontFamily = Exo2,
                        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            return@Box
        }

        // ── Ventana completa / mini: controles completos ──
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (live.mode == ViewMode.FULL) TopInfo(live, now, Modifier.align(Alignment.TopStart))
                BottomBar(live, now, onToggleFull, onToggleMini, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (live.panel != Panel.NONE && live.mode == ViewMode.FULL) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp, top = 24.dp, bottom = 120.dp).fillMaxHeight()) {
                SidePanel(live)
            }
        }
    }
}

/** Mensajes de estado: cargando, reconectando, error. */
@Composable
private fun BoxScope.StatusLayer(live: LiveController) {
    val player = live.player
    val small = live.mode != ViewMode.FULL
    when (val s = player.state) {
        PlayState.Idle -> if (live.current == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            JxIcon(Ico.LIVE, 30.dp, Jx.Muted)
            Spacer(Modifier.height(8.dp))
            Text("Haz clic en un canal para verlo aquí", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp)
            Text("Doble clic: pantalla completa", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
        }
        PlayState.Opening, is PlayState.Buffering, is PlayState.Reconnecting -> Column(
            Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Jx.Accent, modifier = Modifier.size(if (small) 28.dp else 44.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(10.dp))
            val text = when (s) {
                is PlayState.Buffering -> "Cargando… ${s.percent} %"
                is PlayState.Reconnecting -> "Reconectando… (${s.attempt} de ${s.max})"
                else -> "Conectando…"
            }
            Text(text, color = Jx.Text, fontFamily = Exo2, fontSize = if (small) 13.sp else 16.sp,
                modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        is PlayState.Failed -> Column(
            Modifier.align(Alignment.Center).padding(20.dp).widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s.message, color = Jx.Text, fontFamily = Exo2, fontSize = if (small) 13.sp else 16.sp)
            Spacer(Modifier.height(12.dp))
            PillButton("Reintentar") { player.togglePause() }
        }
        else -> {}
    }
}

@Composable
private fun TopInfo(live: LiveController, now: Long, modifier: Modifier) {
    val ch = live.current ?: return
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ch.num}", color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Text(ch.name, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        live.sleepAt?.let { at ->
            val min = ((at - now) / 60_000).coerceAtLeast(0) + 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                JxIcon(Ico.SLEEP, 16.dp, Jx.Gold); Spacer(Modifier.width(6.dp))
                Text("$min min", color = Jx.Gold, fontFamily = Exo2, fontSize = 14.sp)
            }
            Spacer(Modifier.width(18.dp))
        }
        Text(hhmm(now), color = Jx.TextSoft, fontFamily = Exo2, fontSize = 18.sp)
    }
}

@Composable
private fun BottomBar(live: LiveController, now: Long, onToggleFull: () -> Unit, onToggleMini: () -> Unit, modifier: Modifier) {
    val player = live.player
    val ch = live.current
    val mini = live.mode == ViewMode.MINI
    val control = LocalWindowControl.current
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = if (mini) 10.dp else 28.dp, vertical = if (mini) 8.dp else 20.dp)
    ) {
        if (ch != null && !mini) {
            EpgNowNext(live.repo, ch, now)
            Spacer(Modifier.height(14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (mini) 4.dp else 8.dp)) {
            val paused = player.state == PlayState.Paused
            RoundButton(Ico.PREV, "Canal anterior (↑)", small = mini) { live.step(-1) }
            RoundButton(if (paused) Ico.PLAY else Ico.PAUSE, "Pausa (espacio)", accent = true, small = mini) { player.togglePause() }
            RoundButton(Ico.NEXT, "Canal siguiente (↓)", small = mini) { live.step(1) }
            Spacer(Modifier.width(6.dp))
            RoundButton(if (player.muted || player.volume == 0) Ico.MUTE else Ico.VOLUME, "Silencio (M)", small = mini) { live.toggleMute() }
            if (!mini) {
                Slider(
                    value = player.volume.toFloat(), onValueChange = { player.setVolumeTo(it.toInt()) },
                    valueRange = 0f..200f, modifier = Modifier.width(140.dp),
                    colors = SliderDefaults.colors(thumbColor = Jx.Accent, activeTrackColor = Jx.Accent, inactiveTrackColor = Jx.Line),
                )
                Text("${player.volume} %", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 12.sp, modifier = Modifier.width(44.dp))
            }
            Spacer(Modifier.weight(1f))
            if (!mini) {
                RoundButton(Ico.LIST, "Canales (C)", selected = live.panel == Panel.CHANNELS) { live.toggle(Panel.CHANNELS) }
                RoundButton(Ico.AUDIO, "Audio (A)", selected = live.panel == Panel.AUDIO) { live.toggle(Panel.AUDIO) }
                RoundButton(Ico.SUBTITLES, "Subtítulos (S)", selected = live.panel == Panel.SUBTITLES) { live.toggle(Panel.SUBTITLES) }
                RoundButton(Ico.PICTURE, "Formato de imagen (Z)", selected = live.panel == Panel.PICTURE) { live.toggle(Panel.PICTURE) }
                RoundButton(Ico.SLEEP, "Apagado automático", selected = live.panel == Panel.SLEEP) { live.toggle(Panel.SLEEP) }
                RoundButton(Ico.INFO, "Información técnica (I)", selected = live.panel == Panel.INFO) { live.toggle(Panel.INFO) }
                Spacer(Modifier.width(6.dp))
            }
            RoundButton(Ico.MINI, if (mini) "Volver a la ventana (T)" else "Mini reproductor siempre encima (T)", selected = mini, small = mini) { onToggleMini() }
            RoundButton(
                if (control?.fullscreen == true) Ico.EXIT_FULLSCREEN else Ico.FULLSCREEN,
                "Pantalla completa (F)", small = mini,
            ) { onToggleFull() }
        }
    }
}

/** AHORA y A CONTINUACIÓN del canal, con barra de avance. */
@Composable
fun EpgNowNext(repo: TvRepo, ch: Channel, now: Long, compact: Boolean = false) {
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) { value = repo.epg(ch) }
    val list = epg.orEmpty()
    val cur = TvRepo.nowPlaying(list, now)
    val next = TvRepo.nextAfter(list, cur)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (cur == null) {
            Text(if (epg == null) "Cargando guía…" else "Sin guía para este canal", color = Jx.Faint, fontFamily = Exo2, fontSize = 14.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AHORA", color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text("${hhmm(cur.start)} – ${hhmm(cur.end)}", color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp)
        }
        Text(cur.title, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 16.sp else 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ProgressLine(cur.progress(now), Modifier.fillMaxWidth().padding(vertical = 3.dp))
        if (cur.description.isNotBlank()) {
            Text(cur.description, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp,
                maxLines = if (compact) 3 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (next != null) {
            Row(Modifier.padding(top = 2.dp)) {
                Text("DESPUÉS", color = Jx.Muted, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                Text("${hhmm(next.start)}  ${next.title}", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 3.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(Jx.Line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Jx.Accent))
    }
}

// ═══ Paneles de opciones ═══

@Composable
private fun SidePanel(live: LiveController) {
    val player = live.player
    Column(
        Modifier.width(340.dp).fillMaxHeight()
            .background(Color(0xF00A0E14), RoundedCornerShape(16.dp))
            .padding(18.dp)
            .pointerInput(Unit) { detectTapGestures { } }, // los clics dentro del panel no lo cierran
    ) {
        val title = when (live.panel) {
            Panel.CHANNELS -> live.listName.ifBlank { "Canales" }
            Panel.AUDIO -> "Audio"
            Panel.SUBTITLES -> "Subtítulos"
            Panel.PICTURE -> "Formato de imagen"
            Panel.SLEEP -> "Apagado automático"
            Panel.INFO -> "Información técnica"
            Panel.NONE -> ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RoundButton(Ico.CLOSE, "Cerrar (Esc)", small = true) { live.panel = Panel.NONE }
        }
        Spacer(Modifier.height(12.dp))
        when (live.panel) {
            Panel.CHANNELS -> ChannelsPanel(live)
            Panel.AUDIO -> Column(Modifier.fillMaxWidth()) {
                if (player.audioTracks.isEmpty()) Note("Este canal no informa pistas de audio.")
                player.audioTracks.forEach { t -> Choice(t.name, t.id == player.audioTrack) { player.selectAudio(t.id) } }
            }
            Panel.SUBTITLES -> Column(Modifier.fillMaxWidth()) {
                Choice("Sin subtítulos", player.subTrack < 0) { player.selectSubtitle(-1) }
                if (player.subTracks.isEmpty()) Note("Este canal no trae subtítulos.")
                player.subTracks.forEach { t -> Choice(t.name, t.id == player.subTrack) { player.selectSubtitle(t.id) } }
            }
            Panel.PICTURE -> Column(Modifier.fillMaxWidth()) {
                Aspect.entries.forEach { a -> Choice(a.label + if (a == Aspect.STRETCH) " (recomendado)" else "", a == player.aspect) { player.setAspectMode(a) } }
                Note("Atajo: Z cambia de formato.")
            }
            Panel.SLEEP -> Column(Modifier.fillMaxWidth()) {
                val sel = live.sleepAt
                Choice("Desactivado", sel == null) { live.setSleep(null) }
                listOf(15, 30, 45, 60, 90, 120).forEach { m -> Choice("En $m minutos", false) { live.setSleep(m) } }
                if (sel != null) Note("Se detendrá a las ${hhmm(sel)}.")
            }
            Panel.INFO -> {
                var info by remember { mutableStateOf(player.techInfo()) }
                LaunchedEffect(Unit) { while (true) { delay(2000); info = player.techInfo() } }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    info.forEach { (k, v) ->
                        Column {
                            Text(k.uppercase(), color = Jx.Muted, fontFamily = Exo2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(v, color = Jx.Text, fontFamily = Exo2, fontSize = 14.sp)
                        }
                    }
                }
            }
            Panel.NONE -> {}
        }
    }
}

@Composable
private fun ChannelsPanel(live: LiveController) {
    val list = live.list
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        val i = list.indexOfFirst { it.id == live.current?.id }
        if (i > 0) state.scrollToItem((i - 3).coerceAtLeast(0))
    }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(list, key = { _, c -> c.id }) { _, c ->
            val playing = c.id == live.current?.id
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (playing) Jx.SurfaceHi else Color.Transparent)
                    .clickable { live.play(c, list, live.listName) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${c.num}", color = if (playing) Jx.Accent else Jx.Faint, fontFamily = Exo2, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                RemoteImage(c.logo, c.name, Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(10.dp))
                Text(c.name, color = if (playing) Jx.Accent else Jx.Text, fontFamily = Exo2, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Jx.SurfaceHi else Color.Transparent)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) Jx.Accent else Jx.Line))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (selected) Jx.Accent else Jx.Text, fontFamily = Exo2, fontSize = 14.sp)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

/** Botón redondo con icono y descripción al pasar el mouse. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RoundButton(
    ico: Ico, tip: String, small: Boolean = false, accent: Boolean = false, selected: Boolean = false, onClick: () -> Unit,
) {
    val size = if (small) 30.dp else 42.dp
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Text(tip, color = Jx.Text, fontFamily = Exo2, fontSize = 12.sp,
                modifier = Modifier.background(Jx.SurfaceHi, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        },
        delayMillis = 500,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(when { accent -> Jx.Accent; selected -> Jx.Accent.copy(alpha = 0.25f); else -> Color(0x66000000) })
                .then(hoverTint())
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            JxIcon(ico, if (small) 15.dp else 20.dp, if (accent) Jx.OnAccent else Jx.Text)
        }
    }
}

/** Botón de texto en forma de píldora. */
@Composable
fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Jx.Accent).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, color = Jx.OnAccent, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Aclara un poco el fondo cuando el mouse pasa por encima. */
@Composable
private fun hoverTint(): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return Modifier.background(if (hovered) Color(0x33FFFFFF) else Color.Transparent).hoverable(source)
}

private fun LiveController.toggle(p: Panel) { panel = if (panel == p) Panel.NONE else p }
