package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jox3.iptvexo.tv.LocalShellChrome
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.activity.compose.BackHandler
import com.jox3.iptvexo.tv.ui.rememberFocusMemory
import com.jox3.iptvexo.tv.ui.focusMemoryItem
import com.jox3.iptvexo.tv.ui.focusMemoryGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.jox3.iptvexo.tv.ui.safeFocusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.TvHost
import com.jox3.iptvexo.tv.player.LocalLive
import com.jox3.iptvexo.tv.data.Category
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.EpgItem
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.Section
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.StatusBox
import com.jox3.iptvexo.tv.ui.ThinProgress
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FAV_ID = "__fav"
private const val RECENT_ID = "__recent"
private val clock = SimpleDateFormat("h:mm a", Locale("es"))
private fun allCatsFor(catList: List<Category>?) =
    listOf(Category(FAV_ID, "Favoritos"), Category(RECENT_ID, "Recientes")) + catList.orEmpty()
private fun hhmm(ms: Long) = if (ms > 0) clock.format(Date(ms)) else ""

/**
 * En vivo, estilo guía: categorías | canales con lo que se emite | vista previa.
 *
 * - Moverse por los canales NO cambia lo que se está viendo.
 * - OK sobre un canal → se reproduce en la ventanita de vista previa.
 * - OK otra vez sobre el mismo canal → pantalla completa al instante, sin
 *   recargar (es el mismo video, solo se agranda).
 * - OK mantenido → favorito.
 */
@Composable
fun LiveScreen(repo: TvRepo, @Suppress("UNUSED_PARAMETER") host: TvHost) {
    val lib = repo.library
    val live = LocalLive.current
    var reload by remember { mutableIntStateOf(0) }
    val catV = lib.catVersion.intValue
    val cats by produceState<Result<List<Category>>?>(null, reload, catV) {
        value = runCatching { repo.shownCategories(Section.LIVE) }
    }
    var selected by remember { mutableStateOf<Category?>(null) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    val channelsReq = remember { FocusRequester() }
    val playingReq = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var firstFocusDone by remember { mutableStateOf(false) }
    val catMem = rememberFocusMemory()
    val chMem = rememberFocusMemory()
    LaunchedEffect(selected?.id) { catMem.remember(selected?.id) }
    // Vista: lista con vista previa, o guía por horas (se recuerda)
    val ctx = LocalContext.current
    val uiPrefs = remember { ctx.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    var guideMode by remember { mutableStateOf(uiPrefs.getString("live_view", "list") == "guide") }

    // ── Vista compacta (como TiviMate) ──
    // Al entrar a los canales se ocultan la barra lateral y las categorías: la vista
    // previa crece. Atrás o ◀ desde los canales las vuelve a mostrar.
    val chrome = LocalShellChrome.current
    var compact by remember { mutableStateOf(false) }
    val catListReq = remember { FocusRequester() }
    val catListState = rememberLazyListState()
    var expandTick by remember { mutableIntStateOf(0) }
    var pendingChannelFocus by remember { mutableStateOf(false) }
    fun expand() { if (compact) { compact = false; expandTick++ } }
    LaunchedEffect(compact) { chrome.railHidden = compact }
    DisposableEffect(Unit) { onDispose { chrome.railHidden = false } }
    /** Lleva el foco a la categoría abierta (desplazando la lista hasta ella). */
    suspend fun focusSelectedCategory() {
        delay(16)
        val idx = allCatsFor(cats?.getOrNull()).indexOfFirst { it.id == selected?.id }
        if (idx >= 0) runCatching { catListState.scrollToItem((idx - 3).coerceAtLeast(0)) }
        delay(30)
        runCatching { catListReq.requestFocus() }
    }
    LaunchedEffect(expandTick) {
        if (expandTick == 0) return@LaunchedEffect
        focusSelectedCategory()
    }
    BackHandler(enabled = compact && !live.fullscreen) { expand() }
    val leftExpands = Modifier.onPreviewKeyEvent { ev ->
        if (compact && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            expand(); true
        } else false
    }

    val catList = cats?.getOrNull()
    val allCats = remember(catList) { allCatsFor(catList) }
    // Al entrar: la última categoría elegida (se recuerda entre sesiones) y el foco en las
    // categorías, para que el usuario elija; los canales se muestran al pulsar OK o ▶.
    LaunchedEffect(catList) {
        if (selected == null && catList != null) {
            val saved = uiPrefs.getString("live_cat", null)
            selected = allCats.firstOrNull { it.id == saved }
                ?: if (lib.favorites.any { it.kind == "live" }) allCats[0] else catList.firstOrNull()
            if (!firstFocusDone) { firstFocusDone = true; focusSelectedCategory() }
        }
    }
    LaunchedEffect(selected?.id) { selected?.id?.let { uiPrefs.edit().putString("live_cat", it).apply() } }

    // La lista de favoritos solo se recalcula si se está viendo esa categoría
    val favKey = if (selected?.id == FAV_ID) lib.favorites.size else -1
    var loadedFor by remember { mutableStateOf<String?>(null) }
    val channels by produceState<Result<List<Channel>>?>(null, selected, favKey, reload) {
        val cat = selected ?: return@produceState
        if (loadedFor != cat.id) value = null   // solo al cambiar de categoría se muestra "Cargando…"
        loadedFor = cat.id
        value = runCatching {
            when (cat.id) {
                FAV_ID -> lib.favorites.filter { it.kind == "live" }.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
                RECENT_ID -> lib.recentChannels.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
                else -> repo.channels(cat.id)
            }
        }
    }
    val list = channels?.getOrNull()

    // Al volver de pantalla completa (quizá en otro canal), el foco va al canal que se está viendo
    var wasFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(live.fullscreen) {
        if (live.fullscreen) { wasFullscreen = true; return@LaunchedEffect }
        if (!wasFullscreen) return@LaunchedEffect
        wasFullscreen = false
        val idx = list?.indexOfFirst { it.id == live.current?.id } ?: -1
        if (idx >= 0) {
            listState.scrollToItem((idx - 3).coerceAtLeast(0))
            delay(40)
            runCatching { playingReq.requestFocus() }
        }
    }

    if (cats == null) { StatusBox("Cargando canales…"); return }
    if (catList == null) {
        StatusBox("No se pudieron cargar las categorías: ${cats?.exceptionOrNull()?.message}", onRetry = { reload++ }); return
    }

    Row(Modifier.fillMaxSize()) {
        // ── Categorías ──
        if (!compact) Column(Modifier.width(210.dp).fillMaxHeight().background(Jx.Rail).padding(top = 27.dp)) {
            FocusSurface(
                onClick = {
                    guideMode = !guideMode
                    uiPrefs.edit().putString("live_view", if (guideMode) "guide" else "list").apply()
                },
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 14.dp).fillMaxWidth().height(40.dp),
                focusedScale = 1f,
                container = Jx.Surface,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    JxIcon(if (guideMode) R.drawable.tv_ic_list else R.drawable.tv_ic_live, 16.dp, Jx.Accent)
                    Text(if (guideMode) "Ver en lista" else "Guía por horas", style = Type.Label)
                }
            }
            Text("CATEGORÍAS", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            LazyColumn(
                Modifier.fillMaxSize().focusRequester(catListReq).focusMemoryGroup(catMem),
                state = catListState,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 27.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(allCats, key = { it.id }) { c ->
                    val isSel = c.id == selected?.id
                    var focused by remember { mutableStateOf(false) }
                    // Al detenerse sobre una categoría se carga sola (sin pulsar OK)
                    LaunchedEffect(focused) { if (focused && !isSel) { delay(350); selected = c } }
                    FocusSurface(
                        onClick = { selected = c; pendingChannelFocus = true },
                        modifier = Modifier.fillMaxWidth().height(38.dp).focusMemoryItem(catMem, c.id)
                            .onFocusChanged { focused = it.isFocused },
                        focusedScale = 1f,
                        container = if (isSel) Jx.SurfaceHi else androidx.compose.ui.graphics.Color.Transparent,
                        content = if (isSel) Jx.Accent else Jx.TextSoft,
                    ) {
                        Text(c.name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
                    }
                }
            }
        }

        // OK en una categoría: cuando sus canales estén listos, el foco pasa a ellos
        LaunchedEffect(list, pendingChannelFocus) {
            if (!pendingChannelFocus || list == null) return@LaunchedEffect
            pendingChannelFocus = false
            if (list.isNotEmpty()) { delay(40); runCatching { channelsReq.requestFocus() } }
        }

        if (guideMode) {
            when {
                channels == null -> Box(Modifier.weight(1f)) { StatusBox("Cargando…") }
                list == null -> Box(Modifier.weight(1f)) { StatusBox("No se pudo cargar la categoría", onRetry = { reload++ }) }
                list.isEmpty() -> Box(Modifier.weight(1f)) {
                    StatusBox(if (selected?.id == FAV_ID) "Mantén OK sobre un canal para agregarlo a favoritos" else "Sin canales")
                }
                else -> LiveGuide(repo, list, selected?.name.orEmpty(), listState, channelsReq, playingReq,
                    Modifier.weight(1f).onFocusChanged { if (it.hasFocus) compact = true },
                    onChannelLeft = { expand(); true })
            }
            return@Row
        }

        // ── Canales ──
        Column(Modifier.width(if (compact) 380.dp else 340.dp).fillMaxHeight().padding(top = 27.dp, start = if (compact) 16.dp else 0.dp)
            .onFocusChanged { if (it.hasFocus) compact = true }
            .then(leftExpands)) {
            Text(selected?.name.orEmpty(), style = Type.H1, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp))
            when {
                channels == null -> StatusBox("Cargando…")
                list == null -> StatusBox("No se pudo cargar la categoría", onRetry = { reload++ })
                list.isEmpty() -> StatusBox(if (selected?.id == FAV_ID) "Mantén OK sobre un canal para agregarlo a favoritos" else "Sin canales")
                else -> LazyColumn(
                    Modifier.fillMaxSize().focusRequester(channelsReq).focusMemoryGroup(chMem),
                    state = listState,
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 27.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { _, ch ->
                        val playing = live.current?.id == ch.id
                        ChannelRow(
                            ch, repo,
                            favorite = lib.isFavorite("live", ch.id),
                            playing = playing,
                            modifier = (if (playing) Modifier.focusRequester(playingReq) else Modifier).focusMemoryItem(chMem, ch.id),
                            onFocus = { focusedChannel = ch },
                            onClick = {
                                if (playing) live.enterFullscreen()
                                else live.play(ch, list, selected?.name.orEmpty())
                            },
                            onLongClick = { lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId)) },
                        )
                    }
                }
            }
        }

        // ── Vista previa + guía ──
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 27.dp, end = 32.dp, start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Hueco de la vista previa: el video lo dibuja la capa de video encima
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Jx.Surface)
                    .onGloballyPositioned { live.previewBounds = it.boundsInRoot() },
                contentAlignment = Alignment.Center,
            ) {
                if (live.current == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(16.dp)) {
                        JxIcon(R.drawable.tv_ic_live, 28.dp, Jx.Muted)
                        Text("Pulsa OK en un canal para verlo aquí", style = Type.Label, color = Jx.TextSoft)
                        Text("OK otra vez: pantalla completa", style = Type.Small, color = Jx.Faint)
                    }
                }
            }
            val shown = focusedChannel ?: live.current
            if (shown != null) {
                val isPlaying = live.current?.id == shown.id
                Text(if (isPlaying) "OK: ver en pantalla completa" else "OK: ver en la vista previa",
                    style = Type.Small, color = if (isPlaying) Jx.Accent else Jx.Faint)
                ChannelGuide(shown, repo)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { live.previewBounds = null } }
}

@Composable
private fun ChannelRow(
    ch: Channel, repo: TvRepo, favorite: Boolean, playing: Boolean, modifier: Modifier,
    onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val version by repo.epgVersion
    // Lo que se emite ahora: se pide solo si la fila queda visible un instante
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) {
        if (value.isNullOrEmpty()) { delay(300); value = repo.epg(ch) }
    }
    val now = epg?.let { repo.nowPlaying(it) }
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(58.dp).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        },
        focusedScale = 1.03f,
        container = if (playing) Jx.Surface else androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${ch.num}", style = Type.Number, color = if (focused || playing) Jx.Accent else Jx.Faint, modifier = Modifier.width(34.dp))
            RemoteImage(ch.logo, ch.name, Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)), scale = ContentScale.Fit)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ch.name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) Jx.Accent else androidx.tv.material3.LocalContentColor.current)
                if (now != null) {
                    Text(now.label, style = Type.Small, color = if (focused) Jx.TextSoft else Jx.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (focused) ThinProgress(now.progress(), Modifier.fillMaxWidth(), height = 2.dp)
                }
            }
            if (playing) JxIcon(R.drawable.tv_ic_play, 12.dp, Jx.Accent, "En reproducción")
            if (favorite) JxIcon(R.drawable.tv_ic_star_filled, 14.dp, Jx.Gold, "Favorito")
        }
    }
}

@Composable
private fun ChannelGuide(ch: Channel, repo: TvRepo) {
    val version by repo.epgVersion
    val epg by produceState<List<EpgItem>?>(repo.cachedEpg(ch), ch.id, version) {
        value = repo.epg(ch)
    }
    val list = epg.orEmpty()
    val now = repo.nowPlaying(list)
    // Compacta para que quepa entera bajo la vista previa (incluido "A continuación")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(ch.name, style = Type.Small, color = Jx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (now == null) {
            Text(if (epg == null) "Cargando guía…" else "Sin guía para este canal", style = Type.Body, color = Jx.Faint)
            return@Column
        }
        Text("AHORA · ${hhmm(now.start)} – ${hhmm(now.end)}", style = Type.Eyebrow, color = Jx.Accent)
        Text(now.label, style = Type.H2, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (now.meta.isNotBlank()) Text(now.meta, style = Type.Small, color = Jx.Muted, maxLines = 1)
        ThinProgress(now.progress(), Modifier.fillMaxWidth().padding(vertical = 2.dp), height = 3.dp)
        if (now.description.isNotBlank()) {
            Text(now.description, style = Type.Small, color = Jx.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val next = list.filter { it.start >= now.end }.take(2)
        if (next.isNotEmpty()) {
            Text("A CONTINUACIÓN", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(top = 4.dp))
            next.forEach { n ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(hhmm(n.start), style = Type.Label, color = Jx.Muted, modifier = Modifier.width(64.dp))
                    Text(n.label, style = Type.Label, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
