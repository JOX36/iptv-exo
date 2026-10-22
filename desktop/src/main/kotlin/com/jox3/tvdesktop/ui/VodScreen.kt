package com.jox3.tvdesktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.core.Settings
import com.jox3.tvdesktop.data.Category
import com.jox3.tvdesktop.data.Episode
import com.jox3.tvdesktop.data.Movie
import com.jox3.tvdesktop.data.Resume
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.Section
import com.jox3.tvdesktop.data.Series
import com.jox3.tvdesktop.data.SeriesInfo
import com.jox3.tvdesktop.data.TvRepo
import kotlinx.coroutines.launch

private const val CONTINUE_ID = "__continue"
private const val FAV_ID = "__fav"

/** Elemento de la cuadrícula: película o serie con lo necesario para mostrarlo. */
private data class Tile(val id: String, val name: String, val poster: String, val rating: String, val categoryId: String,
                        val added: Long, val movie: Movie? = null, val series: Series? = null)

/** Ficha abierta: película o serie (y la temporada a mostrar al abrir). */
private sealed interface Detail {
    data class OfMovie(val movie: Movie) : Detail
    data class OfSeries(val series: Series, val season: Int? = null) : Detail
}

private enum class Sort(val label: String) { SERVER("Orden del servidor"), RECENT("Más recientes"), AZ("A–Z") }

/**
 * Películas o Series: categorías (con "Continuar viendo" y Favoritos) | cuadrícula de carátulas.
 * Clic en una carátula = su ficha, con Ver / Continuar, temporadas y episodios.
 */
@Composable
fun VodScreen(live: LiveController, section: Section, onPlay: (VodSession, Long) -> Unit) {
    val repo = live.repo
    val lib = repo.library
    val isMovies = section == Section.VOD
    val key = if (isMovies) "movies" else "series"
    val kind = if (isMovies) "movie" else "series"
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val cats by produceState<Result<List<Category>>?>(null, section, reload) { value = runCatching { repo.categories(section) } }
    val catList = cats?.getOrNull()
    val allCats = remember(catList) {
        listOf(Category(CONTINUE_ID, "Continuar viendo"), Category(FAV_ID, "★  Favoritos")) + catList.orEmpty()
    }
    var selected by remember(section) { mutableStateOf<Category?>(null) }
    var detail by remember(section) { mutableStateOf<Detail?>(null) }
    var filter by remember(section) { mutableStateOf("") }
    var catFilter by remember(section) { mutableStateOf("") }
    var sort by remember(section) { mutableStateOf(runCatching { Sort.valueOf(Settings.string("$key.sort", "SERVER")) }.getOrDefault(Sort.SERVER)) }

    LaunchedEffect(catList) {
        if (selected == null && catList != null) {
            val saved = Settings.string("$key.category")
            selected = allCats.firstOrNull { it.id == saved }
                ?: if (lib.continueWatching(if (isMovies) "movie" else "episode").isNotEmpty()) allCats[0] else catList.firstOrNull()
        }
    }
    LaunchedEffect(selected?.id) { selected?.id?.let { Settings.put("$key.category", it); filter = "" } }

    val progressV by lib.progressVersion
    val favKey = if (selected?.id == FAV_ID) lib.favorites.size else -1
    val contKey = if (selected?.id == CONTINUE_ID) progressV else -1
    val tiles by produceState<Result<List<Tile>>?>(null, selected, favKey, contKey, reload) {
        val cat = selected ?: return@produceState
        value = null
        value = runCatching {
            when (cat.id) {
                CONTINUE_ID -> continueTiles(lib.continueWatching(if (isMovies) "movie" else "episode"), isMovies)
                FAV_ID -> lib.favorites.filter { it.kind == kind }.map { f ->
                    if (isMovies) Tile(f.id, f.name, f.image, "", f.categoryId, f.ts, movie = Movie(f.id, f.name, f.image, "", f.ext, f.categoryId, 0))
                    else Tile(f.id, f.name, f.image, "", f.categoryId, f.ts, series = Series(f.id, f.name, f.image, "", f.categoryId, 0))
                }
                else -> if (isMovies) repo.movies(cat.id).map { Tile(it.id, it.name, it.poster, it.rating, it.categoryId, it.added, movie = it) }
                else repo.series(cat.id).map { Tile(it.id, it.name, it.poster, it.rating, it.categoryId, it.updated, series = it) }
            }
        }
    }
    val shown = remember(tiles, filter, sort) {
        val q = TvRepo.normalize(filter.trim())
        val base = tiles?.getOrNull().orEmpty().let { l -> if (q.isEmpty()) l else l.filter { TvRepo.normalize(it.name).contains(q) } }
        when (sort) {
            Sort.SERVER -> base
            Sort.RECENT -> base.sortedByDescending { it.added }
            Sort.AZ -> base.sortedBy { TvRepo.normalize(it.name) }
        }
    }

    if (cats == null) { Status("Cargando categorías…"); return }
    if (catList == null) {
        Status("No se pudieron cargar las categorías: ${cats?.exceptionOrNull()?.message}", "Reintentar") { reload++ }
        return
    }

    Row(Modifier.fillMaxSize()) {
        // ── Categorías ──
        Column(Modifier.width(250.dp).fillMaxHeight().background(Jx.Rail).padding(top = 16.dp)) {
            SearchField(catFilter, { catFilter = it }, "Buscar categoría", Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))
            val shownCats = remember(allCats, catFilter) {
                val q = TvRepo.normalize(catFilter.trim())
                if (q.isEmpty()) allCats else allCats.filter { it.id == CONTINUE_ID || it.id == FAV_ID || TvRepo.normalize(it.name).contains(q) }
            }
            val catState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = catState, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, bottom = 16.dp)) {
                    items(shownCats, key = { it.id }) { c ->
                        val isSel = c.id == selected?.id
                        ListRow(isSel, onClick = { selected = c; detail = null }) {
                            Text(c.name, color = if (isSel) Jx.Accent else Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(catState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (val d = detail) {
                is Detail.OfMovie -> MovieDetail(live, d.movie, onBack = { detail = null }, onPlay = onPlay)
                is Detail.OfSeries -> SeriesDetail(live, d.series, d.season, onBack = { detail = null }, onPlay = onPlay)
                null -> Column(Modifier.fillMaxSize().padding(top = 16.dp, start = 20.dp, end = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected?.name.orEmpty().removePrefix("★  "), color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        tiles?.getOrNull()?.let { Text("${shown.size}", color = Jx.Faint, fontFamily = Exo2, fontSize = 13.sp) }
                        Spacer(Modifier.width(12.dp))
                        SearchField(filter, { filter = it }, if (isMovies) "Buscar película" else "Buscar serie", Modifier.width(260.dp))
                        Spacer(Modifier.width(8.dp))
                        SortChip(sort) { sort = it; Settings.put("$key.sort", it.name) }
                        Spacer(Modifier.width(8.dp))
                        RoundButton(Ico.REFRESH, "Actualizar", small = true) { scope.launch { repo.clear(); reload++ } }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        tiles == null -> Status("Cargando…")
                        tiles?.getOrNull() == null -> Status("No se pudo cargar: ${tiles?.exceptionOrNull()?.message}", "Reintentar") { reload++ }
                        shown.isEmpty() -> Status(when {
                            filter.isNotBlank() -> "Nada coincide con \"$filter\""
                            selected?.id == CONTINUE_ID -> "Aquí aparece lo que empiezas a ver y no terminas."
                            selected?.id == FAV_ID -> "Aún no tienes favoritos. Usa ☆ en la ficha o clic derecho en una carátula."
                            else -> "Esta categoría está vacía"
                        })
                        else -> PosterGrid(live, shown, kind, onOpen = { t ->
                            detail = when {
                                t.movie != null -> Detail.OfMovie(t.movie)
                                t.series != null -> Detail.OfSeries(t.series) // la ficha abre en la temporada del último episodio visto
                                else -> null
                            }
                        })
                    }
                }
            }
        }
    }
}

/** "Continuar viendo": películas tal cual; de episodios, una tarjeta por serie (el último visto). */
private fun continueTiles(list: List<Resume>, movies: Boolean): List<Tile> =
    if (movies) list.map { r ->
        val i = r.item
        Tile(i.id, i.name, i.image, "", i.categoryId, r.ts, movie = Movie(i.id, i.name, i.image, "", i.ext, i.categoryId, 0))
    } else list.distinctBy { it.item.seriesId }.map { r ->
        val i = r.item
        val name = i.name.substringBefore(" · ")
        Tile(i.seriesId, name, i.image, "", i.categoryId, r.ts, series = Series(i.seriesId, name, i.image, "", i.categoryId, 0))
    }

@Composable
private fun SortChip(sort: Sort, onChange: (Sort) -> Unit) {
    val next = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(Jx.Surface)
            .clickable { onChange(next) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(sort.label, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PosterGrid(live: LiveController, tiles: List<Tile>, kind: String, onOpen: (Tile) -> Unit) {
    val lib = live.repo.library
    val state = rememberLazyGridState()
    val progressV by lib.progressVersion
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(158.dp),
            state = state,
            contentPadding = PaddingValues(bottom = 20.dp, end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(tiles, key = { it.id }) { t ->
                @Suppress("UNUSED_VARIABLE") val v = progressV
                val fav = lib.isFavorite(kind, t.id)
                val resume = if (t.movie != null) lib.positionOf(t.id) else lib.lastEpisodeOf(t.id)
                ContextMenuArea(items = {
                    listOf(ContextMenuItem(if (fav) "Quitar de favoritos" else "Agregar a favoritos") {
                        val added = lib.toggleFavorite(SavedItem(kind, t.id, t.name, t.poster, ext = t.movie?.ext.orEmpty(), categoryId = t.categoryId))
                        live.flash(if (added) "Agregado a favoritos" else "Quitado de favoritos")
                    })
                }) {
                    PosterCard(t.name, t.poster, t.rating, fav, resume?.takeUnless { it.finished }?.fraction) { onOpen(t) }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun PosterCard(name: String, poster: String, rating: String, favorite: Boolean, progress: Float?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(Modifier.hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                .then(if (hovered) Modifier.border(2.dp, Jx.Accent, RoundedCornerShape(12.dp)) else Modifier)
        ) {
            RemoteImage(poster, name, Modifier.fillMaxSize(), scale = ContentScale.Crop)
            if (rating.isNotBlank()) {
                Text("★ ${rating.take(3)}", color = Jx.Gold, fontFamily = Exo2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (favorite) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                .background(Color(0xCC000000), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                JxIcon(Ico.STAR_FILLED, 13.dp, Jx.Gold)
            }
            if (progress != null && progress > 0f) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), height = 4.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = if (hovered) Jx.Accent else Jx.Text, fontFamily = Exo2, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ═══ Fichas ═══

@Composable
private fun DetailHeader(
    backdrop: String, poster: String, title: String, meta: List<String>, rating: String,
    onBack: () -> Unit, content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        val bg = backdrop.ifBlank { poster }
        RemoteImage(bg, title, Modifier.fillMaxSize(), scale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99070A0F), Color(0xF2070A0F), Jx.Bg))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE6070A0F), Color(0x00070A0F)))))
        Row(Modifier.fillMaxSize().padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 10.dp)) {
            Column {
                RoundButton(Ico.PREV, "Volver") { onBack() }
            }
            Spacer(Modifier.width(18.dp))
            RemoteImage(poster, title, Modifier.width(190.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)), scale = ContentScale.Crop)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(title, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 28.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rating.isNotBlank()) {
                        Text("★ ${rating.take(3)}", color = Jx.Gold, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(meta.filter { it.isNotBlank() }.joinToString("  ·  "), color = Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun MovieDetail(live: LiveController, movie: Movie, onBack: () -> Unit, onPlay: (VodSession, Long) -> Unit) {
    val repo = live.repo
    val lib = repo.library
    val info by produceState<Result<com.jox3.tvdesktop.data.MovieInfo>?>(null, movie.id) { value = runCatching { repo.movieInfo(movie.id) } }
    val i = info?.getOrNull()
    val progressV by lib.progressVersion
    @Suppress("UNUSED_VARIABLE") val pv = progressV
    val resume = lib.positionOf(movie.id)?.takeUnless { it.finished || it.position < 5_000 }
    val fav = lib.isFavorite("movie", movie.id)
    val poster = i?.poster?.ifBlank { null } ?: movie.poster
    val ext = i?.ext?.ifBlank { null } ?: movie.ext

    fun session() = VodSession(
        item = SavedItem("movie", movie.id, movie.name, poster, ext = ext, categoryId = movie.categoryId),
        title = movie.name,
        subtitle = listOf(i?.year.orEmpty(), i?.genre.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
        image = poster,
        url = repo.api.movieUrl(movie.id, ext),
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailHeader(
                backdrop = i?.backdrop.orEmpty(), poster = poster, title = movie.name,
                meta = listOf(i?.year.orEmpty(), i?.duration.orEmpty(), i?.genre.orEmpty()),
                rating = i?.rating?.ifBlank { null } ?: movie.rating, onBack = onBack,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (resume != null) {
                        PillButton("▶  Continuar (${clock(resume.position)})") { onPlay(session(), resume.position) }
                        GhostButton("Desde el inicio") { onPlay(session(), 0) }
                    } else {
                        PillButton("▶  Ver") { onPlay(session(), 0) }
                    }
                    GhostButton(if (fav) "★  En favoritos" else "☆  Favorito") {
                        lib.toggleFavorite(SavedItem("movie", movie.id, movie.name, poster, ext = ext, categoryId = movie.categoryId))
                    }
                }
                if (resume != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(resume.fraction, Modifier.width(220.dp), height = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Faltan ${resume.remainingMin} min", color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp).fillMaxWidth()) {
                when {
                    info == null -> Text("Cargando la ficha…", color = Jx.Faint, fontFamily = Exo2)
                    i == null -> Text("El servidor no entregó la ficha de esta película.", color = Jx.Faint, fontFamily = Exo2)
                    else -> {
                        if (i.plot.isNotBlank()) Text(i.plot, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 15.sp, lineHeight = 22.sp)
                        Spacer(Modifier.height(16.dp))
                        if (i.director.isNotBlank()) InfoLine("Dirección", i.director)
                        if (i.cast.isNotBlank()) InfoLine("Reparto", i.cast)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesDetail(live: LiveController, series: Series, startSeason: Int?, onBack: () -> Unit, onPlay: (VodSession, Long) -> Unit) {
    val repo = live.repo
    val lib = repo.library
    val info by produceState<Result<SeriesInfo>?>(null, series.id) { value = runCatching { repo.seriesInfo(series.id) } }
    val si = info?.getOrNull()
    val progressV by lib.progressVersion
    @Suppress("UNUSED_VARIABLE") val pv = progressV
    val last = lib.lastEpisodeOf(series.id)
    val fav = lib.isFavorite("series", series.id)
    val poster = si?.poster?.ifBlank { null } ?: series.poster
    val seasons = si?.seasons.orEmpty()
    var season by remember(series.id, si) {
        mutableStateOf(startSeason ?: last?.let { r -> seasons.entries.firstOrNull { e -> e.value.any { it.id == r.item.id } }?.key }
            ?: seasons.keys.firstOrNull() ?: 1)
    }
    val allEpisodes = remember(seasons) { seasons.values.flatten() }

    fun session(ep: Episode): VodSession {
        val idx = allEpisodes.indexOfFirst { it.id == ep.id }
        val next = allEpisodes.getOrNull(idx + 1)
        val label = "T${ep.season} E${ep.num}" + if (ep.title.isNotBlank() && !ep.title.contains(series.name, true)) " · ${ep.title}" else ""
        return VodSession(
            item = SavedItem("episode", ep.id, "${series.name} · $label", poster, ext = ep.ext, seriesId = series.id, categoryId = series.categoryId),
            title = series.name,
            subtitle = label,
            image = poster,
            url = repo.api.episodeUrl(ep.id, ep.ext),
            nextLabel = next?.let { n -> "T${n.season} E${n.num}" + if (n.title.isNotBlank()) " · ${n.title}" else "" },
            next = next?.let { n -> { onPlay(session(n), lib.positionOf(n.id)?.takeUnless { it.finished }?.position ?: 0) } },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailHeader(
                backdrop = si?.backdrop.orEmpty(), poster = poster, title = si?.name?.ifBlank { null } ?: series.name,
                meta = listOf(si?.year.orEmpty(), si?.genre.orEmpty(), if (seasons.isNotEmpty()) "${seasons.size} temporada${if (seasons.size > 1) "s" else ""}" else ""),
                rating = si?.rating?.ifBlank { null } ?: series.rating, onBack = onBack,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val lastEp = last?.let { r -> allEpisodes.firstOrNull { it.id == r.item.id } }
                    when {
                        lastEp != null && last != null && !last.finished ->
                            PillButton("▶  Continuar ${last.item.name.substringAfter(" · ")}") { onPlay(session(lastEp), last.position) }
                        lastEp != null -> {
                            val nxt = allEpisodes.getOrNull(allEpisodes.indexOf(lastEp) + 1)
                            if (nxt != null) PillButton("▶  Siguiente: T${nxt.season} E${nxt.num}") { onPlay(session(nxt), 0) }
                            else PillButton("▶  Ver de nuevo T${lastEp.season} E${lastEp.num}") { onPlay(session(lastEp), 0) }
                        }
                        allEpisodes.isNotEmpty() -> PillButton("▶  Ver T${allEpisodes[0].season} E${allEpisodes[0].num}") { onPlay(session(allEpisodes[0]), 0) }
                    }
                    GhostButton(if (fav) "★  En favoritos" else "☆  Favorito") {
                        lib.toggleFavorite(SavedItem("series", series.id, series.name, poster, categoryId = series.categoryId))
                    }
                }
                if (!si?.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(si!!.plot, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        when {
            info == null -> item { Text("Cargando temporadas…", color = Jx.Faint, fontFamily = Exo2, modifier = Modifier.padding(32.dp)) }
            si == null || seasons.isEmpty() -> item {
                Text("El servidor no entregó los episodios de esta serie.", color = Jx.Faint, fontFamily = Exo2, modifier = Modifier.padding(32.dp))
            }
            else -> {
                item {
                    LazyRow(Modifier.padding(horizontal = 28.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons.keys.toList()) { n ->
                            val sel = n == season
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(if (sel) Jx.Accent else Jx.Surface)
                                    .clickable { season = n }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text("Temporada $n", color = if (sel) Jx.OnAccent else Jx.TextSoft, fontFamily = Exo2,
                                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                items(seasons[season].orEmpty(), key = { it.id }) { ep ->
                    val r = lib.positionOf(ep.id)
                    EpisodeRow(ep, poster, r, playing = false) {
                        onPlay(session(ep), r?.takeUnless { it.finished || it.position < 5_000 }?.position ?: 0)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, poster: String, resume: Resume?, playing: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (hovered || playing) Jx.Surface else Color.Transparent)
            .hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(180.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            RemoteImage(ep.image.ifBlank { poster }, ep.title, Modifier.fillMaxSize(), scale = ContentScale.Crop)
            if (hovered) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                JxIcon(Ico.PLAY, 28.dp, Jx.Text)
            }
            if (resume != null) ProgressLine(if (resume.finished) 1f else resume.fraction,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp), height = 3.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("E${ep.num}", color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Text(ep.title.ifBlank { "Episodio ${ep.num}" }, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.Medium,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (resume?.finished == true) { Spacer(Modifier.width(10.dp)); Pill("VISTO", Jx.Ok) }
            }
            if (ep.duration.isNotBlank()) Text(ep.duration, color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
            if (ep.plot.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(ep.plot, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Text(value, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp)
    }
}

/** Botón de texto con borde (acción secundaria). */
@Composable
fun GhostButton(text: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (hovered) Jx.SurfaceHi else Color(0x66111923))
            .border(1.dp, Jx.Line, RoundedCornerShape(50))
            .hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}
