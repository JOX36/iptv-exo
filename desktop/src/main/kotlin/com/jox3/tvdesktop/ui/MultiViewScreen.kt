package com.jox3.tvdesktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Category
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.Section
import com.jox3.tvdesktop.data.TvRepo
import com.jox3.tvdesktop.player.MultiView
import com.jox3.tvdesktop.player.PlayState
import kotlinx.coroutines.delay

/**
 * Multipantalla (solo PC): hasta 4 canales a la vez. Clic = ese suena; doble clic = agrandar;
 * Esc = volver a la cuadrícula o salir. Cada cuadro muestra el canal y lo que dan ahora (guía).
 */
@Composable
fun MultiViewScreen(live: LiveController, onExit: () -> Unit) {
    val repo = live.repo
    var picking by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(picking) {
        val prev = KeyRouter.handler
        KeyRouter.handler = handler@{ ev ->
            if (ev.type != KeyEventType.KeyDown) return@handler false
            if (Typing.active && ev.key != Key.Escape) return@handler false
            when (ev.key) {
                Key.Escape -> when {
                    picking != null -> picking = null
                    MultiView.expanded >= 0 -> MultiView.collapse()
                    else -> onExit()
                }
                Key.One, Key.NumPad1 -> MultiView.focusAudio(0)
                Key.Two, Key.NumPad2 -> MultiView.focusAudio(1)
                Key.Three, Key.NumPad3 -> MultiView.focusAudio(2)
                Key.Four, Key.NumPad4 -> MultiView.focusAudio(3)
                else -> return@handler false
            }
            true
        }
        onDispose { KeyRouter.handler = prev }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Barra de arriba: diseños, conexiones y salir
        Row(
            Modifier.fillMaxWidth().background(Jx.Bg).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Multipantalla", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("${MultiView.used()} de ${MultiView.limit()} · suena el resaltado · doble clic agranda",
                color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            MultiView.Layout.entries.forEach { l -> Chip(l.label, selected = MultiView.layout == l) { MultiView.setLayout(l) } }
            if (MultiView.maxConnections < MultiView.MAX)
                Text("Tu cuenta: ${MultiView.maxConnections} conexiones", color = Jx.Gold, fontFamily = Exo2, fontSize = 12.sp)
            GhostButton("Salir (Esc)") { onExit() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
            val ex = MultiView.expanded
            if (ex >= 0) {
                TileView(repo, ex, Modifier.fillMaxSize(), onPick = { picking = it })
            } else when (MultiView.layout) {
                MultiView.Layout.TWO -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TileView(repo, 0, Modifier.weight(1f).fillMaxHeight(), onPick = { picking = it })
                    TileView(repo, 1, Modifier.weight(1f).fillMaxHeight(), onPick = { picking = it })
                }
                MultiView.Layout.FOUR -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TileView(repo, 0, Modifier.weight(1f).fillMaxHeight(), onPick = { picking = it })
                        TileView(repo, 1, Modifier.weight(1f).fillMaxHeight(), onPick = { picking = it })
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TileView(repo, 2, Modifier.weight(1f).fillMaxHeight(), onPick = { picking = it })
                        TileView(repo, 3, Modifier.weight(1f).fillMaxHeight(), onPick = { picking = it })
                    }
                }
                MultiView.Layout.BIG -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TileView(repo, 0, Modifier.weight(3f).fillMaxHeight(), onPick = { picking = it })
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TileView(repo, 1, Modifier.weight(1f).fillMaxWidth(), small = true, onPick = { picking = it })
                        TileView(repo, 2, Modifier.weight(1f).fillMaxWidth(), small = true, onPick = { picking = it })
                        TileView(repo, 3, Modifier.weight(1f).fillMaxWidth(), small = true, onPick = { picking = it })
                    }
                }
            }
            picking?.let { slot ->
                ChannelPicker(repo, onPick = { ch ->
                    MultiView.put(slot, ch)?.let { live.notice(it) }
                    picking = null
                }, onClose = { picking = null })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileView(repo: TvRepo, i: Int, modifier: Modifier, small: Boolean = false, onPick: (Int) -> Unit) {
    val t = MultiView.tiles.getOrNull(i)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    if (t == null) {
        // Cuadro libre
        val full = MultiView.used() >= MultiView.limit()
        Box(
            modifier.clip(RoundedCornerShape(8.dp)).background(Jx.Bg)
                .border(1.dp, if (hovered) Jx.Accent else Jx.SurfaceHi, RoundedCornerShape(8.dp))
                .hoverable(source).clickable { onPick(i) }.pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("+", color = if (full) Jx.Faint else Jx.Accent, fontSize = if (small) 26.sp else 40.sp, fontFamily = Exo2)
                Text(if (full) "Tu cuenta no permite más conexiones" else "Agregar canal",
                    color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
            }
        }
        return
    }
    val ch = t.channel
    val p = t.player
    val isAudio = MultiView.audio == i
    // Guía del canal: se refresca cada minuto
    val version by repo.epgVersion
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) { value = repo.epg(ch) }
    val now = remember(epg, tick) { System.currentTimeMillis() }
    val cur = epg?.firstOrNull { now in it.start until it.end }
    val next = epg?.firstOrNull { it.start >= (cur?.end ?: now) }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black)
            .border(if (isAudio) 2.dp else 1.dp, if (isAudio) Jx.Accent else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .hoverable(source)
            .combinedClickable(onClick = { MultiView.focusAudio(i) }, onDoubleClick = { MultiView.toggleExpand(i) }),
    ) {
        VideoSurface(p, Modifier.fillMaxSize())
        when (val st = p.state) {
            PlayState.Opening, is PlayState.Buffering -> Text("Cargando…", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            is PlayState.Failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No carga este canal", color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
                Text(st.message.take(80), color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp)
            }
            else -> {}
        }
        // Arriba: canal y sonido
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3070A0F))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (ch.logo.isNotBlank()) RemoteImage(ch.logo, ch.name, Modifier.size(18.dp), scale = ContentScale.Fit)
            Text((if (isAudio) "🔊 " else "🔇 ") + ch.name, color = if (isAudio) Jx.Text else Jx.TextSoft, fontFamily = Exo2,
                fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (small) 160.dp else 320.dp))
        }
        // Al pasar el ratón: cambiar o quitar
        if (hovered) Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction("⇄") { onPick(i) }
            MiniAction("✕") { MultiView.remove(i) }
        }
        // Abajo: lo que dan ahora (guía), con su barrita
        if (cur != null || (hovered && next != null)) Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9070A0F))))
                .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp),
        ) {
            if (cur != null) {
                Text(cur.label, color = Jx.Text, fontFamily = Exo2, fontSize = if (small) 11.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!small) Text("${hhmm(cur.start)} – ${hhmm(cur.end)}", color = Jx.Muted, fontFamily = Exo2, fontSize = 11.sp)
                ProgressLine(cur.progress(now), Modifier.fillMaxWidth().padding(top = 4.dp), height = 2.dp)
            }
            if (hovered && next != null && !small) Text("Sigue ${hhmm(next.start)}: ${next.label}", color = Jx.Faint, fontFamily = Exo2,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MiniAction(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC070A0F))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Jx.Text, fontSize = 14.sp) }
}

/** Elegir canal para un cuadro: categoría a la izquierda, canales a la derecha, con buscador. */
@Composable
private fun ChannelPicker(repo: TvRepo, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val cats by produceState<List<Category>>(emptyList()) { value = runCatching { repo.shownCategories(Section.LIVE) }.getOrDefault(emptyList()) }
    var cat by remember { mutableStateOf<Category?>(null) }
    LaunchedEffect(cats) { if (cat == null) cat = cats.firstOrNull() }
    var filter by remember { mutableStateOf("") }
    val chans by produceState<List<Channel>>(emptyList(), cat) { value = cat?.let { c -> runCatching { repo.channels(c.id) }.getOrDefault(emptyList()) }.orEmpty() }
    val shown = remember(chans, filter) {
        val q = TvRepo.normalize(filter.trim())
        if (q.isEmpty()) chans else chans.filter { TvRepo.normalize(it.name).contains(q) }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 860.dp).fillMaxWidth(0.9f).fillMaxHeight(0.85f).clip(RoundedCornerShape(16.dp)).background(Jx.Rail)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Elegir canal", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                GhostButton("Cerrar") { onClose() }
            }
            GateField("Buscar en la categoría", filter, { filter = it }, "Nombre del canal")
            Row(Modifier.weight(1f)) {
                LazyColumn(Modifier.width(240.dp).fillMaxHeight()) {
                    items(cats, key = { it.id }) { c ->
                        val sel = c.id == cat?.id
                        Text(c.name, color = if (sel) Jx.Accent else Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) Jx.SurfaceHi else Color.Transparent)
                                .clickable { cat = c }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                    items(shown, key = { it.id }) { ch ->
                        val taken = MultiView.tiles.any { it?.channel?.id == ch.id }
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !taken) { onPick(ch) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RemoteImage(ch.logo, ch.name, Modifier.size(32.dp), scale = ContentScale.Fit)
                            Text(ch.name, color = if (taken) Jx.Faint else Jx.Text, fontFamily = Exo2, fontSize = 14.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (taken) Text("ya está", color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
