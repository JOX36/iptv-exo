package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.jox3.iptvexo.tv.ui.safeFocusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.EpgItem
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.player.LocalLive
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.ThinProgress
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Guía por horas (traída de JOX3 Verifier): rejilla canal × hora de la categoría abierta.
private const val HOURS = 6
private const val HALF_HOUR = 1_800_000L
private val PER_MIN = 4.dp          // ancho de un minuto en la línea de tiempo
private val CH_W = 196.dp           // columna fija de canales
private val ROW_H = 60.dp
private val timeFmt = SimpleDateFormat("h:mm a", Locale("es"))
private fun hhmm(ms: Long) = if (ms > 0) timeFmt.format(Date(ms)) else ""

/**
 * Desplazamiento horizontal de la línea de tiempo: los programas largos (más
 * anchos que la pantalla) no hacen saltar la guía si ya se ven en parte; los
 * cortos se mantienen lejos de los bordes, como en el resto de la app.
 */
@OptIn(ExperimentalFoundationApi::class)
private val TimelineScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        val lead = containerSize * 0.12f
        val trail = containerSize * 0.88f
        return if (size >= trail - lead) {
            when {
                offset < trail && end > lead -> 0f       // ya se ve un buen trozo
                offset >= trail -> offset - lead
                else -> end - trail
            }
        } else when {
            offset < lead -> offset - lead
            end > trail -> end - trail
            else -> 0f
        }
    }
}

/**
 * Arriba: vista previa + detalle del programa enfocado. Abajo: la rejilla.
 * OK en un canal o programa → se ve en la vista previa; OK otra vez → pantalla completa.
 * OK mantenido → favorito.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveGuide(
    repo: TvRepo,
    list: List<Channel>,
    group: String,
    listState: LazyListState,
    listReq: FocusRequester,
    playingReq: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val live = LocalLive.current
    val lib = repo.library
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowMs = System.currentTimeMillis() } }
    // La ventana empieza media hora antes de la media hora actual y dura 6 h
    val start = (nowMs / HALF_HOUR) * HALF_HOUR - HALF_HOUR
    val end = start + HOURS * 3_600_000L
    val totalW = PER_MIN * (HOURS * 60).toFloat()
    val hScroll = rememberScrollState()
    var focused by remember { mutableStateOf<Pair<Channel, EpgItem?>?>(null) }

    Column(modifier.fillMaxHeight().padding(top = 27.dp)) {
        // ── Vista previa + detalle ──
        Row(Modifier.fillMaxWidth().height(168.dp).padding(start = 16.dp, end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Jx.Surface)
                    .onGloballyPositioned { live.previewBounds = it.boundsInRoot() },
                contentAlignment = Alignment.Center,
            ) {
                if (live.current == null) Text("Pulsa OK en un canal", style = Type.Small, color = Jx.Faint)
            }
            ProgramInfo(focused, repo, nowMs, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(12.dp))

        // ── Cabecera de horas ──
        Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 10.dp)) {
            Text("Hoy", style = Type.Label, color = Jx.Text, modifier = Modifier.width(CH_W).padding(start = 8.dp))
            Row(Modifier.weight(1f).horizontalScroll(hScroll, enabled = false)) {
                var t = start
                while (t < end) {
                    Text(hhmm(t), style = Type.Small, color = Jx.Muted,
                        modifier = Modifier.width(PER_MIN * 30f).padding(start = 6.dp))
                    t += HALF_HOUR
                }
            }
        }

        // ── Rejilla ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().focusRequester(listReq).safeFocusRestorer(),
                state = listState,
                contentPadding = PaddingValues(start = 10.dp, bottom = 27.dp),
            ) {
                itemsIndexed(list, key = { _, c -> c.id }) { _, ch ->
                    val playing = live.current?.id == ch.id
                    GuideRow(
                        ch, repo, start, end, nowMs, totalW, hScroll,
                        playing = playing,
                        favorite = lib.isFavorite("live", ch.id),
                        chModifier = if (playing) Modifier.focusRequester(playingReq) else Modifier,
                        onFocus = { p -> focused = ch to p },
                        onClick = { if (playing) live.enterFullscreen() else live.play(ch, list, group) },
                        onLongClick = { lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId)) },
                    )
                }
            }
            // Línea de "ahora"
            val density = LocalDensity.current
            val xPx = with(density) { (10.dp + CH_W + PER_MIN * ((nowMs - start) / 60_000f)).toPx() } - hScroll.value
            val minPx = with(density) { (10.dp + CH_W).toPx() }
            if (xPx >= minPx) {
                Box(Modifier.offset { IntOffset(xPx.roundToInt(), 0) }.width(2.dp).fillMaxHeight().background(Jx.Live))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    ch: Channel, repo: TvRepo, start: Long, end: Long, nowMs: Long, totalW: androidx.compose.ui.unit.Dp,
    hScroll: ScrollState, playing: Boolean, favorite: Boolean, chModifier: Modifier,
    onFocus: (EpgItem?) -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val version by repo.epgVersion
    // La guía de cada canal se pide solo cuando su fila aparece un instante en pantalla
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) {
        if (value.isNullOrEmpty()) { delay(250); value = repo.epg(ch) }
    }
    val visible = epg.orEmpty().filter { it.end > start && it.start < end }

    Row(Modifier.fillMaxWidth().height(ROW_H).padding(vertical = 3.dp)) {
        var chFocused by remember { mutableStateOf(false) }
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = chModifier.width(CH_W - 6.dp).fillMaxHeight().onFocusChanged {
                chFocused = it.isFocused
                if (it.isFocused) onFocus(epg?.let { l -> repo.nowPlaying(l) })
            },
            focusedScale = 1f,
            container = if (playing) Jx.Surface else Color.Transparent,
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${ch.num}", style = Type.Small, color = if (chFocused || playing) Jx.Accent else Jx.Faint,
                    modifier = Modifier.width(26.dp))
                RemoteImage(ch.logo, ch.name, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)), scale = ContentScale.Fit)
                Text(ch.name, style = Type.Label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (playing) Jx.Accent else androidx.tv.material3.LocalContentColor.current,
                    modifier = Modifier.weight(1f))
                if (favorite) JxIcon(R.drawable.tv_ic_star_filled, 12.dp, Jx.Gold, "Favorito")
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides TimelineScroll) {
                Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                    Box(Modifier.width(totalW).fillMaxHeight()) {
                        visible.forEach { p ->
                            val a = maxOf(p.start, start)
                            val b = minOf(p.end, end)
                            val left = PER_MIN * ((a - start) / 60_000f)
                            val w = (PER_MIN * ((b - a) / 60_000f) - 4.dp).coerceAtLeast(18.dp)
                            val isNow = nowMs in p.start until p.end
                            val past = p.end <= nowMs
                            FocusSurface(
                                onClick = onClick,
                                onLongClick = onLongClick,
                                modifier = Modifier.offset(x = left).width(w).fillMaxHeight()
                                    .onFocusChanged { if (it.isFocused) onFocus(p) },
                                shape = RoundedCornerShape(8.dp),
                                focusedScale = 1f,
                                container = if (isNow) Jx.Accent.copy(alpha = 0.16f) else Jx.Surface,
                                content = if (past) Jx.Faint else Jx.TextSoft,
                            ) {
                                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    Text(p.title, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(hhmm(p.start), style = Type.Small, maxLines = 1,
                                        color = if (isNow) Jx.Accent else Jx.Faint)
                                }
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                Text(if (epg == null) "Cargando guía…" else "Sin guía para estas horas", style = Type.Small, color = Jx.Faint,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            }
        }
    }
}

/** Detalle del canal / programa enfocado en la rejilla. */
@Composable
private fun ProgramInfo(sel: Pair<Channel, EpgItem?>?, repo: TvRepo, nowMs: Long, modifier: Modifier) {
    val live = LocalLive.current
    val ch = sel?.first ?: live.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (ch == null) {
            Text("Guía por horas", style = Type.H1, color = Jx.Text)
            Text("Muévete por la rejilla para ver qué dan en cada canal.", style = Type.Body, color = Jx.Muted)
            return@Column
        }
        val p = sel?.second ?: repo.cachedEpg(ch)?.let { repo.nowPlaying(it) }
        Text(ch.name, style = Type.Label, color = Jx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (p == null) {
            Text("Sin guía para este canal", style = Type.Body, color = Jx.Faint)
        } else {
            val isNow = nowMs in p.start until p.end
            val past = p.end <= nowMs
            Text(
                (if (isNow) "AHORA · " else if (past) "YA TERMINÓ · " else "") + "${hhmm(p.start)} – ${hhmm(p.end)}",
                style = Type.Eyebrow, color = if (isNow) Jx.Accent else Jx.Muted,
            )
            Text(p.title, style = Type.H1, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (isNow) ThinProgress(p.progress(), Modifier.fillMaxWidth().padding(vertical = 2.dp), height = 3.dp)
            if (p.description.isNotBlank()) {
                Text(p.description, style = Type.Small, color = Jx.TextSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        val playing = live.current?.id == ch.id
        Text(if (playing) "OK: ver en pantalla completa" else "OK: ver en la vista previa",
            style = Type.Small, color = if (playing) Jx.Accent else Jx.Faint)
    }
}
