package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.Nav
import com.jox3.iptvexo.tv.Route
import com.jox3.iptvexo.tv.TvHost
import com.jox3.iptvexo.tv.player.LocalLive
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.ServerInfo
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.ActionButton
import com.jox3.iptvexo.tv.ui.ChannelTile
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.Safe
import com.jox3.iptvexo.tv.ui.ShelfRow
import com.jox3.iptvexo.tv.ui.StatusBox
import com.jox3.iptvexo.tv.ui.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Favoritos: canales, películas y series. OK mantenido quita un favorito. */
@Composable
fun FavoritesScreen(repo: TvRepo, nav: Nav, host: TvHost) {
    val player = LocalLive.current
    val lib = repo.library
    val favs = lib.favorites.toList()
    val live = favs.filter { it.kind == "live" }
    val movies = favs.filter { it.kind == "movie" }
    val series = favs.filter { it.kind == "series" }

    if (favs.isEmpty()) {
        StatusBox("Aún no tienes favoritos.\nMantén pulsado OK sobre un canal, película o serie para agregarlo.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Safe.V, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "title") {
            Text("Favoritos", style = Type.H1, color = Jx.Text, modifier = Modifier.padding(start = Safe.H))
        }
        if (live.isNotEmpty()) item(key = "live") {
            val chans = live.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
            ShelfRow("Canales") {
                itemsIndexed(chans, key = { _, c -> "c" + c.id }) { i, c ->
                    ChannelTile(c.name, c.logo, onClick = { player.playFullscreen(chans, i, "Favoritos") },
                        onLongClick = { lib.toggleFavorite(live[i]) })
                }
            }
        }
        if (movies.isNotEmpty()) item(key = "movies") {
            ShelfRow("Películas") {
                items(movies, key = { "m" + it.id }) { s ->
                    PosterCard(s.name, s.image, onClick = {
                        nav.open(Route.MovieDetail(Movie(s.id, s.name, s.image, "", s.ext, s.categoryId, 0)))
                    }, onLongClick = { lib.toggleFavorite(s) })
                }
            }
        }
        if (series.isNotEmpty()) item(key = "series") {
            ShelfRow("Series") {
                items(series, key = { "s" + it.id }) { s ->
                    PosterCard(s.name, s.image, onClick = { nav.open(Route.SeriesDetail(s.id, s.name, s.image)) },
                        onLongClick = { lib.toggleFavorite(s) })
                }
            }
        }
    }
}

/** Cuenta, actualizaciones y opciones de la app. */
@Composable
fun SettingsScreen(repo: TvRepo, host: TvHost, account: Account, onLogout: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var recordings by remember { mutableStateOf(false) }
    if (recordings) { RecordingsScreen(tv = true) { recordings = false }; return }
    var organize by remember { mutableStateOf(false) }
    if (organize) { CategoryOrganizer(repo, tv = true) { organize = false }; return }
    val info by produceState<Result<ServerInfo>?>(null) { value = runCatching { repo.api.serverInfo() } }
    val firstReq = remember { FocusRequester() }
    val date = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es"))

    Row(Modifier.fillMaxSize().padding(horizontal = Safe.H, vertical = Safe.V), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ajustes y cuenta", style = Type.H1, color = Jx.Text)
            Card {
                Line("Servidor", account.label)
                Line("Usuario", account.user)
                val i = info?.getOrNull()
                when {
                    info == null -> Line("Estado", "Consultando…")
                    i == null -> Line("Estado", "No se pudo consultar")
                    else -> {
                        Line("Estado", if (i.status.equals("Active", true)) "Activa" else i.status,
                            if (i.status.equals("Active", true)) Jx.Ok else Jx.Live)
                        val exp = i.expires
                        if (exp != null) {
                            val days = ((exp - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                            Line("Vence", date.format(Date(exp)))
                            Line("Días restantes", "$days", when { days < 7 -> Jx.Live; days < 30 -> Jx.Gold; else -> Jx.Ok })
                        } else Line("Vence", "Sin fecha")
                        Line("Conexiones", "${i.activeCons} / ${i.maxCons}")
                    }
                }
            }
        }
        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 52.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Buscar actualizaciones", host::checkUpdates, Modifier.fillMaxWidth().focusRequester(firstReq),
                icon = R.drawable.tv_ic_update)
            ActionButton("⏺ Grabaciones", { recordings = true }, Modifier.fillMaxWidth(), icon = R.drawable.ic_record)
            ActionButton("Organizar categorías", { organize = true }, Modifier.fillMaxWidth(), icon = R.drawable.tv_ic_list)
            ActionButton("Cambiar de cuenta", onLogout, Modifier.fillMaxWidth(), icon = R.drawable.tv_ic_logout)
            // Se pide confirmar: la primera pulsación solo cambia el texto
            ActionButton(if (confirmDelete) "OK otra vez para eliminar" else "Eliminar esta cuenta",
                { if (confirmDelete) onDelete() else confirmDelete = true },
                Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) confirmDelete = false },
                icon = R.drawable.ic_delete)
            if (confirmDelete) Text("Se borra del equipo con sus favoritos e historial.", style = Type.Small, color = Jx.Live)
            // Ajuste automático de la frecuencia de la TV (películas y series)
            val sctx = androidx.compose.ui.platform.LocalContext.current
            var afr by remember { mutableStateOf(com.jox3.iptvexo.tv.GoogleTv.afrEnabled(sctx)) }
            ActionButton("Ajustar la TV al video: " + if (afr) "Sí" else "No", {
                afr = !afr; com.jox3.iptvexo.tv.GoogleTv.setAfr(sctx, afr)
            }, Modifier.fillMaxWidth())
            Text("Cambia la frecuencia de la TV (24, 25, 50, 60 Hz) a la de cada película para que se vea fluida. " +
                "Al empezar, la pantalla puede quedar negra un segundo.", style = Type.Small, color = Jx.Faint)
            // Diagnóstico: qué se publicó en la pantalla de inicio de la TV
            Text("INICIO DE LA TV", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(top = 6.dp))
            Text("Inicio del equipo: " + com.jox3.iptvexo.tv.GoogleTv.homeLauncher(sctx), style = Type.Small, color = Jx.TextSoft)
            Text(com.jox3.iptvexo.tv.GoogleTv.continueStatus, style = Type.Small, color = Jx.TextSoft)
            if (com.jox3.iptvexo.tv.GoogleTv.channelStatus.isNotBlank())
                Text(com.jox3.iptvexo.tv.GoogleTv.channelStatus, style = Type.Small, color = Jx.TextSoft)
            ActionButton("Publicar en el inicio ahora", {
                com.jox3.iptvexo.tv.GoogleTv.syncContinue(sctx, account, repo.library)
                com.jox3.iptvexo.tv.GoogleTv.syncFavoritesChannel(sctx, account, repo.library.favorites.toList()) { "" }
            }, Modifier.fillMaxWidth())
            var bgTrailer by remember { mutableStateOf(com.jox3.iptvexo.tv.data.Tmdb.backgroundTrailers(sctx)) }
            ActionButton("Tráiler de fondo: " + if (bgTrailer) "Sí" else "No", {
                bgTrailer = !bgTrailer; com.jox3.iptvexo.tv.data.Tmdb.setBackgroundTrailers(sctx, bgTrailer)
            }, Modifier.fillMaxWidth())
            Text("En la ficha de las películas, el tráiler oficial se ve sin sonido detrás del título.", style = Type.Small, color = Jx.Faint)
            Text("JOX3 TV ${host.appVersion()}", style = Type.Small, color = Jx.Faint, modifier = Modifier.padding(top = 8.dp))
            Text("Las versiones nuevas se instalan solas: la app avisa al abrirse.", style = Type.Small, color = Jx.Faint)
        }
    }
    LaunchedEffect(Unit) { runCatching { firstReq.requestFocus() } }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Jx.Surface, RoundedCornerShape(12.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Line(label: String, value: String, color: androidx.compose.ui.graphics.Color = Jx.Text) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Type.Label, color = Jx.Muted, modifier = Modifier.weight(1f))
        Text(value, style = Type.Label, color = color)
    }
}


