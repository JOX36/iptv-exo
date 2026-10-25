package com.jox3.tvdesktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.EpgItem
import kotlinx.coroutines.delay

private const val SLOT_MIN = 30
private val PX_PER_MIN: Dp = 6.dp
private val ROW_H: Dp = 58.dp
private val CHANNEL_COL: Dp = 230.dp

/**
 * Guía por horas (parrilla): canales en filas y la programación a lo largo del tiempo.
 * ◀ ▶ o Mayús + rueda del mouse para moverse en el tiempo, "Ahora" para volver.
 * Clic en un canal o programa = verlo en pantalla completa.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveGuideGrid(live: LiveController, channels: List<Channel>, title: String, onPlay: (Channel) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    // Inicio de la parrilla: media hora antes de la hora actual redondeada
    val base = remember { (System.currentTimeMillis() / (SLOT_MIN * 60_000L)) * (SLOT_MIN * 60_000L) - SLOT_MIN * 60_000L }
    var offsetMin by remember { mutableLongStateOf(0L) }
    val start = base + offsetMin * 60_000L

    Column(Modifier.fillMaxSize().padding(top = 16.dp, start = 12.dp, end = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.removePrefix("★  "), color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RoundButton(Ico.PREV, "Antes (Mayús + rueda)", small = true) { offsetMin = (offsetMin - 60).coerceAtLeast(-120) }
            Spacer(Modifier.width(6.dp))
            PillButton("Ahora") { offsetMin = 0 }
            Spacer(Modifier.width(6.dp))
            RoundButton(Ico.NEXT, "Después (Mayús + rueda)", small = true) { offsetMin = (offsetMin + 60).coerceAtMost(20 * 60) }
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineW = maxWidth - CHANNEL_COL - 14.dp
            val spanMin = (timelineW / PX_PER_MIN).toLong().coerceAtLeast(60)
            Column(
                Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll) { ev ->
                    val c = ev.changes.firstOrNull() ?: return@onPointerEvent
                    val dx = c.scrollDelta.x
                    val horizontal = if (dx != 0f) dx else if (ev.keyboardModifiers.isShiftPressed) c.scrollDelta.y else 0f
                    if (horizontal != 0f) {
                        offsetMin = (offsetMin + (if (horizontal > 0) 30 else -30)).coerceIn(-120, 20 * 60)
                        c.consume()
                    }
                }
            ) {
                // Encabezado de horas
                Row(Modifier.fillMaxWidth().height(30.dp)) {
                    Spacer(Modifier.width(CHANNEL_COL))
                    Box(Modifier.width(timelineW).fillMaxHeight().clipToBounds()) {
                        var t = start
                        while (t < start + spanMin * 60_000L) {
                            val x = PX_PER_MIN * ((t - start) / 60_000L).toFloat()
                            Text(hhmm(t), color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp, modifier = Modifier.offset(x = x + 6.dp, y = 6.dp))
                            t += SLOT_MIN * 60_000L
                        }
                    }
                }
                val state = rememberLazyListState()
                LaunchedEffect(channels) {
                    val i = channels.indexOfFirst { it.id == live.current?.id }
                    if (i > 3) state.scrollToItem(i - 3)
                }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        items(channels, key = { it.id }) { ch ->
                            GuideRow(live, ch, start, spanMin, timelineW, now, onPlay)
                        }
                    }
                    // Línea de "ahora"
                    val nowX = PX_PER_MIN * ((now - start) / 60_000f)
                    if (now in start until start + spanMin * 60_000L) {
                        Box(Modifier.offset(x = CHANNEL_COL + nowX).width(2.dp).fillMaxHeight().background(Jx.Accent.copy(alpha = 0.8f)))
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun GuideRow(live: LiveController, ch: Channel, start: Long, spanMin: Long, timelineW: Dp, now: Long, onPlay: (Channel) -> Unit) {
    val repo = live.repo
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) {
        if (value.isNullOrEmpty()) { delay(200); value = repo.epg(ch) }
    }
    val end = start + spanMin * 60_000L
    val playing = live.current?.id == ch.id
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(vertical = 2.dp)) {
        // Canal
        Row(
            Modifier.width(CHANNEL_COL - 6.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                .background(if (playing) Jx.SurfaceHi else Jx.Surface)
                .clickable { onPlay(ch) }.pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${ch.num}", color = if (playing) Jx.Accent else Jx.Faint, fontFamily = Exo2, fontSize = 12.sp, modifier = Modifier.width(34.dp))
            RemoteImage(ch.logo, ch.name, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(8.dp))
            Text(ch.name, color = if (playing) Jx.Accent else Jx.Text, fontFamily = Exo2, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        // Programas
        Box(Modifier.width(timelineW).fillMaxHeight().clipToBounds()) {
            val items = epg.orEmpty().filter { it.end > start && it.start < end }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Jx.Rail).clickable { onPlay(ch) }
                    .pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.CenterStart) {
                    Text(if (epg == null) "Cargando…" else "Sin guía", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            items.forEach { p -> ProgramBlock(p, start, end, now) { onPlay(ch) } }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProgramBlock(p: EpgItem, start: Long, end: Long, now: Long, onClick: () -> Unit) {
    val from = maxOf(p.start, start)
    val to = minOf(p.end, end)
    val x = PX_PER_MIN * ((from - start) / 60_000f)
    val w = (PX_PER_MIN * ((to - from) / 60_000f) - 3.dp).coerceAtLeast(4.dp)
    val airing = now in p.start until p.end
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Column(Modifier.width(320.dp).background(Jx.SurfaceHi, RoundedCornerShape(8.dp)).padding(10.dp)) {
                if (p.icon.isNotBlank()) {
                    RemoteImage(p.icon, p.title, Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(6.dp)),
                        scale = androidx.compose.ui.layout.ContentScale.Crop)
                    Spacer(Modifier.height(6.dp))
                }
                Text(p.label, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${hhmm(p.start)} – ${hhmm(p.end)}", color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp)
                if (p.meta.isNotBlank()) Text(p.meta, color = Jx.Accent, fontFamily = Exo2, fontSize = 12.sp)
                if (p.description.isNotBlank()) Text(p.description, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 12.sp, maxLines = 6)
            }
        },
        delayMillis = 400,
        modifier = Modifier.offset(x = x),
    ) {
        Column(
            Modifier.width(w).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                .background(if (airing) Jx.SurfaceHi else Jx.Surface)
                .then(if (hovered) Modifier.border(1.dp, Jx.Accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp)) else Modifier)
                .hoverable(source)
                .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(p.label, color = if (airing) Jx.Text else Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp,
                fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${hhmm(p.start)} – ${hhmm(p.end)}", color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp, maxLines = 1)
            if (airing) {
                Spacer(Modifier.height(3.dp))
                ProgressLine(p.progress(now), Modifier.fillMaxWidth(), height = 2.dp)
            }
        }
    }
}

