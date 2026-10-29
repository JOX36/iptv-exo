package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.jox3.iptvexo.tv.ui.rememberFocusMemory
import com.jox3.iptvexo.tv.ui.focusMemoryItem
import com.jox3.iptvexo.tv.ui.focusMemoryGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.tv.Nav
import com.jox3.iptvexo.tv.Route
import com.jox3.iptvexo.tv.data.Category
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.Section
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.StatusBox
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay

/** Elemento de la grilla, común a películas y series. */
private data class GridItem(val id: String, val name: String, val poster: String, val rating: String, val open: () -> Unit, val fav: SavedItem)

@Composable
fun MoviesScreen(repo: TvRepo, nav: Nav) = CatalogScreen(repo, Section.VOD, "Películas") { catId ->
    repo.movies(catId).map { m ->
        GridItem(m.id, m.name, m.poster, m.rating, { nav.open(Route.MovieDetail(m)) },
            SavedItem("movie", m.id, m.name, m.poster, ext = m.ext, categoryId = m.categoryId))
    }
}

@Composable
fun SeriesScreen(repo: TvRepo, nav: Nav) = CatalogScreen(repo, Section.SERIES, "Series") { catId ->
    repo.series(catId).map { s ->
        GridItem(s.id, s.name, s.poster, s.rating, { nav.open(Route.SeriesDetail(s.id, s.name, s.poster)) },
            SavedItem("series", s.id, s.name, s.poster, categoryId = s.categoryId))
    }
}

/** Categorías a la izquierda + grilla de pósters. */
@Composable
private fun CatalogScreen(repo: TvRepo, section: Section, title: String, load: suspend (String) -> List<GridItem>) {
    val lib = repo.library
    var reload by remember { mutableIntStateOf(0) }
    val catV = lib.catVersion.intValue
    val cats by produceState<Result<List<Category>>?>(null, reload, catV) { value = runCatching { repo.shownCategories(section) } }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val gridReq = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }
    val catMem = rememberFocusMemory()
    val gridMem = rememberFocusMemory()

    val catList = cats?.getOrNull()
    LaunchedEffect(catList) { if (selectedId == null) selectedId = catList?.firstOrNull()?.id }
    // Al volver a las categorías, el foco cae en la que está abierta
    LaunchedEffect(selectedId) { catMem.remember(selectedId) }
    val selected = catList?.firstOrNull { it.id == selectedId }

    val items by produceState<Result<List<GridItem>>?>(null, selectedId, reload) {
        val id = selectedId ?: return@produceState
        value = null
        value = runCatching { load(id) }
    }

    if (cats == null) { StatusBox("Cargando $title…"); return }
    if (catList == null) { StatusBox("No se pudieron cargar las categorías", onRetry = { reload++ }); return }
    if (catList.isEmpty()) { StatusBox("Esta cuenta no tiene $title"); return }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(210.dp).fillMaxHeight().background(Jx.Rail).padding(top = 27.dp)) {
            Text(title.uppercase(), style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            LazyColumn(
                Modifier.fillMaxSize().focusMemoryGroup(catMem),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 27.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(catList, key = { it.id }) { c ->
                    val isSel = c.id == selectedId
                    var focused by remember { mutableStateOf(false) }
                    LaunchedEffect(focused) { if (focused && !isSel) { delay(350); selectedId = c.id } }
                    FocusSurface(
                        onClick = { selectedId = c.id; runCatching { gridReq.requestFocus() } },
                        modifier = Modifier.fillMaxWidth().height(38.dp).focusMemoryItem(catMem, c.id)
                            .onFocusChanged { focused = it.isFocused },
                        focusedScale = 1f,
                        container = if (isSel) Jx.SurfaceHi else Color.Transparent,
                        content = if (isSel) Jx.Accent else Jx.TextSoft,
                    ) {
                        Text(c.name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 27.dp)) {
            val list = items?.getOrNull()
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 40.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(selected?.name.orEmpty(), style = Type.H1, color = Jx.Text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (list != null) Text("${list.size} títulos", style = Type.Label, color = Jx.Faint)
            }
            when {
                items == null -> StatusBox("Cargando…")
                list == null -> StatusBox("No se pudo cargar la categoría", onRetry = { reload++ })
                list.isEmpty() -> StatusBox("Categoría vacía")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    modifier = Modifier.fillMaxSize().focusRequester(gridReq).focusMemoryGroup(gridMem),
                    contentPadding = PaddingValues(start = 28.dp, end = 40.dp, top = 12.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(list, key = { it.id }) { g ->
                        PosterCard(
                            g.name, g.poster, onClick = g.open,
                            modifier = Modifier.fillMaxWidth().focusMemoryItem(gridMem, g.id),
                            width = 112.dp,
                            subtitle = g.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                            favorite = lib.isFavorite(g.fav.kind, g.id),
                            onLongClick = { lib.toggleFavorite(g.fav) },
                        )
                    }
                }
            }
            LaunchedEffect(list) {
                if (!firstFocusDone && !list.isNullOrEmpty()) {
                    firstFocusDone = true
                    delay(50); runCatching { gridReq.requestFocus() }
                }
            }
        }
    }
}
