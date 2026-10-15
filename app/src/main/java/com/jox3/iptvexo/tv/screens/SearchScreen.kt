package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.Nav
import com.jox3.iptvexo.tv.Route
import com.jox3.iptvexo.tv.TvHost
import com.jox3.iptvexo.tv.player.LocalLive
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.Series
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.ChannelTile
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.ShelfRow
import com.jox3.iptvexo.tv.ui.StatusBox
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val KEYS = "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ0123456789".map { it.toString() }

private data class Results(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/**
 * Búsqueda con teclado en pantalla: con el control es mucho más rápido que el
 * teclado de Android. Busca en canales, películas y series a la vez, sin
 * importar tildes ni mayúsculas.
 */
@Composable
fun SearchScreen(repo: TvRepo, nav: Nav, host: TvHost) {
    val live = LocalLive.current
    var query by rememberSaveable { mutableStateOf("") }
    val index by produceState<TvRepo.Index?>(null) { value = repo.searchIndex() }
    val results by produceState<Results?>(null, query, index) {
        val idx = index ?: return@produceState
        val q = TvRepo.normalize(query.trim())
        if (q.length < 2) { value = null; return@produceState }
        delay(220)   // esperar a que termine de escribir (si pulsa otra letra, esto se cancela)
        value = withContext(Dispatchers.Default) {
            // Los nombres ya vienen normalizados en el índice: solo se compara texto
            fun <T> pick(list: List<T>, keys: List<String>): List<T> {
                val out = ArrayList<T>(40)
                for (i in keys.indices) { if (keys[i].contains(q)) { out.add(list[i]); if (out.size == 40) break } }
                return out
            }
            Results(pick(idx.channels, idx.channelKeys), pick(idx.movies, idx.movieKeys), pick(idx.series, idx.seriesKeys))
        }
    }
    val firstKey = remember { FocusRequester() }
    val lib = repo.library
    // Si se queda unos segundos en una búsqueda con resultados, se guarda en el historial
    LaunchedEffect(results) {
        val r = results ?: return@LaunchedEffect
        if (r.isEmpty) return@LaunchedEffect
        delay(3000)
        lib.addSearch(query)
    }

    Row(Modifier.fillMaxSize()) {
        // ── Teclado ──
        Column(Modifier.width(300.dp).fillMaxHeight().background(Jx.Rail).padding(horizontal = 20.dp, vertical = 27.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("BUSCAR", style = Type.Eyebrow, color = Jx.Muted)
            Box(Modifier.fillMaxWidth().height(44.dp).background(Jx.Surface, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    JxIcon(R.drawable.tv_ic_search, 16.dp, Jx.Muted)
                    Text(query.ifEmpty { "Escribe con el teclado…" }, style = Type.Label,
                        color = if (query.isEmpty()) Jx.Faint else Jx.Text, maxLines = 1)
                }
            }
            KEYS.chunked(6).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEachIndexed { c, k ->
                        Key(k, Modifier.weight(1f).let { if (r == 0 && c == 0) it.focusRequester(firstKey) else it }) { query += k.lowercase() }
                    }
                    repeat(6 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Key("Espacio", Modifier.weight(1f)) { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " }
                Key("Borrar", Modifier.weight(1f)) { query = query.dropLast(1) }
                Key("Limpiar", Modifier.weight(1f)) { query = "" }
            }
        }

        // ── Resultados ──
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val r = results
            when {
                index == null -> StatusBox("Preparando la búsqueda en todo el catálogo…")
                query.trim().length < 2 -> SearchHistory(
                    lib.searchHistory.toList(),
                    onPick = { query = it },
                    onRemove = { lib.removeSearch(it) },
                    onClear = { lib.clearSearches() },
                )
                r == null -> StatusBox("Buscando…")
                r.isEmpty -> StatusBox("Sin resultados para “${query.trim()}”")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 27.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (r.channels.isNotEmpty()) item(key = "ch") {
                        ShelfRow("En vivo · ${r.channels.size}") {
                            itemsIndexed(r.channels, key = { _, c -> "c" + c.id }) { i, c ->
                                ChannelTile(c.name, c.logo, onClick = { lib.addSearch(query); live.playFullscreen(r.channels, i, "Búsqueda") })
                            }
                        }
                    }
                    if (r.movies.isNotEmpty()) item(key = "mv") {
                        ShelfRow("Películas · ${r.movies.size}") {
                            items(r.movies, key = { "m" + it.id }) { m ->
                                PosterCard(m.name, m.poster, onClick = { lib.addSearch(query); nav.open(Route.MovieDetail(m)) }, width = 96.dp,
                                    favorite = lib.isFavorite("movie", m.id))
                            }
                        }
                    }
                    if (r.series.isNotEmpty()) item(key = "sr") {
                        ShelfRow("Series · ${r.series.size}") {
                            items(r.series, key = { "s" + it.id }) { s ->
                                PosterCard(s.name, s.poster, onClick = { lib.addSearch(query); nav.open(Route.SeriesDetail(s.id, s.name, s.poster)) },
                                    width = 96.dp, favorite = lib.isFavorite("series", s.id))
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { firstKey.requestFocus() } }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.1f,
        container = Jx.Surface,
        focusedContainer = Jx.Accent,
        content = Jx.TextSoft,
        focusedContent = Jx.OnAccent,
        ring = false,
    ) {
        Text(label, style = if (label.length == 1) Type.Number else Type.Small, modifier = Modifier.align(Alignment.Center))
    }
}

/** Búsquedas recientes: OK la repite, OK mantenido la borra. */
@Composable
private fun SearchHistory(items: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (items.isEmpty()) {
        StatusBox("Escribe al menos 2 letras para buscar canales, películas y series.\nTus búsquedas aparecerán aquí.")
        return
    }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 40.dp, top = 27.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BÚSQUEDAS RECIENTES", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.weight(1f))
            Text("OK: buscar otra vez · OK mantenido: borrar", style = Type.Small, color = Jx.Faint)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items, key = { it }) { q ->
                FocusSurface(
                    onClick = { onPick(q) },
                    onLongClick = { onRemove(q) },
                    modifier = Modifier.width(420.dp).height(44.dp),
                    focusedScale = 1.02f,
                    container = Jx.Surface,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JxIcon(R.drawable.tv_ic_search, 14.dp)
                        Text(q, style = Type.Label, maxLines = 1)
                    }
                }
            }
            item(key = "__clear") {
                FocusSurface(onClick = onClear, modifier = Modifier.padding(top = 6.dp).height(40.dp), focusedScale = 1.02f) {
                    Text("Borrar historial", style = Type.Label, color = Jx.Live,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp))
                }
            }
        }
    }
}
