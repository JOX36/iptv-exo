package com.jox3.iptvexo.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jox3.iptvexo.tv.player.LiveFullscreen
import com.jox3.iptvexo.tv.player.LivePlayback
import com.jox3.iptvexo.tv.player.LocalLive
import com.jox3.iptvexo.tv.player.VideoLayer
import com.jox3.iptvexo.tv.player.VodFullscreen
import com.jox3.iptvexo.tv.player.VodMini
import com.jox3.iptvexo.tv.player.VodPlayback
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.AccountStore
import com.jox3.iptvexo.tv.data.ServerHub
import com.jox3.iptvexo.tv.screens.ServerPanel
import com.jox3.iptvexo.tv.screens.statusColor
import androidx.compose.foundation.shape.CircleShape
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.data.XtreamApi
import com.jox3.iptvexo.tv.screens.FavoritesScreen
import com.jox3.iptvexo.tv.screens.HomeScreen
import com.jox3.iptvexo.tv.screens.LiveScreen
import com.jox3.iptvexo.tv.screens.LoginScreen
import com.jox3.iptvexo.tv.screens.MovieDetailScreen
import com.jox3.iptvexo.tv.screens.MoviesScreen
import com.jox3.iptvexo.tv.screens.SearchScreen
import com.jox3.iptvexo.tv.screens.SeriesDetailScreen
import com.jox3.iptvexo.tv.screens.SeriesScreen
import com.jox3.iptvexo.tv.screens.SettingsScreen
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.Type

/** Pantallas de la app. Las de la barra lateral son "secciones"; las demás se apilan encima. */
sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Live : Route
    data object Movies : Route
    data object Series : Route
    data object Favorites : Route
    data object Settings : Route
    data class MovieDetail(val movie: Movie) : Route
    data class SeriesDetail(val id: String, val name: String, val poster: String) : Route
}

/** Lo que las pantallas necesitan de la actividad. */
interface TvHost {
    val player: PlayerLauncher
    fun checkUpdates()
    fun appVersion(): String
}

/** Navegación disponible para cada pantalla. */
class Nav(private val stack: MutableList<Route>, private val beforeDetail: () -> Unit = {}) {
    fun open(route: Route) {
        if (route is Route.MovieDetail || route is Route.SeriesDetail) {
            // Recordar en qué tarjeta estaba el foco para volver a ella al cerrar
            if (stack.size == 1) beforeDetail()
            // Solo un nivel de detalle encima de la sección (abrir otro lo reemplaza)
            if (stack.size > 1) stack.removeAt(stack.size - 1)
            stack.add(route)
        } else {
            stack.clear(); stack.add(route)
        }
    }
    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
}

/** Lo que las pantallas pueden pedirle al marco: ocultar la barra lateral (En vivo compacto). */
class ShellChrome { var railHidden by mutableStateOf(false) }
val LocalShellChrome = androidx.compose.runtime.staticCompositionLocalOf { ShellChrome() }

@Composable
fun TvApp(host: TvHost) {
    val context = LocalContext.current
    val accounts = remember { AccountStore(context) }
    var account by remember { mutableStateOf(accounts.current()) }

    val acc = account
    if (acc == null) {
        LoginScreen(accounts) { a -> accounts.use(a); account = a }
        return
    }
    key(acc.key) {
        val repo = remember { TvRepo(context.applicationContext, XtreamApi(acc), LibraryStore(context, acc.key)) }
        host.player.repo = repo
        MainShell(repo, host, acc, accounts, onLogout = { accounts.logout(); account = null },
            onDelete = { accounts.delete(acc); account = null },
            onSwitch = { a -> accounts.use(a); account = a })
    }
}

@Composable
private fun MainShell(
    repo: TvRepo, host: TvHost, account: Account, accounts: AccountStore,
    onLogout: () -> Unit, onDelete: () -> Unit, onSwitch: (Account) -> Unit,
) {
    val context = LocalContext.current
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val baseFocus = remember { FocusRequester() }
    val nav = remember { Nav(stack) { runCatching { baseFocus.saveFocusedChild() } } }
    val section = stack.first()
    val detail = stack.lastOrNull()?.takeIf { stack.size > 1 }

    // ── Reproductor en vivo (uno solo: vista previa y pantalla completa) ──
    val live = remember { LivePlayback(context.applicationContext, repo) }
    live.beforeFullscreen = { runCatching { baseFocus.saveFocusedChild() } }
    // ── Reproductor de películas y series (pantalla completa) ──
    val vod = remember { VodPlayback(context.applicationContext, repo.library) }
    vod.beforeOpen = { runCatching { baseFocus.saveFocusedChild() }; live.stop() }
    host.player.vod = vod
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
        onDispose { lifecycle.removeObserver(obs); live.release(); vod.release(); host.player.vod = null }
    }
    // Fuera de En vivo no hay vista previa: el canal se corta (salvo en pantalla completa)
    LaunchedEffect(section, live.fullscreen) {
        if (section != Route.Live && !live.fullscreen) live.stop()
    }
    // La guía de internet empieza a descargarse en segundo plano desde el inicio
    LaunchedEffect(repo) { runCatching { repo.categories(com.jox3.iptvexo.tv.data.Section.LIVE) } }

    // ── Varios servidores ──
    val allAccounts = remember(account) { accounts.all() }
    var serverPanel by remember { mutableStateOf(false) }
    LaunchedEffect(account.key) {
        ServerHub.refresh(allAccounts)
        // El catálogo de los otros servidores se prepara en segundo plano para el buscador universal
        if (allAccounts.size > 1 && ServerHub.searchAllEnabled(context)) {
            delay(4000)
            ServerHub.ensureIndexes(allAccounts.filter { it.key != account.key })
        }
    }
    // Se llegó aquí desde el buscador de otro servidor: abrir lo que se eligió
    LaunchedEffect(account.key) {
        val p = ServerHub.pendingOpen ?: return@LaunchedEffect
        if (p.account.key != account.key) return@LaunchedEffect
        ServerHub.pendingOpen = null
        delay(150)
        when (p.kind) {
            "movie" -> nav.open(Route.MovieDetail(com.jox3.iptvexo.tv.data.Movie(p.id, p.name, p.image, p.rating, p.ext, p.categoryId, 0L)))
            "series" -> nav.open(Route.SeriesDetail(p.id, p.name, p.image))
            "live" -> live.playFullscreen(listOf(com.jox3.iptvexo.tv.data.Channel(p.id, p.name, p.image, p.categoryId, 1)), 0, "Búsqueda")
        }
    }
    var wasFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(live.fullscreen) {
        if (live.fullscreen) { wasFullscreen = true; return@LaunchedEffect }
        if (wasFullscreen) {
            wasFullscreen = false
            runCatching { if (!baseFocus.restoreFocusedChild()) baseFocus.requestFocus() }
        }
    }

    // ── Atrás: cierra el detalle → vuelve a Inicio → doble Atrás para salir ──
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler {
        when {
            vod.full -> vod.close()
            live.fullscreen -> live.exitFullscreen()
            detail != null -> nav.back()
            section != Route.Home -> nav.open(Route.Home)
            System.currentTimeMillis() - lastBack < 2000 -> (context as? android.app.Activity)?.finish()
            else -> {
                lastBack = System.currentTimeMillis()
                Toast.makeText(context, "Pulsa Atrás otra vez para salir", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Al cerrar un detalle, el foco vuelve a la tarjeta desde la que se abrió
    var hadDetail by remember { mutableStateOf(false) }
    LaunchedEffect(detail) {
        if (detail != null) { hadDetail = true; return@LaunchedEffect }
        if (hadDetail) {
            hadDetail = false
            runCatching { if (!baseFocus.restoreFocusedChild()) baseFocus.requestFocus() }
        }
    }

    // Red de seguridad del foco: si nada lo tiene (una tarjeta desapareció con el foco
    // encima, una lista se recargó…), el control dejaba de responder. Se devuelve solo.
    val detailReq = remember { FocusRequester() }
    var anyFocus by remember { mutableStateOf(true) }
    // Al salir de pantalla completa (cerrar o minimizar) el foco vuelve a donde estaba
    var wasVod by remember { mutableStateOf(false) }
    LaunchedEffect(vod.full) {
        if (vod.full) { wasVod = true; return@LaunchedEffect }
        if (wasVod) {
            wasVod = false
            delay(30)
            runCatching {
                if (detail != null) detailReq.requestFocus()
                else if (!baseFocus.restoreFocusedChild()) baseFocus.requestFocus()
            }
        }
    }
    // El reproductor pequeño se cierra al cambiar de sección (p. ej. de Películas a Inicio o En vivo)
    var miniSection by remember { mutableStateOf<Route?>(null) }
    LaunchedEffect(vod.mini, section) {
        if (!vod.mini) { miniSection = null; return@LaunchedEffect }
        if (miniSection == null) miniSection = section
        else if (miniSection != section) vod.close()
    }
    LaunchedEffect(anyFocus) {
        if (anyFocus) return@LaunchedEffect
        delay(350)
        if (anyFocus || vod.full || live.fullscreen) return@LaunchedEffect
        runCatching {
            if (detail != null) detailReq.requestFocus()
            else if (!baseFocus.restoreFocusedChild()) baseFocus.requestFocus()
        }
    }

    val chrome = remember { ShellChrome() }
    val serverCtx = remember(account, allAccounts) {
        com.jox3.iptvexo.tv.data.ServerCtx(account, allAccounts) { c -> ServerHub.pendingOpen = c; onSwitch(c.account) }
    }
    CompositionLocalProvider(LocalLive provides live, LocalShellChrome provides chrome,
        com.jox3.iptvexo.tv.data.LocalServers provides serverCtx) {
    Box(Modifier.fillMaxSize().background(Jx.Bg).onFocusChanged { anyFocus = it.hasFocus }
        // Con el reproductor pequeño: ⏯ pausa o sigue, ■ lo cierra
        .onPreviewKeyEvent { ev ->
            if (!vod.mini || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (ev.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { vod.togglePlay(); true }
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> { vod.close(); true }
                else -> false
            }
        }) {
        // Sección (queda debajo mientras hay un detalle o pantalla completa, sin recibir foco)
        val covered = detail != null || live.fullscreen || vod.full
        Box(
            Modifier
                .fillMaxSize()
                // Con un detalle encima, las flechas nunca "atraviesan" hacia la sección
                .focusProperties { enter = { if (covered) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup()
        ) {
          Box(Modifier.fillMaxSize().focusRequester(baseFocus).focusGroup()) {
            Row(Modifier.fillMaxSize()) {
                if (!chrome.railHidden) Spacer(Modifier.width(RailCollapsed))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    key(section) {
                        when (section) {
                            Route.Home -> HomeScreen(repo, nav, host)
                            Route.Search -> SearchScreen(repo, nav, host, account, allAccounts, onSwitch)
                            Route.Live -> LiveScreen(repo, host)
                            Route.Movies -> MoviesScreen(repo, nav)
                            Route.Series -> SeriesScreen(repo, nav)
                            Route.Favorites -> FavoritesScreen(repo, nav, host)
                            Route.Settings -> SettingsScreen(repo, host, account, onLogout, onDelete)
                            else -> Unit
                        }
                    }
                }
            }
            if (!chrome.railHidden) Rail(section, nav, allAccounts, account, { serverPanel = true }, Modifier.zIndex(2f))
          }
        }

        // Video en vivo: en la ventanita de vista previa o a pantalla completa
        VideoLayer(live, Modifier.zIndex(2.5f))

        // Detalle a pantalla completa
        if (detail != null) {
            Box(Modifier.fillMaxSize().background(Jx.Bg).zIndex(3f).focusRequester(detailReq).focusGroup()) {
                key(detail) {
                    when (detail) {
                        is Route.MovieDetail -> MovieDetailScreen(repo, nav, host, detail.movie)
                        is Route.SeriesDetail -> SeriesDetailScreen(repo, host, detail.id, detail.name, detail.poster)
                        else -> Unit
                    }
                }
            }
        }

        // Películas y series
        if (vod.full) {
            Box(Modifier.fillMaxSize().zIndex(5f)) {
                VodFullscreen(vod, repo.library, onExit = { vod.close() }, onMinimize = { vod.minimize() })
            }
        } else if (vod.open) {
            VodMini(vod, Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 20.dp).zIndex(4.5f))
        }

        // Panel de servidores (desde la barra lateral)
        if (serverPanel) {
            Box(Modifier.fillMaxSize().zIndex(6f)) {
                ServerPanel(allAccounts, account,
                    onPick = { a -> serverPanel = false; onSwitch(a) },
                    onAdd = { serverPanel = false; onLogout() },
                    onDismiss = { serverPanel = false })
            }
        }

        // Controles del reproductor en vivo a pantalla completa
        if (live.fullscreen && !vod.full) {
            Box(Modifier.fillMaxSize().zIndex(4f)) {
                LiveFullscreen(live, repo) {
                    live.exitFullscreen()
                    if (section != Route.Live) live.stop()
                }
            }
        }
    }
    }
}

private val RailCollapsed = 64.dp
private val RailExpanded = 220.dp

private data class RailItem(val route: Route, @DrawableRes val icon: Int, val label: String)

private val railItems = listOf(
    RailItem(Route.Search, R.drawable.tv_ic_search, "Buscar"),
    RailItem(Route.Home, R.drawable.tv_ic_home, "Inicio"),
    RailItem(Route.Live, R.drawable.tv_ic_live, "En vivo"),
    RailItem(Route.Movies, R.drawable.tv_ic_movie, "Películas"),
    RailItem(Route.Series, R.drawable.tv_ic_series, "Series"),
    RailItem(Route.Favorites, R.drawable.tv_ic_star, "Favoritos"),
)

/**
 * Barra lateral: solo iconos; al recibir el foco se despliega con los nombres
 * por encima del contenido (sin mover nada). Al entrar, el foco cae siempre en
 * la sección actual.
 */
@Composable
private fun Rail(
    current: Route, nav: Nav, accounts: List<Account>, account: Account, onServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) RailExpanded else RailCollapsed, tween(180), label = "rail")
    val currentReq = remember { FocusRequester() }

    Box(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) Brush.horizontalGradient(listOf(Jx.Rail, Jx.Rail, Color(0xEE0A0E14)))
                else Brush.horizontalGradient(listOf(Jx.Rail, Jx.Rail))
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { enter = { currentReq } }
            .focusGroup()
    ) {
        Column(
            Modifier.fillMaxHeight().padding(vertical = 24.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("J3", style = Type.H1, color = Jx.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
            // Servidor en uso: OK abre la lista de servidores
            ServerRailButton(accounts, account, expanded, onServers)
            Spacer(Modifier.height(8.dp))
            railItems.forEach { item ->
                RailButton(item.icon, item.label, item.route == current, expanded,
                    if (item.route == current) Modifier.focusRequester(currentReq) else Modifier) { nav.open(item.route) }
            }
            Spacer(Modifier.weight(1f))
            val settingsSelected = current == Route.Settings
            RailButton(R.drawable.tv_ic_settings, "Ajustes y cuenta", settingsSelected, expanded,
                if (settingsSelected) Modifier.focusRequester(currentReq) else Modifier) { nav.open(Route.Settings) }
        }
    }
}

@Composable
private fun RailButton(
    @DrawableRes icon: Int, label: String, selected: Boolean, expanded: Boolean,
    modifier: Modifier, onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1f,
        container = if (selected) Jx.SurfaceHi else Color.Transparent,
        focusedContainer = Jx.Text,
        content = if (selected) Jx.Accent else Jx.Muted,
        focusedContent = Jx.Bg,
        ring = false,
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JxIcon(icon, 20.dp, description = label)
            if (expanded) Text(label, style = Type.Label, maxLines = 1)
        }
    }
}


/** Botón del servidor en uso (letra + punto de estado); desplegado muestra su nombre. */
@Composable
private fun ServerRailButton(accounts: List<Account>, account: Account, expanded: Boolean, onClick: () -> Unit) {
    val st = ServerHub.status[account.key]
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1f,
        container = Color.Transparent,
        focusedContainer = Jx.Text,
        content = Jx.TextSoft,
        focusedContent = Jx.Bg,
        ring = false,
    ) {
        Row(Modifier.fillMaxSize().padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(30.dp)) {
                Box(Modifier.size(30.dp).background(Jx.SurfaceHi, CircleShape), contentAlignment = Alignment.Center) {
                    Text(ServerHub.letter(accounts, account), style = Type.Label, color = Jx.Accent)
                }
                Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(Jx.Rail, CircleShape).padding(2.dp)
                    .background(statusColor(st), CircleShape))
            }
            if (expanded) Column {
                Text(ServerHub.name(accounts, account), style = Type.Label, maxLines = 1)
                Text(if (accounts.size > 1) "Cambiar servidor" else "Servidores", style = Type.Small, maxLines = 1)
            }
        }
    }
}
