package com.jox3.iptvexo.tv.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.jox3.iptvexo.tv.ui.FocusSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.Nav
import com.jox3.iptvexo.tv.Route
import com.jox3.iptvexo.tv.TvHost
import com.jox3.iptvexo.tv.player.LocalLive
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.MovieInfo
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.Series
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.ActionButton
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.LandscapeCard
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.Safe
import com.jox3.iptvexo.tv.ui.ShelfRow
import com.jox3.iptvexo.tv.ui.StatusBox
import com.jox3.iptvexo.tv.ui.Type
import com.jox3.iptvexo.tv.ui.imageOf

private data class HomeData(
    val heroes: List<Movie>,
    val movies: List<Movie>,
    val series: List<Series>,
)

@Composable
fun HomeScreen(repo: TvRepo, nav: Nav, host: TvHost) {
    val live = LocalLive.current
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState<Result<HomeData>?>(null, reload) {
        value = runCatching {
            val movies = repo.latestMovies()
            val series = repo.latestSeries()
            // Destacado rotativo: las películas más recientes que tienen imagen
            val heroes = movies.filter { it.poster.isNotBlank() }.take(8).ifEmpty { movies.take(8) }
            HomeData(heroes, movies, series)
        }
    }
    val lib = repo.library
    val progressVersion by lib.progressVersion
    val resume = remember(progressVersion) { lib.continueWatching() }
    val becauseList = lib.because.value?.second.orEmpty()
    val fresh = com.jox3.iptvexo.tv.ui.rememberFreshImages(repo, remember(resume, becauseList) { resume.map { it.item } + becauseList })
    val heroReq = remember { FocusRequester() }

    val result = state
    if (result == null) { StatusBox("Cargando…"); return }
    val data = result.getOrElse {
        StatusBox("No se pudo cargar el inicio: ${it.message}", onRetry = { reload++ }); return
    }

    // Al volver al destacado se sube del todo: si no, la lista solo mostraba los botones
    // y el banner quedaba cortado arriba.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // ── Destacado ──
        if (data.heroes.isNotEmpty()) item(key = "hero") {
            HeroCarousel(repo, data.heroes, heroReq,
                modifier = Modifier.onFocusChanged { f ->
                    if (f.hasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0))
                        scope.launch { delay(30); listState.animateScrollToItem(0) }
                },
                onPlay = { m, info -> host.player.playMovie(m.id, m.name, m.poster, info?.ext?.ifBlank { null } ?: m.ext, m.categoryId) },
                onInfo = { m -> nav.open(Route.MovieDetail(m)) })
        } else item(key = "pad") { Box(Modifier.height(Safe.V)) }

        // ── Continuar viendo ──
        if (resume.isNotEmpty()) item(key = "resume") {
            ShelfRow("Continuar viendo") {
                items(resume, key = { "r" + it.item.id }) { r ->
                    LandscapeCard(
                        title = r.item.name.substringBefore(" · ").ifBlank { r.item.name },
                        subtitle = (if (r.item.kind == "episode") r.item.name.substringAfter(" · ", "") + " — " else "") +
                            "quedan ${r.remainingMin} min",
                        image = fresh.imageOf(r.item),
                        progress = r.fraction,
                        onClick = {
                            if (r.item.kind == "episode") nav.open(Route.SeriesDetail(r.item.seriesId, r.item.name.substringBefore(" · "), r.item.image))
                            else host.player.playMovie(r.item.id, r.item.name, fresh.imageOf(r.item), r.item.ext, r.item.categoryId)
                        },
                    )
                }
            }
        }

        // ── Canales recientes ──
        val recents = lib.recentChannels.toList()
        // ── Porque viste… (lo último que terminó) ──
        lib.because.value?.let { (title, recs) ->
            item(key = "because") {
                ShelfRow("Porque viste $title") {
                    items(recs, key = { "b" + it.kind + it.id }) { r ->
                        PosterCard(r.name, fresh.imageOf(r), onClick = {
                            if (r.kind == "series") nav.open(Route.SeriesDetail(r.id, r.name, r.image))
                            else nav.open(Route.MovieDetail(com.jox3.iptvexo.tv.data.Movie(r.id, r.name, r.image, "", r.ext, r.categoryId, 0L)))
                        }, subtitle = if (r.kind == "series") "Serie" else "Película",
                            favorite = lib.isFavorite(r.kind, r.id))
                    }
                }
            }
        }
        if (recents.isNotEmpty()) item(key = "recent") {
            val chans = recents.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
            ShelfRow("Canales recientes") {
                itemsIndexed(chans, key = { _, c -> "c" + c.id }) { i, c ->
                    LiveCard(c, repo, onClick = { live.playFullscreen(chans, i, "Recientes") })
                }
            }
        }

        // ── Películas y series recientes ──
        if (data.movies.isNotEmpty()) item(key = "movies") {
            ShelfRow("Películas recientes") {
                items(data.movies, key = { "m" + it.id }) { m ->
                    PosterCard(m.name, m.poster, onClick = { nav.open(Route.MovieDetail(m)) },
                        subtitle = m.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                        favorite = lib.isFavorite("movie", m.id),
                        onLongClick = { lib.toggleFavorite(SavedItem("movie", m.id, m.name, m.poster, ext = m.ext, categoryId = m.categoryId)) })
                }
            }
        }
        if (data.series.isNotEmpty()) item(key = "series") {
            ShelfRow("Series recientes") {
                items(data.series, key = { "s" + it.id }) { s ->
                    PosterCard(s.name, s.poster, onClick = { nav.open(Route.SeriesDetail(s.id, s.name, s.poster)) },
                        subtitle = s.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                        favorite = lib.isFavorite("series", s.id),
                        onLongClick = { lib.toggleFavorite(SavedItem("series", s.id, s.name, s.poster, categoryId = s.categoryId)) })
                }
            }
        }
    }

    LaunchedEffect(data.heroes.isNotEmpty()) { runCatching { heroReq.requestFocus() } }
}

/**
 * Destacado rotativo (como en el celular): cambia de película cada 8 s con un fundido.
 * El foco se queda en "Reproducir", que siempre abre la película que se está mostrando.
 */
@Composable
private fun HeroCarousel(
    repo: TvRepo, movies: List<Movie>, playReq: FocusRequester, modifier: Modifier = Modifier,
    onPlay: (Movie, MovieInfo?) -> Unit, onInfo: (Movie) -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    val infos = remember { mutableStateMapOf<String, MovieInfo?>() }
    LaunchedEffect(movies) {
        while (true) {
            delay(8_000)
            index = (index + 1) % movies.size
        }
    }
    val m = movies[index.coerceIn(0, movies.lastIndex)]
    LaunchedEffect(m.id) {
        if (!infos.containsKey(m.id)) infos[m.id] = runCatching { repo.api.movieInfo(m.id) }.getOrNull()
        // La siguiente se adelanta para que el cambio sea inmediato
        val next = movies[(index + 1) % movies.size]
        if (!infos.containsKey(next.id)) infos[next.id] = runCatching { repo.api.movieInfo(next.id) }.getOrNull()
    }
    Box(modifier.fillMaxWidth().height(330.dp)) {
        Crossfade(targetState = m, animationSpec = tween(600), label = "hero") { cur ->
            Box(Modifier.fillMaxSize()) {
                val info = infos[cur.id]
                RemoteImage(info?.backdrop?.ifBlank { null } ?: info?.poster?.ifBlank { null } ?: cur.poster, cur.name,
                    Modifier.fillMaxSize(), showInitials = false)
            }
        }
        Hero(m, infos[m.id], playReq, index, movies.size, onPlay = { onPlay(m, infos[m.id]) }, onInfo = { onInfo(m) })
    }
}

/** Canal reciente: logo grande y lo que está pasando ahora, con su avance. */
@Composable
private fun LiveCard(c: Channel, repo: TvRepo, onClick: () -> Unit) {
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(c), c.id, version) { if (value.isNullOrEmpty()) value = repo.epg(c) }
    val now = epg?.let { repo.nowPlaying(it) }
    val fmt = remember { SimpleDateFormat("h:mm a", Locale("es")) }
    FocusSurface(onClick = onClick, modifier = Modifier.width(300.dp).height(132.dp), focusedScale = 1.05f, container = Jx.Surface) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteImage(c.logo, c.name, Modifier.size(width = 84.dp, height = 56.dp).clip(RoundedCornerShape(10.dp))
                    .background(Jx.SurfaceHi).padding(4.dp), scale = ContentScale.Fit)
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = Type.Label, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (now != null) "${fmt.format(Date(now.start))} – ${fmt.format(Date(now.end))}" else "En vivo",
                        style = Type.Small, color = Jx.Muted, maxLines = 1)
                }
            }
            Text(now?.title ?: "Sin guía", style = Type.Small, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Jx.Line)) {
                if (now != null) Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress()).background(Jx.Accent))
            }
        }
    }
}

@Composable
private fun Hero(movie: Movie, info: MovieInfo?, playReq: FocusRequester, index: Int, count: Int, onPlay: () -> Unit, onInfo: () -> Unit) {
    // La imagen de fondo la pone HeroCarousel (con fundido); aquí van los degradados y el texto
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        Box(Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Jx.Bg, 0.35f to Jx.Bg.copy(alpha = 0.92f), 0.75f to Jx.Bg.copy(alpha = 0.2f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(0.6f to Color.Transparent, 1f to Jx.Bg)))

        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Safe.H, bottom = 8.dp).width(440.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("NUEVO EN PELÍCULAS", style = Type.Eyebrow, color = Jx.Accent)
            Text(movie.name, style = Type.Hero, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                info?.year?.ifBlank { null },
                info?.duration?.ifBlank { null },
                info?.genre?.ifBlank { null }?.substringBefore(",", ""),
                (info?.rating?.ifBlank { null } ?: movie.rating.ifBlank { null })?.let { "★ $it" },
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, style = Type.Label, color = Jx.TextSoft)
            if (!info?.plot.isNullOrBlank()) {
                Text(info!!.plot, style = Type.Body, color = Jx.TextSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                ActionButton("Reproducir", onPlay, Modifier.focusRequester(playReq), icon = R.drawable.tv_ic_play, primary = true)
                ActionButton("Más información", onInfo, icon = R.drawable.tv_ic_info)
            }
            if (count > 1) {
                // Puntos: cuál destacado se está viendo
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    repeat(count) { k ->
                        Box(Modifier.height(4.dp).width(if (k == index) 18.dp else 6.dp)
                            .background(if (k == index) Jx.Accent else Color(0x55FFFFFF), RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}
