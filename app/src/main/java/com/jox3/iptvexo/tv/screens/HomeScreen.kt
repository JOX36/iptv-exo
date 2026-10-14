package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
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
import com.jox3.iptvexo.tv.ui.ChannelTile
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.LandscapeCard
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.Safe
import com.jox3.iptvexo.tv.ui.ShelfRow
import com.jox3.iptvexo.tv.ui.StatusBox
import com.jox3.iptvexo.tv.ui.Type

private data class HomeData(
    val hero: Movie?,
    val heroInfo: MovieInfo?,
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
            val hero = movies.firstOrNull()
            val info = hero?.let { runCatching { repo.api.movieInfo(it.id) }.getOrNull() }
            HomeData(hero, info, movies, series)
        }
    }
    val lib = repo.library
    val progressVersion by lib.progressVersion
    val resume = remember(progressVersion) { lib.continueWatching() }
    val heroReq = remember { FocusRequester() }

    val result = state
    if (result == null) { StatusBox("Cargando…"); return }
    val data = result.getOrElse {
        StatusBox("No se pudo cargar el inicio: ${it.message}", onRetry = { reload++ }); return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // ── Destacado ──
        if (data.hero != null) item(key = "hero") {
            Hero(data.hero, data.heroInfo, heroReq,
                onPlay = { host.player.playMovie(data.hero.id, data.hero.name, data.hero.poster, data.heroInfo?.ext?.ifBlank { null } ?: data.hero.ext, data.hero.categoryId) },
                onInfo = { nav.open(Route.MovieDetail(data.hero)) })
        } else item(key = "pad") { Box(Modifier.height(Safe.V)) }

        // ── Continuar viendo ──
        if (resume.isNotEmpty()) item(key = "resume") {
            ShelfRow("Continuar viendo") {
                items(resume, key = { "r" + it.item.id }) { r ->
                    LandscapeCard(
                        title = r.item.name.substringBefore(" · ").ifBlank { r.item.name },
                        subtitle = (if (r.item.kind == "episode") r.item.name.substringAfter(" · ", "") + " — " else "") +
                            "quedan ${r.remainingMin} min",
                        image = r.item.image,
                        progress = r.fraction,
                        onClick = {
                            if (r.item.kind == "episode") nav.open(Route.SeriesDetail(r.item.seriesId, r.item.name.substringBefore(" · "), r.item.image))
                            else host.player.playMovie(r.item.id, r.item.name, r.item.image, r.item.ext, r.item.categoryId)
                        },
                    )
                }
            }
        }

        // ── Canales recientes ──
        val recents = lib.recentChannels.toList()
        if (recents.isNotEmpty()) item(key = "recent") {
            val chans = recents.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
            ShelfRow("Canales recientes") {
                itemsIndexed(chans, key = { _, c -> "c" + c.id }) { i, c ->
                    ChannelTile(c.name, c.logo, onClick = { live.playFullscreen(chans, i, "Recientes") })
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

    LaunchedEffect(data.hero) { runCatching { heroReq.requestFocus() } }
}

@Composable
private fun Hero(movie: Movie, info: MovieInfo?, playReq: FocusRequester, onPlay: () -> Unit, onInfo: () -> Unit) {
    val backdrop = info?.backdrop?.ifBlank { null } ?: info?.poster?.ifBlank { null } ?: movie.poster
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        RemoteImage(backdrop, movie.name, Modifier.fillMaxSize(), showInitials = false)
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
        }
    }
}
