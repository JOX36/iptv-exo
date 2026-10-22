package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.ServerHub
import kotlinx.coroutines.delay

/**
 * Buscador universal: canales, películas y series de TODOS los servidores guardados.
 * Cada resultado muestra en qué servidores está (A, B, C…). Si está en varios, se elige cuál.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(live: LiveController, onOpen: (ServerHub.Copy) -> Unit) {
    val repo = live.repo
    val lib = repo.library
    val servers = LocalServers.current ?: return
    var query by remember { mutableStateOf("") }
    var only by remember { mutableStateOf<String?>(null) }
    var searchAll by remember { mutableStateOf(ServerHub.searchAllEnabled()) }
    var choose by remember { mutableStateOf<ServerHub.Hit?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Catálogo del servidor actual (se arma una vez por sesión)
    val index by produceState(repo.cachedIndex()) { if (value == null) value = runCatching { repo.searchIndex() }.getOrNull() }
    val idxVersion by ServerHub.indexVersion
    val results by produceState<ServerHub.Results?>(null, query, only, index, idxVersion, searchAll) {
        val q = query.trim()
        val idx = index
        if (q.length < 2 || idx == null) { value = null; return@produceState }
        delay(250) // espera a que termine de escribir
        value = ServerHub.search(q, servers.account, idx, servers.all, only, includeOthers = searchAll)
    }

    fun open(hit: ServerHub.Hit) {
        lib.addSearch(query)
        if (hit.copies.size == 1) onOpen(hit.copies[0]) else choose = hit
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 32.dp)) {
            item {
                // Campo de búsqueda grande
                Row(
                    Modifier.fillMaxWidth().widthIn(max = 900.dp).height(54.dp).clip(RoundedCornerShape(14.dp))
                        .background(Jx.Surface).border(1.dp, Jx.Line, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JxIcon(Ico.SEARCH, 20.dp, Jx.Accent)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Buscar canales, películas y series…", color = Jx.Faint, fontFamily = Exo2, fontSize = 17.sp)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Jx.Text, fontFamily = Exo2, fontSize = 17.sp),
                            cursorBrush = SolidColor(Jx.Accent),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                                .onFocusChanged { Typing.active = it.isFocused }
                                .onPreviewKeyEvent {
                                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                                        lib.addSearch(query); true
                                    } else false
                                },
                        )
                    }
                    if (query.isNotEmpty()) RoundButton(Ico.CLOSE, "Borrar", small = true) { query = "" }
                }
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Typing.active = false } }
                Spacer(Modifier.height(14.dp))

                // Servidores
                if (servers.all.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Todos los servidores", selected = only == null) { only = null }
                        servers.all.forEach { acc ->
                            val st = ServerHub.status[acc.key]
                            Chip("${ServerHub.letter(servers.all, acc)} · ${ServerHub.name(servers.all, acc)}", selected = only == acc.key,
                                dot = levelColor(st?.level)) { only = if (only == acc.key) null else acc.key }
                        }
                        Chip(if (searchAll) "Buscar en todos: sí" else "Buscar en todos: no", selected = false) {
                            searchAll = !searchAll; ServerHub.setSearchAll(searchAll)
                            if (searchAll) ServerHub.ensureIndexes(servers.all.filter { it.key != servers.account.key })
                        }
                    }
                    val loading = ServerHub.loadingIndex.filterValues { it }.keys
                    if (searchAll && loading.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Preparando el catálogo de ${loading.size} servidor${if (loading.size > 1) "es" else ""} más… los resultados irán apareciendo.",
                            color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            val q = query.trim()
            when {
                index == null -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 20.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Jx.Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Preparando el catálogo para buscar (solo la primera vez)…", color = Jx.Muted, fontFamily = Exo2)
                    }
                }
                q.length < 2 -> item { History(lib.searchHistory, onPick = { query = it }, onRemove = { lib.removeSearch(it) }, onClear = { lib.clearSearches() }) }
                results == null -> item { Text("Buscando…", color = Jx.Faint, fontFamily = Exo2, modifier = Modifier.padding(vertical = 20.dp)) }
                results!!.isEmpty -> item { Text("No se encontró \"$q\"", color = Jx.Muted, fontFamily = Exo2, fontSize = 15.sp, modifier = Modifier.padding(vertical = 20.dp)) }
                else -> {
                    val r = results!!
                    if (r.channels.isNotEmpty()) {
                        item { SectionTitle("Canales", r.channels.size) }
                        items(r.channels, key = { "c" + it.key }) { h -> ChannelHit(h, servers) { open(h) } }
                    }
                    if (r.movies.isNotEmpty()) {
                        item { SectionTitle("Películas", r.movies.size) }
                        item { PosterRow(r.movies, servers) { open(it) } }
                    }
                    if (r.series.isNotEmpty()) {
                        item { SectionTitle("Series", r.series.size) }
                        item { PosterRow(r.series, servers) { open(it) } }
                    }
                }
            }
        }

        // Elegir servidor cuando el título está en varios
        choose?.let { hit ->
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { choose = null }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 460.dp).background(Jx.Surface, RoundedCornerShape(16.dp)).border(1.dp, Jx.Line, RoundedCornerShape(16.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                        .padding(20.dp)
                ) {
                    Text(hit.name, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 2)
                    Text("Está en ${hit.copies.size} lugares. ¿Cuál abres?", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    hit.copies.forEach { c ->
                        val here = c.account.key == servers.account.key
                        ListRow(false, onClick = { choose = null; onOpen(c) }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Letter(ServerHub.letter(servers.all, c.account), here)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, color = Jx.Text, fontFamily = Exo2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(ServerHub.name(servers.all, c.account) + if (here) " (en uso)" else "", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
                                }
                                if (c.tag.isNotBlank()) Pill(c.tag, Jx.Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun History(list: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (list.isEmpty()) {
        Text("Escribe al menos 2 letras. Se busca sin importar tildes ni mayúsculas.", color = Jx.Faint, fontFamily = Exo2, fontSize = 13.sp)
        return
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Búsquedas recientes", color = Jx.Muted, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text("Borrar", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClear).pointerHoverIcon(PointerIcon.Hand).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { q ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Jx.Surface).clickable { onPick(q) }
                        .pointerHoverIcon(PointerIcon.Hand).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JxIcon(Ico.CLOCK, 13.dp, Jx.Faint)
                    Spacer(Modifier.width(8.dp))
                    Text(q, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(20.dp).clip(CircleShape).clickable { onRemove(q) }, contentAlignment = Alignment.Center) {
                        JxIcon(Ico.CLOSE, 9.dp, Jx.Faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text("$count", color = Jx.Faint, fontFamily = Exo2, fontSize = 13.sp)
    }
}

@Composable
private fun ChannelHit(h: ServerHub.Hit, servers: ServerCtx, onClick: () -> Unit) {
    ListRow(false, onClick) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(h.image, h.name, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(12.dp))
            Text(h.name, color = Jx.Text, fontFamily = Exo2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ServerLetters(h, servers)
        }
    }
}

@Composable
private fun PosterRow(hits: List<ServerHub.Hit>, servers: ServerCtx, onClick: (ServerHub.Hit) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(hits, key = { it.key }) { h ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Column(Modifier.width(140.dp).hoverable(source).clickable { onClick(h) }.pointerHoverIcon(PointerIcon.Hand)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                    .then(if (hovered) Modifier.border(2.dp, Jx.Accent, RoundedCornerShape(12.dp)) else Modifier)) {
                    PosterImage(h.image, h.name, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { ServerLetters(h, servers) }
                }
                Spacer(Modifier.height(6.dp))
                Text(h.name, color = if (hovered) Jx.Accent else Jx.Text, fontFamily = Exo2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Letras de los servidores donde está el resultado (el actual resaltado). */
@Composable
private fun ServerLetters(h: ServerHub.Hit, servers: ServerCtx) {
    if (servers.all.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        h.servers.forEach { acc -> Letter(ServerHub.letter(servers.all, acc), acc.key == servers.account.key) }
    }
}

@Composable
fun Letter(letter: String, current: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(if (current) Jx.Accent else Color(0xCC111923))
            .border(1.dp, if (current) Jx.Accent else Jx.Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = if (current) Jx.OnAccent else Jx.TextSoft, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
fun Chip(label: String, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Jx.Accent else Jx.Surface)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(8.dp)) }
        Text(label, color = if (selected) Jx.OnAccent else Jx.TextSoft, fontFamily = Exo2, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
    }
}

/** Verde: bien · Naranja: lento o por vencer · Rojo: no responde o vencido · Gris: sin revisar. */
fun levelColor(level: Int?): Color = when (level) {
    0 -> Jx.Ok
    1 -> Jx.Gold
    2 -> Jx.Live
    else -> Jx.Faint
}

/** Carátula; si no hay o está rota, se busca en TMDB. */
@Composable
fun PosterImage(url: String, name: String, modifier: Modifier) {
    val shown by produceState(url, url, name) {
        val ok = url.isNotBlank() && Images.load(url) != null
        if (!ok) com.jox3.tvdesktop.data.Tmdb.posterFor(name).takeIf { it.isNotBlank() }?.let { value = it }
    }
    RemoteImage(shown, name, modifier, scale = ContentScale.Crop)
}
