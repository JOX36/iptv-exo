package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.Section
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.ServerInfo
import com.jox3.tvdesktop.data.ServerHub
import com.jox3.tvdesktop.data.TvRepo
import com.jox3.tvdesktop.data.XtreamApi
import com.jox3.tvdesktop.player.PlayState
import com.jox3.tvdesktop.player.WatchTogether
import com.jox3.tvdesktop.player.VideoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Quien maneja las teclas de la ventana en este momento (lo registra el inicio). */
object KeyRouter {
    var handler: ((KeyEvent) -> Boolean)? = null
}

private enum class Tab(val label: String) { LIVE("En vivo"), MOVIES("Películas"), SERIES("Series"), SEARCH("Buscar"), SETTINGS("Ajustes") }

/**
 * Inicio tras entrar: barra superior con secciones y cuenta, y la sección elegida.
 * Cuando el video está en pantalla completa o en mini, ocupa toda la ventana.
 */
@Composable
fun HomeScreen(
    account: Account,
    api: XtreamApi,
    info: ServerInfo,
    version: String,
    player: VideoPlayer,
    accounts: List<Account>,
    profiles: com.jox3.tvdesktop.data.ProfileStore,
    profile: com.jox3.tvdesktop.data.Profile,
    onSwitchServer: (Account) -> Unit,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    // Cada perfil tiene su historial, favoritos, recientes y búsquedas (separados por cuenta y perfil)
    val repo = remember(account.key, profile.id) { TvRepo(api, LibraryStore(account, profile.id)) }
    val live = remember(repo) { LiveController(repo, player) }
    val control = LocalWindowControl.current
    var tab by remember { mutableStateOf(Tab.LIVE) }
    var showAccount by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Abre algo del buscador o de "También en" que está en ESTE servidor. */
    fun openHere(c: ServerHub.Copy) {
        when (c.kind) {
            "live" -> {
                tab = Tab.LIVE
                scope.launch {
                    // Con la lista de su categoría, para poder pasar de canal con ↑ ↓
                    val list = runCatching { repo.channels(c.categoryId) }.getOrDefault(emptyList())
                    val ch = list.firstOrNull { it.id == c.id } ?: com.jox3.tvdesktop.data.Channel(c.id, c.name, c.image, c.categoryId, 1)
                    live.play(ch, list.ifEmpty { listOf(ch) }, "Búsqueda")
                }
            }
            "movie" -> { tab = Tab.MOVIES; live.openRequest = OpenRequest("movie", c.id, c.name, c.image, c.ext, c.categoryId) }
            "series" -> { tab = Tab.SERIES; live.openRequest = OpenRequest("series", c.id, c.name, c.image, "", c.categoryId) }
        }
    }

    /** Abrir en este servidor o cambiar a otro (y abrir allá al entrar). */
    fun open(acc: Account, c: ServerHub.Copy?) {
        if (acc.key == account.key) { c?.let(::openHere); return }
        live.stop()
        ServerHub.pendingOpen = c
        onSwitchServer(acc)
    }
    val serverCtx = remember(account.key, accounts) { ServerCtx(account, accounts, ::open) }

    // Al entrar: lo pendiente de otro servidor, estado de los servidores y sus catálogos para buscar
    LaunchedEffect(account.key) {
        ServerHub.pendingOpen?.let { p -> if (p.account.key == account.key) { ServerHub.pendingOpen = null; openHere(p) } }
        ServerHub.refresh(accounts)
        if (accounts.size > 1 && ServerHub.searchAllEnabled()) {
            delay(8000) // primero que cargue lo de este servidor
            ServerHub.ensureIndexes(accounts.filter { it.key != account.key })
        }
    }

    /** Empieza una película o episodio: deja el canal en vivo y ocupa toda la ventana. */
    fun startVod(session: VodSession, startMs: Long) {
        live.playVod(session, startMs)
        live.mode = ViewMode.FULL
    }

    /** "Porque viste…": el título que se terminó (se muestra encima de todo). */
    var because by remember(repo) { mutableStateOf<SavedItem?>(null) }

    /** Sale de la película o episodio: guarda dónde quedó y vuelve a su ficha. */
    fun exitVod() {
        control?.setFullscreen(false); control?.switchMini(false)
        live.panel = com.jox3.tvdesktop.ui.Panel.NONE
        // ¿La terminó y no queda otro episodio? → se cierra y salen las recomendaciones
        live.finishedItem()?.let { done -> live.stop(); because = done; return }
        // Como en la TV y el celular: el video sigue en una ventanita mientras navegas
        if (com.jox3.tvdesktop.core.Settings.bool("player.pip", true) && live.vod?.item?.kind != "trailer") live.mode = ViewMode.PIP
        else live.stop()
    }

    /** Ventanita de la esquina → grande otra vez. */
    fun expandPip() {
        if (live.vod != null) live.mode = ViewMode.FULL
        else { tab = Tab.LIVE; live.mode = ViewMode.EMBEDDED }
    }

    fun toggleFull() {
        if (!live.hasMedia) { live.flash("Elige un canal primero"); return }
        if (live.mode == ViewMode.PIP) {
            live.mode = ViewMode.FULL
            if (live.vod == null) control?.setFullscreen(true)
            return
        }
        if (live.vod != null && live.mode == ViewMode.FULL) {
            // Película: alterna entre pantalla completa real y ventana, sin salir del video
            control?.let { it.setFullscreen(!it.fullscreen) }; return
        }
        if (live.mode == ViewMode.FULL) {
            control?.setFullscreen(false); live.mode = ViewMode.EMBEDDED; live.panel = com.jox3.tvdesktop.ui.Panel.NONE
        } else {
            control?.switchMini(false); live.mode = ViewMode.FULL; control?.setFullscreen(true)
        }
    }
    fun toggleMini() {
        if (!live.hasMedia) { live.flash("Elige un canal primero"); return }
        if (live.mode == ViewMode.MINI) {
            control?.switchMini(false)
            live.mode = if (live.vod != null) ViewMode.FULL else if (tab == Tab.LIVE) ViewMode.EMBEDDED else ViewMode.PIP
        }
        else { control?.setFullscreen(false); live.panel = com.jox3.tvdesktop.ui.Panel.NONE; live.mode = ViewMode.MINI; control?.switchMini(true) }
    }
    fun leaveVideoMode() {
        if (live.vod != null) { exitVod(); return }
        when (live.mode) {
            ViewMode.MINI -> toggleMini()
            ViewMode.FULL -> toggleFull()
            ViewMode.EMBEDDED, ViewMode.PIP -> {}
        }
    }

    // ── Ver juntos (misma sala que la TV y el celular) ──
    /** Invitado: busca en este servidor lo que ve la sala y lo abre (por título; en series, temporada y episodio). */
    suspend fun openTogether(m: WatchTogether.Media): Boolean {
        val key = TvRepo.titleKey(m.title)
        val idx = runCatching { repo.searchIndex() }.getOrNull() ?: return false
        if (m.kind == "movie") {
            val mv = idx.movies.filter { TvRepo.titleKey(it.name) == key }
                .let { l -> l.firstOrNull { it.poster.startsWith("http") } ?: l.firstOrNull() } ?: return false
            startVod(movieSession(repo, mv, mv.poster, mv.ext.ifBlank { "mp4" }), 0)
            return true
        }
        val sr = idx.series.firstOrNull { TvRepo.titleKey(it.name) == key } ?: return false
        val info = runCatching { repo.seriesInfo(sr.id) }.getOrNull() ?: return false
        val all = info.seasons.toSortedMap().values.flatten()
        val ep = all.firstOrNull { it.season == m.season && it.num == m.episode } ?: return false
        startVod(episodeSession(repo, sr, info.poster.ifBlank { sr.poster }, all, ep, ::startVod), 0)
        return true
    }
    val togetherHost = remember(live) {
        object : WatchTogether.Host {
            override val player: VideoPlayer get() = live.player
            override fun now(): WatchTogether.Now? = live.vod?.takeIf { it.item.kind == "movie" || it.item.kind == "episode" }
                ?.let { WatchTogether.Now(it.item.kind, it.item.name, it.title, it.image) }
            override suspend fun open(m: WatchTogether.Media): Boolean = openTogether(m)
        }
    }
    DisposableEffect(togetherHost) {
        WatchTogether.attach(togetherHost)
        onDispose { WatchTogether.attach(null) }
    }
    LaunchedEffect(live.vod) { WatchTogether.mediaChanged() }
    LaunchedEffect(player.state) {
        if (player.state == PlayState.Playing || player.state == PlayState.Paused) WatchTogether.playerReady()
    }

    // Al cerrar el programa (o al actualizarse) se guarda dónde va la película
    // y lo pendiente se sube a la nube (como mucho 4 s)
    androidx.compose.runtime.DisposableEffect(live) {
        val remove = com.jox3.tvdesktop.core.Shutdown.add {
            live.saveVodProgress()
            com.jox3.tvdesktop.data.CloudSync.flushBlocking(account, repo.library)
        }
        onDispose { remove() }
    }

    // Historial en la nube: compartido con el TV Box y el celular de la misma cuenta
    LaunchedEffect(repo) { com.jox3.tvdesktop.data.CloudSync.run(account, repo.library, profiles) }
    // Los perfiles se comparten entre todos los servidores del equipo: se suben y bajan también los de los demás
    LaunchedEffect(account.key) {
        delay(6000)
        com.jox3.tvdesktop.data.CloudSync.syncAllProfiles(accounts, except = account)
    }

    // Apagado automático
    LaunchedEffect(live.sleepAt) {
        val at = live.sleepAt ?: return@LaunchedEffect
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        if (live.sleepAt == at) {
            live.sleepAt = null
            leaveVideoMode()
            live.stop()
            live.flash("Apagado automático: reproducción detenida")
        }
    }

    // Película o episodio: se guarda dónde va cada 5 s (para "continuar viendo")
    LaunchedEffect(live.vod) {
        if (live.vod == null) return@LaunchedEffect
        while (true) { delay(5000); if (player.state == PlayState.Playing) live.saveVodProgress() }
    }
    LaunchedEffect(player.state) { if (player.state == PlayState.Ended) live.saveVodProgress() }

    // Número de canal tecleado: se cambia 1,5 s después del último dígito
    var numberJob by remember { mutableStateOf<Job?>(null) }
    fun typeDigit(d: Int) {
        if (live.current == null) return
        live.typedNumber = (live.typedNumber + d).takeLast(5)
        numberJob?.cancel()
        numberJob = scope.launch {
            delay(1500)
            val n = live.typedNumber.toIntOrNull()
            live.typedNumber = ""
            if (n != null && !live.jumpTo(n)) live.flash("No hay canal $n en esta lista")
        }
    }

    // ── Atajos de teclado ──
    DisposableEffect(live) {
        KeyRouter.handler = handler@{ ev ->
            if (ev.type == KeyEventType.KeyDown && ev.isCtrlPressed && ev.key == Key.F && live.mode == ViewMode.EMBEDDED && live.vod == null) {
                tab = Tab.SEARCH; return@handler true   // Ctrl + F: buscador
            }
            if (ev.type != KeyEventType.KeyDown || ev.isCtrlPressed || ev.isAltPressed) return@handler false
            if (Typing.active && ev.key != Key.Escape) return@handler false
            val active = live.hasMedia
            val isVod = live.vod != null
            when (ev.key) {
                Key.Escape -> when {
                    showJoin -> showJoin = false
                    because != null && live.mode != ViewMode.FULL && live.mode != ViewMode.MINI -> because = null
                    live.panel != com.jox3.tvdesktop.ui.Panel.NONE -> live.panel = com.jox3.tvdesktop.ui.Panel.NONE
                    live.mode == ViewMode.FULL || live.mode == ViewMode.MINI -> leaveVideoMode()
                    showAccount -> showAccount = false
                    else -> return@handler false
                }
                Key.Spacebar, Key.K -> if (active) player.togglePause() else return@handler false
                Key.F, Key.F11 -> toggleFull()
                Key.T -> toggleMini()
                // En vivo ↑↓ cambian de canal; en películas ↑↓ es el volumen y ←→ adelanta o atrasa 10 s
                // En películas: ↑ ↓ volumen y ← → adelantar/atrasar. En vivo: ↑ ↓ canal.
                // En las listas: ← → cambian de categoría y ↑ ↓ desplazan (si no hay canal sonando).
                Key.DirectionUp, Key.PageUp -> when {
                    isVod -> live.changeVolume(5)
                    live.current != null && tab == Tab.LIVE || live.mode != ViewMode.EMBEDDED -> live.step(-1)
                    live.listScroll != null -> live.listScroll?.invoke(-1)
                    else -> return@handler false
                }
                Key.DirectionDown, Key.PageDown -> when {
                    isVod -> live.changeVolume(-5)
                    live.current != null && tab == Tab.LIVE || live.mode != ViewMode.EMBEDDED -> live.step(1)
                    live.listScroll != null -> live.listScroll?.invoke(1)
                    else -> return@handler false
                }
                Key.DirectionLeft -> when {
                    isVod -> live.seekBy(-10_000)
                    live.mode == ViewMode.EMBEDDED && live.categoryStep != null -> live.categoryStep?.invoke(-1)
                    else -> return@handler false
                }
                Key.DirectionRight -> when {
                    isVod -> live.seekBy(10_000)
                    live.mode == ViewMode.EMBEDDED && live.categoryStep != null -> live.categoryStep?.invoke(1)
                    else -> return@handler false
                }
                Key.V -> if (isVod) live.cycleSpeed() else return@handler false
                Key.J -> if (isVod && live.vod?.item?.kind != "trailer") {
                    if (live.mode != ViewMode.FULL) toggleFull()
                    live.panel = if (live.panel == com.jox3.tvdesktop.ui.Panel.TOGETHER) com.jox3.tvdesktop.ui.Panel.NONE else com.jox3.tvdesktop.ui.Panel.TOGETHER
                } else return@handler false
                Key.N -> if (isVod && live.vod?.next != null) live.vod?.next?.invoke() else return@handler false
                Key.Plus, Key.Equals, Key.NumPadAdd -> live.changeVolume(5)
                Key.Minus, Key.NumPadSubtract -> live.changeVolume(-5)
                Key.M -> live.toggleMute()
                Key.A -> if (active) live.cycleAudio() else return@handler false
                Key.S -> if (active) live.cycleSubtitles() else return@handler false
                Key.Z -> if (active) live.cycleAspect() else return@handler false
                Key.I -> if (active) {
                    if (live.mode != ViewMode.FULL) toggleFull()
                    live.panel = if (live.panel == com.jox3.tvdesktop.ui.Panel.INFO) com.jox3.tvdesktop.ui.Panel.NONE else com.jox3.tvdesktop.ui.Panel.INFO
                } else return@handler false
                Key.C -> if (active && !isVod && live.mode == ViewMode.FULL) {
                    live.panel = if (live.panel == com.jox3.tvdesktop.ui.Panel.CHANNELS) com.jox3.tvdesktop.ui.Panel.NONE else com.jox3.tvdesktop.ui.Panel.CHANNELS
                } else return@handler false
                else -> {
                    val digit = digitOf(ev.key) ?: return@handler false
                    if (isVod) return@handler false
                    typeDigit(digit)
                }
            }
            true
        }
        onDispose { KeyRouter.handler = null }
    }

    // Si se cambia de sección con un canal sonando en la vista previa, se detiene (sin audio escondido)
    // y al volver a En vivo el mismo canal sigue solo.
    // Con la opción de mini reproductor (Ajustes), el canal sigue en la ventanita de la esquina.
    LaunchedEffect(tab, live.vod) {
        val pip = com.jox3.tvdesktop.core.Settings.bool("player.pip", true)
        if (tab != Tab.LIVE && live.mode == ViewMode.EMBEDDED && live.current != null) {
            if (pip) live.mode = ViewMode.PIP else live.pauseLive()
        }
        if (tab == Tab.LIVE && live.vod == null) {
            if (live.mode == ViewMode.PIP) live.mode = ViewMode.EMBEDDED else live.resumeLive()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalServers provides serverCtx) {
    Box(Modifier.fillMaxSize().background(Jx.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── Barra superior ──
            Row(
                Modifier.fillMaxWidth().background(Jx.Rail).padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand(iconSize = 30.dp, textSize = 20)
                Spacer(Modifier.width(30.dp))
                Tab.entries.forEach { t ->
                    TabButton(t.label, selected = tab == t, icon = when (t) { Tab.SEARCH -> Ico.SEARCH; Tab.SETTINGS -> Ico.SETTINGS; else -> null }) { tab = t }
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.weight(1f))
                if (live.current != null && player.state == PlayState.Playing) {
                    Pill("EN VIVO", Jx.Live)
                    Spacer(Modifier.width(12.dp))
                }
                TabButton("Ver juntos", selected = showJoin, icon = Ico.USER) { showJoin = true }
                Spacer(Modifier.width(8.dp))
                ProfileChip(profile, account.label, selected = showAccount) { showAccount = !showAccount }
            }
            // ── Sección ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.LIVE -> LiveScreen(live, ::toggleFull, ::toggleMini)
                    Tab.MOVIES -> VodScreen(live, Section.VOD, onPlay = ::startVod)
                    Tab.SERIES -> VodScreen(live, Section.SERIES, onPlay = ::startVod)
                    Tab.SEARCH -> SearchScreen(live) { c -> open(c.account, c) }
                    Tab.SETTINGS -> SettingsScreen(live, version)
                }
            }
        }
        // "Porque viste…" (encima de la sección)
        because?.let { b ->
            BecauseScreen(repo, b, Modifier.fillMaxSize(),
                onOpen = { r ->
                    because = null
                    if (r.kind == "series") { tab = Tab.SERIES; live.openRequest = OpenRequest("series", r.id, r.name, r.image, "", r.categoryId) }
                    else { tab = Tab.MOVIES; live.openRequest = OpenRequest("movie", r.id, r.name, r.image, r.ext, r.categoryId) }
                },
                onClose = { because = null })
        }
        // Video en ventana completa o mini: va ENCIMA de la sección, así al volver todo sigue
        // donde estaba (la ficha abierta, la categoría, la posición de la lista).
        if (live.mode == ViewMode.FULL || live.mode == ViewMode.MINI) {
            PlayerView(live, Modifier.fillMaxSize(), ::toggleFull, ::toggleMini, ::exitVod)
        }
        if (live.mode == ViewMode.PIP && live.hasMedia) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(20.dp).size(width = 400.dp, height = 225.dp)
                    .shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Jx.Line, RoundedCornerShape(14.dp))
            ) {
                PlayerView(live, Modifier.fillMaxSize(), ::expandPip, ::toggleMini, onExitVod = { live.stop() })
            }
        }
        // Ver juntos: aviso de la sala (código, personas, reacciones) encima de todo
        TogetherBadge(Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
        if (showJoin) TogetherJoin(onDismiss = { showJoin = false })
        if (showAccount && (live.mode == ViewMode.EMBEDDED || live.mode == ViewMode.PIP)) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { showAccount = false })
            AccountPanel(account, accounts, info, version, Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 20.dp),
                profile = profile,
                onSwitchProfile = { showAccount = false; live.stop(); onSwitchProfile() },
                onUse = { acc -> showAccount = false; open(acc, null) },
                onLogout = { showAccount = false; live.stop(); onLogout() }, onOpenLogs = onOpenLogs)
        }
    }
    }
}

private fun digitOf(k: Key): Int? = when (k) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9; else -> null
}

@Composable
private fun TabButton(label: String, selected: Boolean, soon: Boolean = false, icon: Ico? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(when { selected -> Jx.SurfaceHi; hovered -> Jx.Surface; else -> Color.Transparent })
            .hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { JxIcon(icon, 16.dp, if (selected) Jx.Accent else Jx.TextSoft); Spacer(Modifier.width(8.dp)) }
        Text(label, color = if (selected) Jx.Accent else if (soon) Jx.Faint else Jx.TextSoft,
            style = MaterialTheme.typography.titleSmall)
        if (soon) { Spacer(Modifier.width(6.dp)); Pill("Pronto", Jx.Faint) }
    }
}

/** Datos de la cuenta, los servidores guardados (con su estado) y el registro de errores. */
@Composable
private fun AccountPanel(
    account: Account, accounts: List<Account>, info: ServerInfo, version: String, modifier: Modifier,
    profile: com.jox3.tvdesktop.data.Profile, onSwitchProfile: () -> Unit,
    onUse: (Account) -> Unit, onLogout: () -> Unit, onOpenLogs: () -> Unit,
) {
    Column(
        modifier.widthIn(max = 460.dp).fillMaxWidth()
            .background(Jx.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Jx.Line, RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(22.dp)
    ) {
        // ── Perfil ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.emoji, profile.color, 44.dp, lock = profile.hasPin)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                Text("Perfil en uso", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
            }
            GhostButton("Cambiar de perfil", onSwitchProfile)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Jx.Line))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tu cuenta", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            val active = info.status.equals("Active", ignoreCase = true)
            Pill(if (active) "ACTIVA" else info.status.uppercase().ifBlank { "SIN ESTADO" }, if (active) Jx.Ok else Jx.Gold)
        }
        Spacer(Modifier.height(14.dp))
        InfoRow("Servidor", account.host)
        InfoRow("Usuario", account.user)
        InfoRow("Vence", info.expires?.let { fmtDate(it) } ?: "Sin fecha de vencimiento")
        InfoRow("Conexiones", "${info.activeCons} en uso de ${info.maxCons}")

        // ── Servidores ──
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Servidores", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("Revisar", color = Jx.Accent, fontFamily = Exo2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .clickable { ServerHub.refresh(accounts, force = true) }.pointerHoverIcon(PointerIcon.Hand).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        accounts.forEach { acc ->
            val st = ServerHub.status[acc.key]
            val here = acc.key == account.key
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Letter(ServerHub.letter(accounts, acc), here)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ServerHub.name(accounts, acc), color = Jx.Text, fontFamily = Exo2, fontSize = 14.sp, maxLines = 1)
                    val detail = when {
                        st == null -> "Revisando…"
                        !st.ok -> st.error.ifBlank { "No responde" }
                        else -> listOfNotNull(
                            "${st.pingMs} ms",
                            st.daysLeft?.let { if (it == 0L) "vence hoy" else "vence en $it días" },
                            st.info?.let { "${it.activeCons}/${it.maxCons} conexiones" },
                        ).joinToString(" · ")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(levelColor(st?.level)))
                        Spacer(Modifier.width(6.dp))
                        Text(detail, color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp, maxLines = 1)
                    }
                }
                if (here) Pill("EN USO", Jx.Accent) else GhostButton("Usar") { onUse(acc) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Agregar o cambiar cuenta", onLogout)
            Text("Registro de errores", color = Jx.Faint, fontSize = 12.sp, fontFamily = Exo2, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenLogs)
                    .pointerHoverIcon(PointerIcon.Hand).padding(8.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("JOX3 TV para PC · versión $version", color = Jx.Faint, fontSize = 11.sp, fontFamily = Exo2)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = Jx.Muted, modifier = Modifier.width(110.dp), fontFamily = Exo2, fontSize = 14.sp)
        Text(value, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp)
    }
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es")).format(Date(ms))

/** Perfil en uso (emoji y nombre) y el servidor, arriba a la derecha. */
@Composable
private fun ProfileChip(profile: com.jox3.tvdesktop.data.Profile, server: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(when { selected -> Jx.SurfaceHi; hovered -> Jx.Surface; else -> Color.Transparent })
            .hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile.emoji, profile.color, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(profile.name, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            Text(server, color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp, maxLines = 1)
        }
    }
}
