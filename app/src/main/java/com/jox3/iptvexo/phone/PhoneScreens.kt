package com.jox3.iptvexo.phone

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.PlayerLauncher
import com.jox3.iptvexo.tv.data.Account
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
            Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(Jx.Surface)) {
                if (hero != null) {
                    RemoteImage(d.heroInfo?.backdrop?.ifBlank { null } ?: hero.poster, hero.name, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2070A0F)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("NUEVO EN PELÍCULAS", style = PType.Eyebrow, color = Jx.Accent)
                        Text(hero.name, style = PType.Hero, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PButton("Ver ahora", icon = R.drawable.ic_play) {
                                launcher.playMovie(hero.id, hero.name, hero.poster, d.heroInfo?.ext?.ifBlank { null } ?: hero.ext, hero.categoryId)
                            }
                            PButton("Detalles", primary = false) { onOpenMovie(hero) }
                        }
                    }
                } else CenterText(if (d == null) "Cargando…" else "")
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
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(chans, key = { _, c -> c.id }) { i, c -> ChannelBubble(c.name, c.logo) { onPlayChannel(chans, i) } }
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

private class GridEntry(val id: String, val name: String, val poster: String, val rating: String, val kind: String, val open: () -> Unit)

@Composable
fun PhoneCatalog(repo: TvRepo, section: Section, onOpenMovie: (Movie) -> Unit, onOpenSeries: (String, String, String) -> Unit) {
    val lib = repo.library
    val cats by produceState<List<Category>?>(null, section) { value = runCatching { repo.categories(section) }.getOrDefault(emptyList()) }
    var selected by remember(section) { mutableStateOf<String?>(null) }
    LaunchedEffect(cats) { if (selected == null) selected = cats?.firstOrNull()?.id }
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
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { c -> Pill(c.name, c.id == selected) { selected = c.id } }
        }
        val e = entries
        when {
            e == null -> CenterText("Cargando…")
            e.isEmpty() -> CenterText("Categoría vacía")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
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

// ─────────────────────────── Detalles ───────────────────────────

@Composable
fun PhoneMovieDetail(repo: TvRepo, launcher: PlayerLauncher, movie: Movie, onBack: () -> Unit) {
    val lib = repo.library
    val info by produceState<MovieInfo?>(null, movie.id) { value = runCatching { repo.api.movieInfo(movie.id) }.getOrNull() }
    val progressVersion by lib.progressVersion
    val (pos, dur) = remember(progressVersion) { lib.positionOf(movie.id) }
    val canResume = pos > 0 && dur > 0 && pos.toFloat() / dur < 0.95f
    val fav = SavedItem("movie", movie.id, movie.name, movie.poster, ext = movie.ext, categoryId = movie.categoryId)
    val ext = info?.ext?.ifBlank { null } ?: movie.ext
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { DetailHeader(info?.backdrop?.ifBlank { null } ?: movie.poster, movie.name, onBack) }
        item {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(movie.name, style = PType.Hero, color = Jx.Text)
                val meta = listOfNotNull(info?.year?.ifBlank { null }, info?.genre?.ifBlank { null }, info?.duration?.ifBlank { null },
                    (info?.rating ?: movie.rating).ifBlank { null }?.let { "★ $it" }).joinToString(" · ")
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
                info?.plot?.takeIf { it.isNotBlank() }?.let { Text(it, style = PType.Body, color = Jx.TextSoft) }
                val credits = listOfNotNull(info?.director?.ifBlank { null }?.let { "Dirección: $it" }, info?.cast?.ifBlank { null }?.let { "Reparto: $it" })
                credits.forEach { Text(it, style = PType.Small, color = Jx.Muted) }
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
    val title = data?.name?.ifBlank { null } ?: name
    val cover = data?.poster?.ifBlank { null } ?: poster
    val isFav = lib.isFavorite("series", seriesId)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { DetailHeader(data?.backdrop?.ifBlank { null } ?: cover, title, onBack) }
        item {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = PType.Hero, color = Jx.Text)
                val meta = listOfNotNull(data?.year?.ifBlank { null }, data?.genre?.ifBlank { null },
                    seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 temporada" else "$it temporadas" },
                    data?.rating?.ifBlank { null }?.let { "★ $it" }).joinToString(" · ")
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
                data?.plot?.takeIf { it.isNotBlank() }?.let { Text(it, style = PType.Body, color = Jx.TextSoft, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            }
        }
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
private fun DetailHeader(image: String, title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        RemoteImage(image, title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66070A0F), Color.Transparent, Jx.Bg))))
        RoundIcon(R.drawable.ic_back, "Volver", Modifier.statusBarsPadding().padding(10.dp), bg = Color(0x8C070A0F), onClick = onBack)
    }
}

// ─────────────────────────── Buscar ───────────────────────────

private class SearchResults(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@Composable
fun PhoneSearch(repo: TvRepo, onOpenMovie: (Movie) -> Unit, onOpenSeries: (String, String, String) -> Unit, onPlayChannel: (List<Channel>, Int) -> Unit) {
    val lib = repo.library
    var query by remember { mutableStateOf("") }
    val index by produceState<TvRepo.Index?>(null) { value = runCatching { repo.searchIndex() }.getOrNull() }
    val results by produceState<SearchResults?>(null, query, index) {
        val idx = index ?: return@produceState
        val q = TvRepo.normalize(query.trim())
        if (q.length < 2) { value = null; return@produceState }
        delay(200)
        value = withContext(Dispatchers.Default) {
            fun <T> pick(list: List<T>, keys: List<String>): List<T> {
                val out = ArrayList<T>(40)
                for (i in keys.indices) { if (keys[i].contains(q)) { out.add(list[i]); if (out.size == 40) break } }
                return out
            }
            SearchResults(pick(idx.channels, idx.channelKeys), pick(idx.movies, idx.movieKeys), pick(idx.series, idx.seriesKeys))
        }
    }
    LaunchedEffect(results) { val r = results ?: return@LaunchedEffect; if (!r.isEmpty) { delay(4000); lib.addSearch(query) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Canales, películas y series", style = PType.Body) },
            singleLine = true, textStyle = PType.Body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jx.Accent, unfocusedBorderColor = Jx.Line,
                focusedTextColor = Jx.Text, unfocusedTextColor = Jx.Text, cursorColor = Jx.Accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
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
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (r.channels.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("En vivo · ${r.channels.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(r.channels, key = { _, c -> c.id }) { i, c -> ChannelBubble(c.name, c.logo) { lib.addSearch(query); onPlayChannel(r.channels, i) } }
                        }
                    }
                }
                if (r.movies.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Películas · ${r.movies.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(r.movies, key = { it.id }) { m -> PosterTile(m.name, m.poster, Modifier.width(108.dp)) { lib.addSearch(query); onOpenMovie(m) } }
                        }
                    }
                }
                if (r.series.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Series · ${r.series.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(r.series, key = { it.id }) { s -> PosterTile(s.name, s.poster, Modifier.width(108.dp)) { lib.addSearch(query); onOpenSeries(s.id, s.name, s.poster) } }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Ajustes ───────────────────────────

@Composable
fun PhoneSettings(repo: TvRepo, act: PhoneActivity, account: Account, onAddAccount: () -> Unit, onLogout: () -> Unit, onBack: () -> Unit) {
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
                PButton("Agregar otra cuenta", Modifier.fillMaxWidth(), icon = R.drawable.tv_ic_list, primary = false, onClick = onAddAccount)
                PButton("Cerrar sesión de esta cuenta", Modifier.fillMaxWidth(), icon = R.drawable.tv_ic_logout, primary = false, onClick = onLogout)
                PButton("Usar la interfaz anterior", Modifier.fillMaxWidth(), primary = false) { act.openClassicUi() }
                Text("JOX3 TV ${act.appVersion()}", style = PType.Small, color = Jx.Faint, modifier = Modifier.padding(top = 6.dp))
                Text("Las versiones nuevas se instalan solas: la app avisa al abrirse.", style = PType.Small, color = Jx.Faint)
            }
        }
    }
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
    val saved = remember { accounts.all() }
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
        if (error.isNotEmpty()) item { Text(error, style = PType.Small, color = Jx.Live) }
        item { Text("Puedes pegar en Servidor el enlace completo de tu lista (get.php?username=…).", style = PType.Small, color = Jx.Faint) }
        if (saved.isNotEmpty()) {
            item { Text("CUENTAS GUARDADAS", style = PType.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(top = 14.dp)) }
            items(saved, key = { it.key }) { acc ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Jx.Surface).clickable { onLogin(acc) }.padding(14.dp)) {
                    Text(acc.label, style = PType.Label, color = Jx.Text)
                    Text(acc.user, style = PType.Small, color = Jx.Faint)
                }
            }
        }
    }
}

