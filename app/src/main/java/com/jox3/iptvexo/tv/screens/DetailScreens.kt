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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.jox3.iptvexo.tv.ui.rememberFocusMemory
import com.jox3.iptvexo.tv.ui.focusMemoryItem
import com.jox3.iptvexo.tv.ui.focusMemoryGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.jox3.iptvexo.tv.ui.safeFocusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.Nav
import com.jox3.iptvexo.tv.Route
import com.jox3.iptvexo.tv.TvHost
import com.jox3.iptvexo.tv.data.Episode
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.ActionButton
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.Safe
import com.jox3.iptvexo.tv.ui.ThinProgress
import com.jox3.iptvexo.tv.ui.Type
import com.jox3.iptvexo.tv.ui.rememberTmdb
import com.jox3.iptvexo.tv.ui.rememberOtherCopies
import com.jox3.iptvexo.tv.ui.RatingBadges
import com.jox3.iptvexo.tv.ui.CastRow
import com.jox3.iptvexo.tv.data.Tmdb
import com.jox3.iptvexo.tv.data.ServerHub
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Ficha de película a pantalla completa. */
@Composable
fun MovieDetailScreen(repo: TvRepo, nav: Nav, host: TvHost, movie: Movie) {
    val lib = repo.library
    val info by produceState<com.jox3.iptvexo.tv.data.MovieInfo?>(null, movie.id) {
        value = runCatching { repo.api.movieInfo(movie.id) }.getOrNull()
    }
    // Parecidas por género (no solo de la misma carpeta)
    val similar by produceState(emptyList<Movie>(), movie.id, info) {
        if (info == null) return@produceState
        value = runCatching { repo.similarMovies(movie, info?.genre.orEmpty(), 16) }.getOrDefault(emptyList())
    }
    val progressVersion by lib.progressVersion
    val (pos, dur) = remember(progressVersion, movie.id) { lib.positionOf(movie.id) }
    val canResume = pos > 5000 && dur > 0 && pos.toFloat() / dur < 0.95f
    val playReq = remember { FocusRequester() }
    val ext = info?.ext?.ifBlank { null } ?: movie.ext
    val isFav = lib.isFavorite("movie", movie.id)
    val favItem = SavedItem("movie", movie.id, movie.name, movie.poster, ext = movie.ext, categoryId = movie.categoryId)
    // TMDB completa calificación, reparto con fotos, tráiler y lo que falte
    val tm = rememberTmdb("movie", movie.name)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val others = rememberOtherCopies("movie", movie.name, movie.id, repo)
    val servers = com.jox3.iptvexo.tv.data.LocalServers.current

    Box(Modifier.fillMaxSize()) {
        val i = info
        RemoteImage(i?.backdrop?.ifBlank { null } ?: tm?.backdrop?.ifBlank { null } ?: movie.poster, movie.name, Modifier.fillMaxSize(), showInitials = false)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Jx.Bg, 0.4f to Jx.Bg.copy(alpha = 0.94f), 0.75f to Jx.Bg.copy(alpha = 0.35f), 1f to Jx.Bg.copy(alpha = 0.1f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 0.75f to Jx.Bg)))

        // Se desplaza sola al bajar con el control (reparto, más servidores, sugerencias)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp, bottom = 24.dp)) {
            Column(Modifier.padding(start = Safe.H).width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(movie.name, style = Type.Hero, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                RatingBadges(tm, i?.rating?.ifBlank { null } ?: movie.rating)
                val meta = listOfNotNull(
                    i?.year?.ifBlank { null } ?: tm?.year?.ifBlank { null },
                    i?.duration?.ifBlank { null } ?: tm?.runtimeMin?.takeIf { it > 0 }?.let { "${it / 60} h ${it % 60} min" },
                    i?.genre?.ifBlank { null } ?: tm?.genres?.ifBlank { null },
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, style = Type.Label, color = Jx.TextSoft)
                val plot = i?.plot?.ifBlank { null } ?: tm?.overview?.ifBlank { null }
                Text(
                    when {
                        info == null && plot == null -> "Cargando información…"
                        plot == null -> "Sin sinopsis disponible."
                        else -> plot
                    },
                    style = Type.Body, color = Jx.TextSoft, maxLines = if (tm?.cast.isNullOrEmpty()) 5 else 4, overflow = TextOverflow.Ellipsis,
                )
                if (!tm?.cast.isNullOrEmpty()) {
                    CastRow(tm!!.cast)
                    tm.director.takeIf { it.isNotBlank() }?.let { Text("Dirección: $it", style = Type.Small, color = Jx.Muted, maxLines = 1) }
                } else {
                    val credits = listOfNotNull(
                        i?.director?.ifBlank { null }?.let { "Dirección: $it" },
                        i?.cast?.ifBlank { null }?.let { "Reparto: $it" },
                    ).joinToString("  ·  ")
                    if (credits.isNotEmpty()) Text(credits, style = Type.Small, color = Jx.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }

                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canResume) {
                        ActionButton("Continuar · ${fmtTime(pos)}", {
                            host.player.playMovie(movie.id, movie.name, movie.poster, ext, movie.categoryId)
                        }, Modifier.focusRequester(playReq), icon = R.drawable.tv_ic_play, primary = true, progress = pos.toFloat() / dur)
                        ActionButton("Desde el inicio", {
                            // Se borra la posición guardada: el reproductor arranca en 0 sin preguntar
                            lib.clearProgress(movie.id)
                            host.player.playMovie(movie.id, movie.name, movie.poster, ext, movie.categoryId)
                        }, icon = R.drawable.tv_ic_restart)
                    } else {
                        ActionButton("Reproducir", {
                            host.player.playMovie(movie.id, movie.name, movie.poster, ext, movie.categoryId)
                        }, Modifier.focusRequester(playReq), icon = R.drawable.tv_ic_play, primary = true)
                    }
                    ActionButton(if (isFav) "En favoritos" else "Favorito", { lib.toggleFavorite(favItem) },
                        icon = if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star)
                    if (!tm?.trailerKey.isNullOrBlank()) ActionButton("Tráiler", { Tmdb.openTrailer(ctx, tm!!.trailerKey) }, icon = R.drawable.tv_ic_play)
                }
                // Disponible también en otros servidores (o en otra calidad en este)
                if (others.isNotEmpty() && servers != null) {
                    Text("TAMBIÉN DISPONIBLE EN", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        others.take(4).forEach { c ->
                            val here = c.account.key == servers.account.key
                            val label = (if (here) "Aquí" else "Servidor " + ServerHub.letter(servers.all, c.account)) +
                                (if (c.tag.isNotBlank()) " · ${c.tag}" else "")
                            ActionButton(label, {
                                if (here) nav.open(Route.MovieDetail(Movie(c.id, c.name, c.image, c.rating, c.ext, c.categoryId, 0L)))
                                else servers.switchTo(c)
                            })
                        }
                    }
                }
            }

            if (similar.isNotEmpty()) {
                Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("También te puede gustar", style = Type.H2, color = Jx.Text, modifier = Modifier.padding(start = Safe.H))
                    LazyRow(
                        Modifier.safeFocusRestorer(),
                        contentPadding = PaddingValues(horizontal = Safe.H, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(similar, key = { it.id }) { m ->
                            PosterCard(m.name, m.poster, onClick = { nav.open(Route.MovieDetail(m)) }, width = 96.dp,
                                favorite = lib.isFavorite("movie", m.id))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(movie.id, canResume) { runCatching { playReq.requestFocus() } }
}

/** Serie: información a la izquierda; temporadas y episodios a la derecha. */
@Composable
fun SeriesDetailScreen(repo: TvRepo, host: TvHost, seriesId: String, name: String, poster: String) {
    val lib = repo.library
    var reload by remember { mutableIntStateOf(0) }
    val info by produceState<Result<com.jox3.iptvexo.tv.data.SeriesInfo>?>(null, seriesId, reload) {
        value = runCatching { repo.api.seriesInfo(seriesId) }
    }
    val progressVersion by lib.progressVersion
    val result = info
    val data = result?.getOrNull()
    val seasons = data?.seasons.orEmpty()
    val allEpisodes = remember(data) { seasons.values.flatten() }
    val tm = rememberTmdb("series", name)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val others = rememberOtherCopies("series", name, seriesId, repo)
    val servers = com.jox3.iptvexo.tv.data.LocalServers.current
    val cover = data?.poster?.ifBlank { null } ?: poster.ifBlank { null } ?: tm?.poster.orEmpty()
    val title = data?.name?.ifBlank { null } ?: name

    // Último episodio empezado → temporada y botón "Continuar"
    val resume = remember(allEpisodes, progressVersion) {
        allEpisodes.mapNotNull { e ->
            val (p, d) = lib.positionOf(e.id)
            if (p > 5000 && d > 0 && p.toFloat() / d < 0.95f) e to (p.toFloat() / d) else null
        }.lastOrNull()
    }
    var season by remember(data) { mutableStateOf(resume?.first?.season ?: seasons.keys.firstOrNull() ?: 1) }
    val episodes = seasons[season].orEmpty()
    val isFav = lib.isFavorite("series", seriesId)
    val mainReq = remember { FocusRequester() }
    val seasonMem = rememberFocusMemory()
    val epMem = rememberFocusMemory()
    LaunchedEffect(season) { seasonMem.remember(season) }

    Box(Modifier.fillMaxSize()) {
        // Fondo tenue a la izquierda
        Box(Modifier.width(420.dp).fillMaxHeight()) {
            RemoteImage(data?.backdrop?.ifBlank { null } ?: tm?.backdrop?.ifBlank { null } ?: cover, title, Modifier.fillMaxSize(), showInitials = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Jx.Bg.copy(alpha = 0.35f), 0.55f to Jx.Bg.copy(alpha = 0.88f), 1f to Jx.Bg)))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.8f to Color.Transparent, 1f to Jx.Bg)))
        }

        Row(Modifier.fillMaxSize()) {
            // ── Información ──
            Column(Modifier.width(380.dp).fillMaxHeight().padding(start = Safe.H, bottom = 32.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                Text(title, style = Type.Hero, color = Jx.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                RatingBadges(tm, data?.rating.orEmpty())
                val meta = listOfNotNull(
                    data?.year?.ifBlank { null } ?: tm?.year?.ifBlank { null },
                    seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 temporada" else "$it temporadas" },
                    data?.genre?.ifBlank { null } ?: tm?.genres?.ifBlank { null },
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, style = Type.Label, color = Jx.TextSoft, maxLines = 2)
                val plot = data?.plot?.ifBlank { null } ?: tm?.overview?.ifBlank { null }
                if (plot != null) {
                    Text(plot, style = Type.Body, color = Jx.TextSoft, maxLines = if (tm?.cast.isNullOrEmpty()) 6 else 4, overflow = TextOverflow.Ellipsis)
                }
                if (!tm?.cast.isNullOrEmpty()) CastRow(tm!!.cast, max = 4, photo = 40.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (resume != null) {
                        val (ep, frac) = resume
                        ActionButton("Continuar T${ep.season} · E${ep.num}", {
                            host.player.playEpisode(seriesId, title, cover, allEpisodes, allEpisodes.indexOf(ep))
                        }, icon = R.drawable.tv_ic_play, primary = true, progress = frac)
                    }
                    ActionButton(if (isFav) "En favoritos" else "Favorito", {
                        lib.toggleFavorite(SavedItem("series", seriesId, title, cover))
                    }, icon = if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star)
                    if (!tm?.trailerKey.isNullOrBlank()) ActionButton("Tráiler", { Tmdb.openTrailer(ctx, tm!!.trailerKey) })
                }
                // También en otros servidores
                if (others.isNotEmpty() && servers != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        others.filter { it.account.key != servers.account.key }.take(3).forEach { c ->
                            ActionButton("Ver en " + ServerHub.letter(servers.all, c.account) + (if (c.tag.isNotBlank()) " · ${c.tag}" else ""),
                                { servers.switchTo(c) })
                        }
                    }
                }
            }

            // ── Temporadas y episodios ──
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 27.dp, end = 40.dp)) {
                when {
                    result == null -> { com.jox3.iptvexo.tv.ui.StatusBox("Cargando episodios…"); return@Column }
                    data == null -> { com.jox3.iptvexo.tv.ui.StatusBox("No se pudo cargar la serie", onRetry = { reload++ }); return@Column }
                    seasons.isEmpty() -> { com.jox3.iptvexo.tv.ui.StatusBox("Esta serie aún no tiene episodios"); return@Column }
                }
                LazyRow(Modifier.focusMemoryGroup(seasonMem), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                    items(seasons.keys.toList(), key = { it }) { s ->
                        val sel = s == season
                        FocusSurface(
                            onClick = { season = s },
                            modifier = Modifier.height(36.dp).focusMemoryItem(seasonMem, s),
                            shape = RoundedCornerShape(18.dp),
                            focusedScale = 1.05f,
                            container = if (sel) Jx.Text else Color.Transparent,
                            content = if (sel) Jx.Bg else Jx.TextSoft,
                            focusedContainer = if (sel) Jx.Text else Jx.SurfaceHi,
                            focusedContent = if (sel) Jx.Bg else Jx.Text,
                        ) {
                            Text("Temporada $s", style = Type.Label, modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp))
                        }
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(top = 10.dp).focusRequester(mainReq).focusMemoryGroup(epMem),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 32.dp, top = 4.dp, start = 4.dp, end = 4.dp),
                ) {
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(ep, cover, lib.positionOf(ep.id).let { (p, d) -> if (d > 0) p.toFloat() / d else 0f },
                            Modifier.focusMemoryItem(epMem, ep.id)) {
                            host.player.playEpisode(seriesId, title, cover, allEpisodes, allEpisodes.indexOf(ep))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(data, season) { if (data != null) runCatching { mainReq.requestFocus() } }
}

@Composable
private fun EpisodeRow(ep: Episode, cover: String, watched: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (focused) 104.dp else 78.dp).onFocusChanged { focused = it.isFocused },
        focusedScale = 1.02f,
        focusedContainer = Jx.SurfaceHi,
    ) {
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().width(if (focused) 156.dp else 112.dp).clip(RoundedCornerShape(8.dp))) {
                RemoteImage(ep.image.ifBlank { cover }, ep.title.ifBlank { "E${ep.num}" }, Modifier.fillMaxSize())
                if (watched >= 0.95f) {
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).background(Color(0xCC070A0F), CircleShape),
                        contentAlignment = Alignment.Center) { JxIcon(R.drawable.tv_ic_check, 12.dp, Jx.Ok, "Visto") }
                } else if (watched > 0f) {
                    ThinProgress(watched, Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 3.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("EPISODIO ${ep.num}" + if (ep.duration.isNotBlank()) " · ${ep.duration}" else "",
                    style = Type.Eyebrow, color = if (focused) Jx.Accent else Jx.Muted)
                Text(ep.title.ifBlank { "Episodio ${ep.num}" }, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (focused && ep.plot.isNotBlank()) {
                    Text(ep.plot, style = Type.Small, color = Jx.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
