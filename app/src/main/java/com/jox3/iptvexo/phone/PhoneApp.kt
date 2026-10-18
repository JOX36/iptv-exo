package com.jox3.iptvexo.phone

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.PlayerLauncher
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.AccountStore
import com.jox3.iptvexo.tv.data.ServerHub
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.Section
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.data.XtreamApi
import com.jox3.iptvexo.tv.player.LivePlayback
import com.jox3.iptvexo.tv.player.VodPlayback
import com.jox3.iptvexo.tv.ui.Jx

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    Home("Inicio", R.drawable.tv_ic_home),
    Live("En vivo", R.drawable.tv_ic_live),
    Movies("Películas", R.drawable.tv_ic_movie),
    Series("Series", R.drawable.tv_ic_series),
    Search("Buscar", R.drawable.tv_ic_search),
}

/** Pantallas que se abren encima de la pestaña actual. */
private sealed interface Page {
    data class MovieDetail(val movie: Movie) : Page
    data class SeriesDetail(val id: String, val name: String, val poster: String) : Page
    data object Settings : Page
    data object Servers : Page
}

@Composable
fun PhoneApp(act: PhoneActivity) {
    val context = LocalContext.current
    val accounts = remember { AccountStore(context) }
    var account by remember { mutableStateOf(accounts.current()) }
    var adding by remember { mutableStateOf(false) }
    val acc = account
    if (acc == null || adding) {
        PhoneLogin(accounts, onLogin = { a -> accounts.use(a); account = a; adding = false },
            onCancel = if (acc != null) ({ adding = false }) else null)
        BackHandler(enabled = acc != null) { adding = false }
        return
    }
    key(acc.key) {
        val repo = remember { TvRepo(context.applicationContext, XtreamApi(acc), LibraryStore(context, acc.key)) }
        PhoneShell(act, repo, accounts, acc,
            onSwitch = { a -> accounts.use(a); account = a },
            onAdd = { adding = true },
            onLogout = { accounts.delete(acc); account = accounts.all().firstOrNull()?.also { accounts.use(it) } })
    }
}

@Composable
private fun PhoneShell(
    act: PhoneActivity, repo: TvRepo, accounts: AccountStore, account: Account,
    onSwitch: (Account) -> Unit, onAdd: () -> Unit, onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val live = remember { LivePlayback(context.applicationContext, repo) }
    val vod = remember { VodPlayback(context.applicationContext, repo.library) }
    val launcher = remember { PlayerLauncher().also { it.repo = repo; it.vod = vod } }
    vod.beforeOpen = { live.stop() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { live.onPause(); vod.onPause() }
                Lifecycle.Event.ON_RESUME -> { live.onResume(); vod.onResume() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); live.release(); vod.release() }
    }
    // La guía de internet empieza a prepararse en segundo plano
    LaunchedEffect(repo) { runCatching { repo.categories(Section.LIVE) } }

    // ── Chromecast ──
    val cast = remember { PhoneCast(context) }
    DisposableEffect(cast) {
        cast.onDisconnected = { pos ->
            // Al cortar, se sigue en el celular (películas y series, en el minuto del TV)
            live.player?.volume = 1f; vod.player?.volume = 1f
            if (vod.open) vod.player?.let { p -> if (pos > 0) p.seekTo(pos); p.play() }
            else live.player?.play()
        }
        cast.start()
        onDispose { cast.release() }
    }
    val castingTo = cast.connectedTo
    // Lo que se ve (canal, película o episodio) se manda al Chromecast; al cambiar de canal o episodio, también
    LaunchedEffect(castingTo, live.current?.id, vod.item?.id, vod.open) {
        if (castingTo == null) return@LaunchedEffect
        val item = vod.item
        val ch = live.current
        when {
            vod.open && item != null -> cast.load(CastMedia(item.url, item.subtitle.takeIf { it.isNotBlank() && it != "Película" }
                ?.let { "${item.title} · $it" } ?: item.title, item.image, live = false, startMs = vod.position))
            ch != null -> cast.load(CastMedia(repo.api.liveUrl(ch.id), ch.name, ch.logo, live = true))
        }
    }
    // Mientras se transmite, el celular no suena ni reproduce (evita el audio doble)
    LaunchedEffect(castingTo, live.playingNow, vod.playing) {
        if (castingTo == null) return@LaunchedEffect
        live.player?.let { it.volume = 0f; it.pause() }
        vod.player?.let { it.volume = 0f; it.pause() }
    }

    var tab by remember { mutableStateOf(Tab.Home) }
    val pages = remember { mutableStateListOf<Page>() }
    // Fuera de En vivo el canal se corta (salvo que se esté viendo a pantalla completa)
    LaunchedEffect(tab) { if (tab != Tab.Live) live.stop() }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val liveActive = tab == Tab.Live && live.current != null && !vod.open
    val fullscreen = landscape && (vod.open || liveActive)
    LaunchedEffect(fullscreen) { act.setFullscreenMode(fullscreen) }

    fun openMovie(m: Movie) { pages.add(Page.MovieDetail(m)) }
    fun openSeries(id: String, name: String, poster: String) { pages.add(Page.SeriesDetail(id, name, poster)) }
    fun playChannel(list: List<com.jox3.iptvexo.tv.data.Channel>, i: Int) {
        val c = list.getOrNull(i) ?: return
        repo.library.addRecentChannel(SavedItem("live", c.id, c.name, c.logo, categoryId = c.categoryId))
        vod.close(); pages.clear(); tab = Tab.Live
        live.play(c, list, "")
    }

    // ── Varios servidores ──
    LaunchedEffect(account.key) {
        val all = accounts.all()
        ServerHub.refresh(all)
        if (all.size > 1 && ServerHub.searchAllEnabled(context)) {
            kotlinx.coroutines.delay(4000)
            ServerHub.ensureIndexes(all.filter { it.key != account.key })
        }
    }
    // Se llegó aquí desde el buscador de otro servidor: abrir lo que se eligió
    LaunchedEffect(account.key) {
        val p = ServerHub.pendingOpen ?: return@LaunchedEffect
        if (p.account.key != account.key) return@LaunchedEffect
        ServerHub.pendingOpen = null
        when (p.kind) {
            "movie" -> openMovie(Movie(p.id, p.name, p.image, p.rating, p.ext, p.categoryId, 0L))
            "series" -> openSeries(p.id, p.name, p.image)
            "live" -> playChannel(listOf(com.jox3.iptvexo.tv.data.Channel(p.id, p.name, p.image, p.categoryId, 1)), 0)
        }
    }

    BackHandler {
        when {
            fullscreen -> act.exitLandscape()
            vod.open -> vod.close()
            pages.isNotEmpty() -> pages.removeAt(pages.lastIndex)
            tab != Tab.Home -> tab = Tab.Home
            else -> act.finish()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalCast provides cast) {
    Box(Modifier.fillMaxSize().background(if (fullscreen) androidx.compose.ui.graphics.Color.Black else Jx.Bg)) {
        if (fullscreen) {
            if (vod.open) VodFullscreenPhone(vod) { act.exitLandscape() }
            else LiveFullscreenPhone(live, repo) { act.exitLandscape() }
            return@Box
        }
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            val page = pages.lastOrNull()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vod.open -> Box(Modifier.statusBarsPadding()) {
                        VodScreen(vod, repo.library, onFullscreen = { act.enterLandscape() }, onClose = { vod.close() }, repo = repo,
                            onPlayMovie = { m -> launcher.playMovie(m.id, m.name, m.poster, m.ext, m.categoryId) })
                    }
                    page is Page.MovieDetail -> key(page.movie.id) {
                        PhoneMovieDetail(repo, launcher, page.movie, onOpenMovie = ::openMovie) { pages.removeAt(pages.lastIndex) }
                    }
                    page is Page.SeriesDetail -> PhoneSeriesDetail(repo, launcher, page.id, page.name, page.poster) { pages.removeAt(pages.lastIndex) }
                    page is Page.Servers -> Box(Modifier.statusBarsPadding()) {
                        PhoneServers(accounts, account, onSwitch = onSwitch, onAdd = onAdd,
                            onDeleted = { a -> if (a.key == account.key) onLogout() }) { pages.removeAt(pages.lastIndex) }
                    }
                    page is Page.Settings -> Box(Modifier.statusBarsPadding()) {
                        PhoneSettings(repo, act, account, onAddAccount = onAdd, onLogout = onLogout,
                            onServers = { pages.add(Page.Servers) }) { pages.removeAt(pages.lastIndex) }
                    }
                    tab == Tab.Live -> Box(Modifier.statusBarsPadding()) {
                        PhoneLiveScreen(repo, live, onFullscreen = { act.enterLandscape() })
                    }
                    else -> Column(Modifier.statusBarsPadding()) {
                        Header(accounts, account, onSwitch, onAdd, onSettings = { pages.add(Page.Settings) },
                            onServers = { pages.add(Page.Servers) })
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                Tab.Home -> PhoneHome(repo, launcher, ::openMovie, ::openSeries, ::playChannel)
                                Tab.Movies -> PhoneCatalog(repo, Section.VOD, ::openMovie, ::openSeries)
                                Tab.Series -> PhoneCatalog(repo, Section.SERIES, ::openMovie, ::openSeries)
                                Tab.Search -> PhoneSearch(repo, ::openMovie, ::openSeries, ::playChannel,
                                    account = account, accounts = accounts.all(), onSwitch = onSwitch)
                                Tab.Live -> Unit
                            }
                        }
                    }
                }
            }
            if (!vod.open) BottomBar(tab) { t -> pages.clear(); tab = t }
        }
    }
    }
}

@Composable
private fun Header(accounts: AccountStore, account: Account, onSwitch: (Account) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit,
                   onServers: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("J3", style = PType.H1, color = Jx.Accent)
        Spacer(Modifier.size(12.dp))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Jx.Surface).clickable { menu = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(account.label, style = PType.Label, color = Jx.TextSoft, maxLines = 1)
                Icon(painterResource(R.drawable.ic_chevron_down), "Cambiar de cuenta", Modifier.size(14.dp), tint = Jx.Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Jx.Surface) {
                accounts.all().forEach { a ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(a.label, style = PType.Label, color = if (a.key == account.key) Jx.Accent else Jx.Text)
                                Text(a.user, style = PType.Tiny, color = Jx.Muted)
                            }
                        },
                        onClick = { menu = false; if (a.key != account.key) onSwitch(a) },
                    )
                }
                DropdownMenuItem(text = { Text("Mis servidores", style = PType.Label, color = Jx.Text) }, onClick = { menu = false; onServers() })
                DropdownMenuItem(text = { Text("+ Agregar servidor", style = PType.Label, color = Jx.Accent) }, onClick = { menu = false; onAdd() })
            }
        }
        Spacer(Modifier.weight(1f))
        RoundIcon(R.drawable.tv_ic_settings, "Ajustes y cuenta", tint = Jx.TextSoft, onClick = onSettings)
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Jx.Rail).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.values().forEach { t ->
            val sel = t == current
            Column(
                Modifier.weight(1f).height(64.dp).clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) Jx.SurfaceHi else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Icon(painterResource(t.icon), null, Modifier.size(22.dp), tint = if (sel) Jx.Accent else Jx.Muted)
                }
                Text(t.label, style = PType.Tiny, color = if (sel) Jx.Accent else Jx.Muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
