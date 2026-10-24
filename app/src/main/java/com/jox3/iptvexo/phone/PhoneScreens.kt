package com.jox3.iptvexo.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.PlayerLauncher
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.ServerHub
import androidx.compose.foundation.shape.CircleShape
import com.jox3.iptvexo.tv.data.AccountStore
import com.jox3.iptvexo.tv.data.Category
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.MovieInfo
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.Section
import com.jox3.iptvexo.tv.data.Series
import com.jox3.iptvexo.tv.data.SeriesInfo
import com.jox3.iptvexo.tv.data.ServerInfo
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.data.XtreamApi
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────── Inicio ───────────────────────────

private class HomeData(val hero: Movie?, val heroInfo: MovieInfo?, val movies: List<Movie>, val series: List<Series>)

@Composable
fun PhoneHome(repo: TvRepo, launcher: PlayerLauncher, onOpenMovie: (Movie) -> Unit, onOpenSeries: (String, String, String) -> Unit,
              onPlayChannel: (List<Channel>, Int) -> Unit) {
    val lib = repo.library
    val data by produceState<HomeData?>(null) {
        value = runCatching {
            val movies = repo.latestMovies()
            val series = repo.latestSeries()
            val hero = movies.firstOrNull()
            HomeData(hero, hero?.let { runCatching { repo.api.movieInfo(it.id) }.getOrNull() }, movies, series)
        }.getOrDefault(HomeData(null, null, emptyList(), emptyList()))
    }
    val progressVersion by lib.progressVersion
    val resume = remember(progressVersion) { lib.continueWatching() }
    val recents = lib.recentChannels.toList()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        val d = data
        val hero = d?.hero
        item {
            if (hero != null) HeroCarousel(repo, launcher, d.movies.take(8), onOpenMovie)
            else Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(Jx.Surface)) {
                CenterText(if (d == null) "Cargando…" else "")
            }
        }
        if (resume.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Continuar viendo")
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resume, key = { it.item.id }) { r ->
                        val isEp = r.item.kind == "episode"
                        WideTile(
                            title = r.item.name.substringBefore(" · ").ifBlank { r.item.name },
                            subtitle = (if (isEp) r.item.name.substringAfter(" · ", "") + " · " else "") + "quedan ${r.remainingMin} min",
                            image = r.item.image, progress = r.fraction,
                        ) {
                            if (isEp) onOpenSeries(r.item.seriesId, r.item.name.substringBefore(" · "), r.item.image)
                            else launcher.playMovie(r.item.id, r.item.name, r.item.image, r.item.ext, r.item.categoryId)
                        }
                    }
                }
            }
        }
        if (recents.isNotEmpty()) item {
            val chans = recents.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Tus canales")
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(chans, key = { _, c -> c.id }) { i, c -> LiveCard(c, repo) { onPlayChannel(chans, i) } }
                }
            }
        }
        if (!d?.movies.isNullOrEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Películas recientes")
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d!!.movies, key = { it.id }) { m ->
                        PosterTile(m.name, m.poster, Modifier.width(112.dp), favorite = lib.isFavorite("movie", m.id)) { onOpenMovie(m) }
                    }
                }
            }
        }
        if (!d?.series.isNullOrEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Series recientes")
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d!!.series, key = { it.id }) { s ->
                        PosterTile(s.name, s.poster, Modifier.width(112.dp), favorite = lib.isFavorite("series", s.id)) { onOpenSeries(s.id, s.name, s.poster) }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Películas / Series ───────────────────────────

/**
 * Categoría elegida y posición de la cuadrícula en Películas y en Series. Esta pantalla
 * se cierra al abrir una ficha o el reproductor; al volver se arma igual que estaba.
 * La categoría también queda en preferencias para la próxima vez que se abra la app.
 */
private object CatalogMemory {
    class Spot(var catId: String? = null, var index: Int = 0, var offset: Int = 0)
    val spots = HashMap<Section, Spot>()
    fun of(s: Section) = spots.getOrPut(s) { Spot() }
}

private class GridEntry(val id: String, val name: String, val poster: String, val rating: String, val kind: String, val open: () -> Unit)

@Composable
fun PhoneCatalog(repo: TvRepo, section: Section, onOpenMovie: (Movie) -> Unit, onOpenSeries: (String, String, String) -> Unit) {
    val lib = repo.library
    val cats by produceState<List<Category>?>(null, section) { value = runCatching { repo.categories(section) }.getOrDefault(emptyList()) }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    val prefKey = if (section == Section.VOD) "phone_vod_cat" else "phone_series_cat"
    val spot = remember(section) { CatalogMemory.of(section) }
    var selected by remember(section) { mutableStateOf(spot.catId ?: prefs.getString(prefKey, null)) }
    var catSheet by remember { mutableStateOf(false) }
    LaunchedEffect(cats) {
        val list = cats ?: return@LaunchedEffect
        // Si la categoría recordada no existe (otra cuenta), se usa la primera
        if (selected == null || list.none { it.id == selected }) selected = list.firstOrNull()?.id
    }
    LaunchedEffect(selected) {
        val id = selected ?: return@LaunchedEffect
        if (spot.catId != id) { spot.index = 0; spot.offset = 0 }
        spot.catId = id
        prefs.edit().putString(prefKey, id).apply()
    }
    val entries by produceState<List<GridEntry>?>(null, selected) {
        val id = selected ?: return@produceState
        value = null
        value = runCatching {
            if (section == Section.VOD) repo.movies(id).map { m -> GridEntry(m.id, m.name, m.poster, m.rating, "movie") { onOpenMovie(m) } }
            else repo.series(id).map { s -> GridEntry(s.id, s.name, s.poster, s.rating, "series") { onOpenSeries(s.id, s.name, s.poster) } }
        }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize()) {
        val list = cats
        if (list == null) { CenterText("Cargando…"); return@Column }
        if (list.isEmpty()) { CenterText("Esta cuenta no tiene ${if (section == Section.VOD) "películas" else "series"}"); return@Column }
        val chips = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyRow(state = chips, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Todas las categorías en una lista vertical
            item(key = "__all") { Pill("Categorías", false, icon = R.drawable.tv_ic_list) { catSheet = true } }
            items(list, key = { it.id }) { c -> Pill(c.name, c.id == selected) { selected = c.id } }
        }
        LaunchedEffect(selected) {
            val i = list.indexOfFirst { it.id == selected }
            if (i >= 0) runCatching { chips.animateScrollToItem(i) }
        }
        if (catSheet) CategorySheet(list, selected, onPick = { selected = it.id }) { catSheet = false }
        val e = entries
        when {
            e == null -> CenterText("Cargando…")
            e.isEmpty() -> CenterText("Categoría vacía")
            else -> {
              val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(spot.index, spot.offset)
              val gridCat = selected
              DisposableEffect(gridState) {
                  onDispose { if (spot.catId == gridCat) { spot.index = gridState.firstVisibleItemIndex; spot.offset = gridState.firstVisibleItemScrollOffset } }
              }
              LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(e, key = { it.id }) { g ->
                    PosterTile(g.name, g.poster, subtitle = g.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                        favorite = lib.isFavorite(g.kind, g.id), onClick = g.open)
                }
              }
            }
        }
    }
}

// ─────────────────────────── Detalles ───────────────────────────

@Composable
fun PhoneMovieDetail(repo: TvRepo, launcher: PlayerLauncher, movie: Movie, onOpenMovie: (Movie) -> Unit = {}, onBack: () -> Unit) {
    val lib = repo.library
    val info by produceState<MovieInfo?>(null, movie.id) { value = runCatching { repo.api.movieInfo(movie.id) }.getOrNull() }
    val progressVersion by lib.progressVersion
    val (pos, dur) = remember(progressVersion) { lib.positionOf(movie.id) }
    val canResume = pos > 0 && dur > 0 && pos.toFloat() / dur < 0.95f
    val fav = SavedItem("movie", movie.id, movie.name, movie.poster, ext = movie.ext, categoryId = movie.categoryId)
    val ext = info?.ext?.ifBlank { null } ?: movie.ext
    val similar by produceState(emptyList<Movie>(), movie.id, info) {
        if (info == null) return@produceState
        value = runCatching { repo.similarMovies(movie, info?.genre.orEmpty(), 21) }.getOrDefault(emptyList())
    }
    val tm = com.jox3.iptvexo.tv.ui.rememberTmdb("movie", movie.name)
    var trailerOpen by remember { mutableStateOf(false) }
    if (trailerOpen && tm != null) PhoneTrailerDialog(tm.trailerKey) { trailerOpen = false }
    val others = com.jox3.iptvexo.tv.ui.rememberOtherCopies("movie", movie.name, movie.id, repo)
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { DetailHeader(info?.backdrop?.ifBlank { null } ?: tm?.backdrop?.ifBlank { null } ?: movie.poster, movie.name, onBack, tm) }
        item {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(movie.name, style = PType.Hero, color = Jx.Text)
                PhoneRating(tm, info?.rating?.ifBlank { null } ?: movie.rating)
                val meta = listOfNotNull(info?.year?.ifBlank { null } ?: tm?.year?.ifBlank { null },
                    info?.genre?.ifBlank { null } ?: tm?.genres?.ifBlank { null },
                    info?.duration?.ifBlank { null } ?: tm?.runtimeMin?.takeIf { it > 0 }?.let { "${it / 60} h ${it % 60} min" }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = PType.Small, color = Jx.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (canResume) PButton("Continuar · ${fmtTime(pos)}", Modifier.weight(1f), icon = R.drawable.ic_play, progress = pos.toFloat() / dur) {
                        launcher.playMovie(movie.id, movie.name, movie.poster, ext, movie.categoryId)
                    } else PButton("Reproducir", Modifier.weight(1f), icon = R.drawable.ic_play) {
                        launcher.playMovie(movie.id, movie.name, movie.poster, ext, movie.categoryId)
                    }
                    val isFav = lib.isFavorite("movie", movie.id)
                    RoundIcon(if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star, if (isFav) "En favoritos" else "Agregar a favoritos",
                        size = 48.dp, bg = Jx.SurfaceHi, tint = if (isFav) Jx.Gold else Jx.Text) { lib.toggleFavorite(fav) }
                }
                if (canResume) PButton("Desde el inicio", Modifier.fillMaxWidth(), icon = R.drawable.tv_ic_restart, primary = false) {
                    lib.clearProgress(movie.id); launcher.playMovie(movie.id, movie.name, movie.poster, ext, movie.categoryId)
                }
                if (!tm?.trailerKey.isNullOrBlank()) PButton("Ver tráiler", Modifier.fillMaxWidth(), icon = R.drawable.ic_play, primary = false) {
                    trailerOpen = true
                }
                AvailableIn(others) { c -> onOpenMovie(Movie(c.id, c.name, c.image, c.rating, c.ext, c.categoryId, 0L)) }
                (info?.plot?.ifBlank { null } ?: tm?.overview?.ifBlank { null })?.let { Text(it, style = PType.Body, color = Jx.TextSoft) }
                if (tm?.cast.isNullOrEmpty()) {
                    val credits = listOfNotNull(info?.director?.ifBlank { null }?.let { "Dirección: $it" }, info?.cast?.ifBlank { null }?.let { "Reparto: $it" })
                    credits.forEach { Text(it, style = PType.Small, color = Jx.Muted) }
                } else tm!!.director.takeIf { it.isNotBlank() }?.let { Text("Dirección: $it", style = PType.Small, color = Jx.Muted) }
            }
        }
        if (!tm?.cast.isNullOrEmpty()) item { PhoneCastRow(tm!!.cast) }
        // Películas parecidas por género (no de la misma carpeta)
        val sim = similar
        if (sim.isNotEmpty()) {
            item { SectionTitle("Películas similares", Modifier.padding(top = 22.dp, bottom = 10.dp)) }
            items(sim.chunked(3).size) { row ->
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val chunk = sim.chunked(3)[row]
                    chunk.forEach { m -> PosterTile(m.name, m.poster, Modifier.weight(1f), favorite = lib.isFavorite("movie", m.id)) { onOpenMovie(m) } }
                    repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun PhoneSeriesDetail(repo: TvRepo, launcher: PlayerLauncher, seriesId: String, name: String, poster: String, onBack: () -> Unit) {
    val lib = repo.library
    val info by produceState<Result<SeriesInfo>?>(null, seriesId) { value = runCatching { repo.api.seriesInfo(seriesId) } }
    val data = info?.getOrNull()
    val seasons = data?.seasons.orEmpty()
    val all = remember(data) { seasons.values.flatten() }
    val progressVersion by lib.progressVersion
    // Episodio para "Continuar": el último empezado y no terminado
    val resume = remember(data, progressVersion) {
        all.mapNotNull { e -> val (p, d) = lib.positionOf(e.id); if (p > 0 && d > 0 && p.toFloat() / d < 0.95f) e to p.toFloat() / d else null }.lastOrNull()
    }
    var season by remember(data) { mutableStateOf(resume?.first?.season ?: seasons.keys.firstOrNull() ?: 1) }
    val tm = com.jox3.iptvexo.tv.ui.rememberTmdb("series", name)
    var trailerOpen by remember { mutableStateOf(false) }
    if (trailerOpen && tm != null) PhoneTrailerDialog(tm.trailerKey) { trailerOpen = false }
    val others = com.jox3.iptvexo.tv.ui.rememberOtherCopies("series", name, seriesId, repo)
    val ctx = LocalContext.current
    val title = data?.name?.ifBlank { null } ?: name
    val cover = data?.poster?.ifBlank { null } ?: poster.ifBlank { null } ?: tm?.poster.orEmpty()
    val isFav = lib.isFavorite("series", seriesId)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { DetailHeader(data?.backdrop?.ifBlank { null } ?: tm?.backdrop?.ifBlank { null } ?: cover, title, onBack, tm) }
        item {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = PType.Hero, color = Jx.Text)
                PhoneRating(tm, data?.rating.orEmpty())
                val meta = listOfNotNull(data?.year?.ifBlank { null } ?: tm?.year?.ifBlank { null },
                    data?.genre?.ifBlank { null } ?: tm?.genres?.ifBlank { null },
                    seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 temporada" else "$it temporadas" }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = PType.Small, color = Jx.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (resume != null) PButton("Continuar T${resume.first.season} · E${resume.first.num}", Modifier.weight(1f),
                        icon = R.drawable.ic_play, progress = resume.second) {
                        launcher.playEpisode(seriesId, title, cover, all, all.indexOf(resume.first))
                    } else if (all.isNotEmpty()) PButton("Ver desde el inicio", Modifier.weight(1f), icon = R.drawable.ic_play) {
                        launcher.playEpisode(seriesId, title, cover, all, 0)
                    } else Spacer(Modifier.weight(1f))
                    RoundIcon(if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star, if (isFav) "En favoritos" else "Agregar a favoritos",
                        size = 48.dp, bg = Jx.SurfaceHi, tint = if (isFav) Jx.Gold else Jx.Text) { lib.toggleFavorite(SavedItem("series", seriesId, title, cover)) }
                }
                if (!tm?.trailerKey.isNullOrBlank()) PButton("Ver tráiler", Modifier.fillMaxWidth(), icon = R.drawable.ic_play, primary = false) {
                    trailerOpen = true
                }
                AvailableIn(others.filter { it.account.key != com.jox3.iptvexo.tv.data.LocalServers.current?.account?.key })
                (data?.plot?.ifBlank { null } ?: tm?.overview?.ifBlank { null })?.let {
                    Text(it, style = PType.Body, color = Jx.TextSoft, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!tm?.cast.isNullOrEmpty()) item { PhoneCastRow(tm!!.cast) }
        when {
            info == null -> item { CenterText("Cargando episodios…", Modifier.height(160.dp)) }
            data == null -> item { CenterText("No se pudo cargar la serie", Modifier.height(160.dp)) }
            seasons.isEmpty() -> item { CenterText("Esta serie aún no tiene episodios", Modifier.height(160.dp)) }
            else -> {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons.keys.toList(), key = { it }) { s -> Pill("Temporada $s", s == season) { season = s } }
                    }
                }
                items(seasons[season].orEmpty(), key = { it.id }) { ep ->
                    val (p, d) = lib.positionOf(ep.id)
                    val frac = if (d > 0) p.toFloat() / d else 0f
                    Row(
                        Modifier.fillMaxWidth().clickable { launcher.playEpisode(seriesId, title, cover, all, all.indexOf(ep)) }
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                            RemoteImage(ep.image.ifBlank { cover }, ep.title.ifBlank { "E${ep.num}" }, Modifier.fillMaxSize())
                            if (frac > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(Jx.Accent))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("E${ep.num} · ${ep.title.ifBlank { "Episodio ${ep.num}" }}", style = PType.Label, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(when { frac >= 0.95f -> "Visto"; frac > 0f -> "A medias"; else -> ep.duration }, style = PType.Tiny,
                                color = if (frac >= 0.95f) Jx.Ok else Jx.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(image: String, title: String, onBack: () -> Unit, tm: com.jox3.iptvexo.tv.data.Tmdb.Info? = null) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        RemoteImage(image, title, Modifier.fillMaxSize())
        // Tráiler de fondo sin sonido: solo oficiales y solo con WiFi (no gasta datos)
        val bgKey = tm?.trailerKey?.takeIf {
            it.isNotBlank() && tm.trailerOfficial && com.jox3.iptvexo.tv.data.Tmdb.backgroundTrailers(ctx) &&
                com.jox3.iptvexo.tv.data.Tmdb.onUnmeteredNetwork(ctx)
        }
        var bgStart by remember(bgKey) { mutableStateOf(false) }
        var bgShow by remember(bgKey) { mutableStateOf(false) }
        var bgFailed by remember(bgKey) { mutableStateOf(false) }
        LaunchedEffect(bgKey) { if (bgKey != null) { delay(2000); bgStart = true } }
        val a by androidx.compose.animation.core.animateFloatAsState(if (bgShow) 1f else 0f,
            androidx.compose.animation.core.tween(800), label = "bgTrailer")
        if (bgKey != null && bgStart && !bgFailed) com.jox3.iptvexo.tv.ui.TrailerPlayer(bgKey, muted = true,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = a }, onPlaying = { bgShow = true }, onError = { bgFailed = true })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66070A0F), Color.Transparent, Jx.Bg))))
        RoundIcon(R.drawable.ic_back, "Volver", Modifier.statusBarsPadding().padding(10.dp), bg = Color(0x8C070A0F), onClick = onBack)
    }
}

// ─────────────────────────── Buscar ───────────────────────────

private class SearchResults(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@Composable
fun PhoneSearch(
    repo: TvRepo, onOpenMovie: (Movie) -> Unit, onOpenSeries: (String, String, String) -> Unit, onPlayChannel: (List<Channel>, Int) -> Unit,
    account: Account? = null, accounts: List<Account> = emptyList(), onSwitch: (Account) -> Unit = {},
) {
    val lib = repo.library
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var only by remember { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf<ServerHub.Hit?>(null) }
    val multi = account != null && accounts.size > 1 && ServerHub.searchAllEnabled(ctx)
    LaunchedEffect(multi) { if (multi) ServerHub.ensureIndexes(accounts.filter { it.key != account?.key }) }
    val indexVersion by ServerHub.indexVersion
    val index by produceState<TvRepo.Index?>(null) { value = runCatching { repo.searchIndex() }.getOrNull() }
    val me = account ?: Account("", "", "")
    val results by produceState<ServerHub.Results?>(null, query, index, only, indexVersion) {
        val idx = index ?: return@produceState
        val q = TvRepo.normalize(query.trim())
        if (q.length < 2) { value = null; return@produceState }
        delay(200)
        value = ServerHub.search(query, me, idx, if (multi) accounts else listOf(me), only, includeOthers = multi)
    }
    LaunchedEffect(results) { val r = results ?: return@LaunchedEffect; if (!r.isEmpty) { delay(4000); lib.addSearch(query) } }
    fun letters(h: ServerHub.Hit) = if (multi) h.servers.map { ServerHub.letter(accounts, it) } else emptyList()
    val here = results?.channels.orEmpty().mapNotNull { h -> h.copies.firstOrNull { it.account.key == me.key } }
        .mapIndexed { i, c -> Channel(c.id, c.name, c.image, c.categoryId, i + 1) }
    fun openCopy(c: ServerHub.Copy) {
        choose = null
        lib.addSearch(query)
        if (c.account.key != me.key) { ServerHub.pendingOpen = c; onSwitch(c.account); return }
        when (c.kind) {
            "movie" -> onOpenMovie(Movie(c.id, c.name, c.image, c.rating, c.ext, c.categoryId, 0L))
            "series" -> onOpenSeries(c.id, c.name, c.image)
            else -> {
                val i = here.indexOfFirst { it.id == c.id }
                if (i >= 0) onPlayChannel(here, i) else onPlayChannel(listOf(Channel(c.id, c.name, c.image, c.categoryId, 1)), 0)
            }
        }
    }
    /** Si solo está en este servidor se abre; si no, se elige de cuál. */
    fun open(h: ServerHub.Hit) { if (h.copies.all { it.account.key == me.key }) openCopy(h.copies.first()) else choose = h }

    choose?.let { h ->
        PlayerSheet({ choose = null }) {
            Text(h.name, style = PType.H2, color = Jx.Text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            Text("¿De qué servidor lo quieres ver?", style = PType.Small, color = Jx.Muted, modifier = Modifier.padding(horizontal = 22.dp))
            LaunchedEffect(Unit) { ServerHub.refresh(h.servers) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                h.copies.distinctBy { it.account.key }.forEachIndexed { i, c ->
                    val st = ServerHub.status[c.account.key]
                    val cur = c.account.key == me.key
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (i == 0) Jx.Text else Jx.Surface)
                            .clickable { openCopy(c) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val fg = if (i == 0) Jx.Bg else Jx.Text
                        Column(Modifier.weight(1f)) {
                            Text("${ServerHub.letter(accounts, c.account)} · ${ServerHub.name(accounts, c.account)}" +
                                (if (c.tag.isNotBlank()) " · ${c.tag}" else ""), style = PType.Label, color = fg, maxLines = 1)
                            Text((if (cur) "Servidor actual · " else "Cambia a este servidor · ") + com.jox3.iptvexo.tv.screens.statusText(st),
                                style = PType.Tiny, color = if (i == 0) Jx.Line else Jx.Muted, maxLines = 1)
                            if (c.kind == "live") {
                                val now by produceState<String?>(null, c) { value = ServerHub.nowOn(ctx, c) }
                                Text(when (now) { null -> "Buscando la guía…"; "" -> "Sin guía en este servidor"; else -> "▶ Ahora: $now" },
                                    style = PType.Tiny, color = if (now.isNullOrEmpty()) (if (i == 0) Jx.Line else Jx.Faint) else (if (i == 0) Jx.Bg else Jx.Accent),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Box(Modifier.size(9.dp).clip(CircleShape).background(com.jox3.iptvexo.tv.screens.statusColor(st)))
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(if (multi) "Buscar en todos tus servidores" else "Canales, películas y series", style = PType.Body) },
            singleLine = true, textStyle = PType.Body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jx.Accent, unfocusedBorderColor = Jx.Line,
                focusedTextColor = Jx.Text, unfocusedTextColor = Jx.Text, cursorColor = Jx.Accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (multi) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill("Todos · ${accounts.size}", only == null) { only = null } }
                items(accounts, key = { it.key }) { a -> Pill(ServerHub.letter(accounts, a) + " · " + ServerHub.name(accounts, a), only == a.key) { only = a.key } }
            }
            val loading = accounts.filter { it.key != me.key && ServerHub.loadingIndex[it.key] == true }
            if (loading.isNotEmpty()) Text("Preparando el catálogo de " + loading.joinToString(", ") { ServerHub.letter(accounts, it) } + "…",
                style = PType.Tiny, color = Jx.Faint, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
        val r = results
        when {
            index == null -> CenterText("Preparando la búsqueda en todo el catálogo…")
            query.trim().length < 2 -> {
                val hist = lib.searchHistory.toList()
                if (hist.isEmpty()) CenterText("Escribe al menos 2 letras.\nTus búsquedas aparecerán aquí.")
                else LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                    item { Text("BÚSQUEDAS RECIENTES", style = PType.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
                    items(hist, key = { it }) { h ->
                        Row(Modifier.fillMaxWidth().clickable { query = h }.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.tv_ic_search), null, Modifier.size(16.dp), tint = Jx.Muted)
                            Text(h, style = PType.Body, color = Jx.Text, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            RoundIcon(R.drawable.ic_close, "Borrar \"$h\"", tint = Jx.Muted, iconSize = 16.dp) { lib.removeSearch(h) }
                        }
                    }
                    item { Text("Borrar historial", style = PType.Label, color = Jx.Live,
                        modifier = Modifier.clickable { lib.clearSearches() }.padding(horizontal = 18.dp, vertical = 14.dp)) }
                }
            }
            r == null -> CenterText("Buscando…")
            r.isEmpty -> CenterText("Sin resultados para “${query.trim()}”")
            else -> LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (r.channels.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("En vivo · ${r.channels.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.channels, key = { it.key }) { h ->
                                val epgV by repo.epgVersion
                                val now by produceState("", h.key, epgV) {
                                    val (t, from) = ServerHub.nowForHit(ctx, h, me)
                                    value = if (t.isEmpty()) "" else if (multi && from != null && from.key != me.key)
                                        "${ServerHub.letter(accounts, from)} · $t" else t
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ChannelBubble(h.name, h.image, Modifier.width(96.dp), now = now) { open(h) }
                                    BadgeRow(letters(h))
                                }
                            }
                        }
                    }
                }
                if (r.movies.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Películas · ${r.movies.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(r.movies, key = { it.key }) { h ->
                                PosterTile(h.name, h.image, Modifier.width(108.dp), subtitle = h.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                                    badges = letters(h)) { open(h) }
                            }
                        }
                    }
                }
                if (r.series.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Series · ${r.series.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(r.series, key = { it.key }) { h ->
                                PosterTile(h.name, h.image, Modifier.width(108.dp), subtitle = h.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                                    badges = letters(h)) { open(h) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Ajustes ───────────────────────────

@Composable
fun PhoneSettings(repo: TvRepo, act: PhoneActivity, account: Account, onAddAccount: () -> Unit, onLogout: () -> Unit,
                  onServers: () -> Unit = {}, onBack: () -> Unit) {
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete) DeleteAccountDialog(account, onConfirm = { askDelete = false; onLogout() }, onDismiss = { askDelete = false })
    val info by produceState<Result<ServerInfo>?>(null) { value = runCatching { repo.api.serverInfo() } }
    val date = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(R.drawable.ic_back, "Volver", onClick = onBack)
                Text("Ajustes y cuenta", style = PType.H1, color = Jx.Text)
            }
        }
        item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Jx.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoLine("Servidor", account.label)
                InfoLine("Usuario", account.user)
                val i = info?.getOrNull()
                when {
                    info == null -> InfoLine("Estado", "Consultando…")
                    i == null -> InfoLine("Estado", "No se pudo consultar")
                    else -> {
                        val active = i.status.equals("Active", true)
                        InfoLine("Estado", if (active) "Activa" else i.status, if (active) Jx.Ok else Jx.Live)
                        val exp = i.expires
                        if (exp != null) {
                            val days = ((exp - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                            InfoLine("Vence", date.format(Date(exp)))
                            InfoLine("Días restantes", "$days", when { days < 7 -> Jx.Live; days < 30 -> Jx.Gold; else -> Jx.Ok })
                        } else InfoLine("Vence", "Sin fecha")
                        InfoLine("Conexiones", "${i.activeCons} / ${i.maxCons}")
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PButton("Buscar actualizaciones", Modifier.fillMaxWidth(), icon = R.drawable.tv_ic_update, primary = false) { act.checkUpdates() }
                PButton("Mis servidores", Modifier.fillMaxWidth(), icon = R.drawable.tv_ic_list, primary = false, onClick = onServers)
                PButton("Agregar otro servidor", Modifier.fillMaxWidth(), primary = false, onClick = onAddAccount)
                val sctx = LocalContext.current
                var bgTrailer by remember { mutableStateOf(com.jox3.iptvexo.tv.data.Tmdb.backgroundTrailers(sctx)) }
                PButton("Tráiler de fondo: " + (if (bgTrailer) "Sí (solo con WiFi)" else "No"), Modifier.fillMaxWidth(), primary = false) {
                    bgTrailer = !bgTrailer; com.jox3.iptvexo.tv.data.Tmdb.setBackgroundTrailers(sctx, bgTrailer)
                }
                PButton("Eliminar esta cuenta", Modifier.fillMaxWidth(), icon = R.drawable.ic_delete, primary = false) { askDelete = true }
                Text("JOX3 TV ${act.appVersion()}", style = PType.Small, color = Jx.Faint, modifier = Modifier.padding(top = 6.dp))
                Text("Las versiones nuevas se instalan solas: la app avisa al abrirse.", style = PType.Small, color = Jx.Faint)
            }
        }
    }
}

/** Confirmación antes de borrar una cuenta del equipo. */
@Composable
private fun DeleteAccountDialog(account: Account, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Jx.Surface,
        title = { Text("¿Eliminar esta cuenta?", style = PType.H2, color = Jx.Text) },
        text = {
            Text("${account.label} · ${account.user}\nSe borra del equipo junto con sus favoritos e historial. Puedes volver a agregarla cuando quieras.",
                style = PType.Body, color = Jx.TextSoft)
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text("Eliminar", color = Jx.Live, style = PType.Label) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar", color = Jx.TextSoft, style = PType.Label) } },
    )
}

@Composable
private fun InfoLine(label: String, value: String, color: Color = Jx.Text) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = PType.Label, color = Jx.Muted, modifier = Modifier.weight(1f))
        Text(value, style = PType.Label, color = color)
    }
}

// ─────────────────────────── Inicio de sesión ───────────────────────────

@Composable
fun PhoneLogin(accounts: AccountStore, onLogin: (Account) -> Unit, onCancel: (() -> Unit)?) {
    var withCode by remember { mutableStateOf(false) }
    if (withCode) {
        Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            com.jox3.iptvexo.tv.screens.VaultCodeLogin(tv = false, onLogin = onLogin, onCancel = { withCode = false })
        }
        return
    }
    var saved by remember { mutableStateOf(accounts.all()) }
    var toDelete by remember { mutableStateOf<Account?>(null) }
    toDelete?.let { a ->
        DeleteAccountDialog(a, onConfirm = { accounts.delete(a); saved = accounts.all(); toDelete = null }, onDismiss = { toDelete = null })
    }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (busy) return
        val acc = XtreamApi.parseLogin(host, user, pass)
        if (acc == null) { error = "Completa servidor, usuario y contraseña (o pega el enlace de tu lista)"; return }
        busy = true; error = ""
        scope.launch {
            try { XtreamApi(acc).serverInfo(); onLogin(acc) }
            catch (e: Exception) { error = e.message ?: "No se pudo conectar con el servidor" }
            finally { busy = false }
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jx.Accent, unfocusedBorderColor = Jx.Line,
        focusedTextColor = Jx.Text, unfocusedTextColor = Jx.Text, cursorColor = Jx.Accent, focusedLabelColor = Jx.Accent, unfocusedLabelColor = Jx.Muted)
    LazyColumn(Modifier.fillMaxSize().background(Jx.Bg).statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onCancel != null) RoundIcon(R.drawable.ic_back, "Volver", onClick = onCancel)
                Column {
                    Text("JOX3 TV", style = PType.Hero, color = Jx.Accent)
                    Text("En vivo · Películas · Series", style = PType.Body, color = Jx.Muted)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            OutlinedTextField(host, { host = it }, label = { Text("Servidor o enlace de la lista") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), colors = fieldColors,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(user, { user = it }, label = { Text("Usuario") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), colors = fieldColors,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(pass, { pass = it }, label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), colors = fieldColors,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        }
        item { PButton(if (busy) "Conectando…" else "Entrar", Modifier.fillMaxWidth(), icon = R.drawable.ic_play) { submit() } }
        item {
            Text("Tengo un código JOX3", style = PType.Label, color = Jx.Accent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Jx.Surface)
                    .clickable { withCode = true }.padding(vertical = 14.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (error.isNotEmpty()) item { Text(error, style = PType.Small, color = Jx.Live) }
        item { Text("Puedes pegar en Servidor el enlace completo de tu lista (get.php?username=…).", style = PType.Small, color = Jx.Faint) }
        if (saved.isNotEmpty()) {
            item { Text("CUENTAS GUARDADAS", style = PType.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(top = 14.dp)) }
            items(saved, key = { it.key }) { acc ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Jx.Surface).clickable { onLogin(acc) }
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(acc.label, style = PType.Label, color = Jx.Text)
                        Text(acc.user, style = PType.Small, color = Jx.Faint)
                    }
                    RoundIcon(R.drawable.ic_delete, "Eliminar cuenta", tint = Jx.Muted) { toDelete = acc }
                }
            }
        }
    }
}


/** Carrusel del inicio: varias películas recientes que pasan solas. */
@Composable
private fun HeroCarousel(repo: TvRepo, launcher: PlayerLauncher, movies: List<Movie>, onOpenMovie: (Movie) -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState { movies.size }
    LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
        if (pager.isScrollInProgress || movies.size < 2) return@LaunchedEffect
        delay(6000)
        runCatching { pager.animateScrollToPage((pager.currentPage + 1) % movies.size) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager, contentPadding = PaddingValues(horizontal = 18.dp), pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        ) { page ->
            val m = movies[page]
            val info by produceState<MovieInfo?>(null, m.id) { value = runCatching { repo.api.movieInfo(m.id) }.getOrNull() }
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Jx.Surface).clickable { onOpenMovie(m) }) {
                RemoteImage(info?.backdrop?.ifBlank { null } ?: m.poster, m.name, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2070A0F)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NUEVO EN PELÍCULAS", style = PType.Eyebrow, color = Jx.Accent)
                    Text(m.name, style = PType.Hero, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton("Ver ahora", icon = R.drawable.ic_play) {
                            launcher.playMovie(m.id, m.name, m.poster, info?.ext?.ifBlank { null } ?: m.ext, m.categoryId)
                        }
                        PButton("Detalles", primary = false) { onOpenMovie(m) }
                    }
                }
            }
        }
        // Puntos de posición
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(movies.size) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(width = if (i == pager.currentPage) 18.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(if (i == pager.currentPage) Jx.Accent else Jx.Line))
            }
        }
    }
}

/** Canal reciente: logo, nombre y lo que dan ahora con su avance. */
@Composable
private fun LiveCard(c: Channel, repo: TvRepo, onClick: () -> Unit) {
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(c), c.id, version) { if (value.isNullOrEmpty()) value = repo.epg(c) }
    val now = epg?.let { repo.nowPlaying(it) }
    Column(
        Modifier.width(250.dp).clip(RoundedCornerShape(16.dp)).background(Jx.Surface).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteImage(c.logo, c.name, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Jx.SurfaceHi),
                scale = androidx.compose.ui.layout.ContentScale.Fit)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = PType.Label, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (now != null) "${clock(now.start)} – ${clock(now.end)}" else "En vivo", style = PType.Tiny, color = Jx.Muted)
            }
            Text("EN VIVO", style = PType.Tiny, color = Color.White,
                modifier = Modifier.background(Jx.Live, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Text(now?.title ?: "Sin guía", style = PType.Body, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Jx.Line)) {
            if (now != null) Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress()).background(Jx.Accent))
        }
    }
}

// ─────────────────────────── Mis servidores ───────────────────────────

/**
 * Todos los servidores (cuentas guardadas) con su estado: días para vencer, qué tan
 * rápido responde y conexiones. Tocar uno cambia a ese servidor; la papelera lo borra.
 */
@Composable
fun PhoneServers(accounts: AccountStore, current: Account, onSwitch: (Account) -> Unit, onAdd: () -> Unit,
                 onDeleted: (Account) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(accounts.all()) }
    var searchAll by remember { mutableStateOf(ServerHub.searchAllEnabled(ctx)) }
    var toDelete by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(Unit) { ServerHub.refresh(all, force = true) }
    toDelete?.let { a ->
        DeleteAccountDialog(a, onConfirm = {
            accounts.delete(a); all = accounts.all(); toDelete = null; onDeleted(a)
        }, onDismiss = { toDelete = null })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(R.drawable.ic_back, "Volver", onClick = onBack)
                Text("Mis servidores", style = PType.H1, color = Jx.Text, modifier = Modifier.weight(1f))
                RoundIcon(R.drawable.ic_refresh, "Actualizar estado", tint = Jx.TextSoft) { ServerHub.refresh(all, force = true) }
            }
        }
        items(all, key = { it.key }) { acc ->
            val st = ServerHub.status[acc.key]
            val isCur = acc.key == current.key
            val days = st?.daysLeft
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Jx.Surface)
                    .border(1.5.dp, if (isCur) Jx.Accent else Jx.Line, RoundedCornerShape(16.dp))
                    .clickable { if (!isCur) onSwitch(acc) }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(if (isCur) Jx.Accent else Jx.SurfaceHi), contentAlignment = Alignment.Center) {
                        Text(ServerHub.letter(all, acc), style = PType.Label, color = if (isCur) Jx.Bg else Jx.Text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(ServerHub.name(all, acc), style = PType.Label, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(com.jox3.iptvexo.tv.screens.statusColor(st)))
                            Text(when { isCur -> "EN USO"; st == null -> "Consultando…"; !st.ok -> "NO RESPONDE"; st.level == 1 -> "REVISAR"; else -> "ACTIVO" },
                                style = PType.Tiny, color = if (isCur) Jx.Accent else Jx.Muted)
                        }
                    }
                    RoundIcon(R.drawable.ic_delete, "Eliminar servidor", tint = Jx.Muted) { toDelete = acc }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Vence", when { days == null -> if (st?.ok == true) "Sin fecha" else "—"; days == 0L -> "Hoy"; else -> "$days días" },
                        if (days != null && days <= 5) Jx.Gold else Jx.Text, Modifier.weight(1f))
                    Stat("Respuesta", st?.takeIf { it.ok }?.let { "${it.pingMs} ms" } ?: "—",
                        if ((st?.pingMs ?: 0) > 1500) Jx.Gold else Jx.Text, Modifier.weight(1f))
                    Stat("Conexiones", st?.info?.let { "${it.activeCons} / ${it.maxCons}" } ?: "—", Jx.Text, Modifier.weight(1f))
                }
                if (!isCur) Text("Toca para cambiar a este servidor", style = PType.Tiny, color = Jx.Faint)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.5.dp, Jx.Line, RoundedCornerShape(16.dp))
                .clickable(onClick = onAdd).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("+", style = PType.H2, color = Jx.Accent)
                Text("Agregar servidor", style = PType.Label, color = Jx.Accent)
            }
        }
        if (all.size > 1) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Jx.Surface)
                .clickable { searchAll = !searchAll; ServerHub.setSearchAll(ctx, searchAll) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Buscar en todos los servidores", style = PType.Label, color = Jx.Text)
                    Text("El buscador muestra en cuál está cada título", style = PType.Tiny, color = Jx.Muted)
                }
                androidx.compose.material3.Switch(checked = searchAll, onCheckedChange = { searchAll = it; ServerHub.setSearchAll(ctx, it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Jx.Accent, checkedThumbColor = Jx.Bg))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Jx.SurfaceHi).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = PType.Tiny, color = Jx.Muted)
        Text(value, style = PType.Label, color = color, maxLines = 1)
    }
}




// ─────────────────────────── Información rica (TMDB) ───────────────────────────

@Composable
fun PhoneRating(info: com.jox3.iptvexo.tv.data.Tmdb.Info?, fallback: String = "") {
    val rating = info?.ratingText?.ifBlank { null } ?: fallback.ifBlank { null }
    val cert = info?.certification.orEmpty()
    if (rating == null && cert.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rating != null) Text("★ $rating", style = PType.Label, color = Color(0xFF070A0F),
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFF5C518)).padding(horizontal = 7.dp, vertical = 1.dp))
        if (cert.isNotBlank()) Text(cert, style = PType.Small, color = Jx.TextSoft,
            modifier = Modifier.border(1.dp, Jx.Muted, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        if (info != null && info.votes > 0) Text("TMDB", style = PType.Tiny, color = Jx.Faint)
    }
}

/** Reparto con fotos, deslizable de lado. */
@Composable
fun PhoneCastRow(cast: List<com.jox3.iptvexo.tv.data.Tmdb.Person>) {
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Reparto")
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cast, key = { it.name + it.role }) { p ->
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RemoteImage(p.photo, p.name, Modifier.size(64.dp).clip(CircleShape))
                    Text(p.name, style = PType.Tiny, color = Jx.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (p.role.isNotBlank()) Text(p.role, style = PType.Tiny, color = Jx.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "También en": otros servidores (o la misma película en otra calidad aquí). Tocar uno la abre allá. */
@Composable
fun AvailableIn(others: List<com.jox3.iptvexo.tv.data.ServerHub.Copy>, onHere: (com.jox3.iptvexo.tv.data.ServerHub.Copy) -> Unit = {}) {
    val sc = com.jox3.iptvexo.tv.data.LocalServers.current ?: return
    if (others.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TAMBIÉN DISPONIBLE EN", style = PType.Eyebrow, color = Jx.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            others.forEach { c ->
                val here = c.account.key == sc.account.key
                val st = ServerHub.status[c.account.key]
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Jx.Surface)
                    .clickable { if (here) onHere(c) else sc.switchTo(c) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(com.jox3.iptvexo.tv.screens.statusColor(st)))
                    Column {
                        Text(if (here) "Este servidor" else "Servidor " + ServerHub.letter(sc.all, c.account), style = PType.Label, color = Jx.Text)
                        Text(c.tag.ifBlank { ServerHub.name(sc.all, c.account) }, style = PType.Tiny, color = Jx.Muted, maxLines = 1)
                    }
                }
            }
        }
    }
}


/** Tráiler con sonido dentro de la app (controles de YouTube con el dedo). */
@Composable
private fun PhoneTrailerDialog(key: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            com.jox3.iptvexo.tv.ui.TrailerPlayer(key, muted = false, touchControls = true,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center),
                onEnded = onDismiss,
                onError = { onDismiss(); com.jox3.iptvexo.tv.data.Tmdb.openTrailer(ctx, key) })
            RoundIcon(R.drawable.ic_close, "Cerrar tráiler", Modifier.statusBarsPadding().padding(10.dp), bg = Color(0x8C070A0F), onClick = onDismiss)
        }
    }
}
