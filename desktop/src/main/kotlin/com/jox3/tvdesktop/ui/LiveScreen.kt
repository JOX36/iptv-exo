package com.jox3.tvdesktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.core.Settings
import com.jox3.tvdesktop.data.Category
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.Section
import com.jox3.tvdesktop.data.TvRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FAV_ID = "__fav"
private const val RECENT_ID = "__recent"

/**
 * En vivo: categorías | canales con lo que se emite | vista previa con la guía.
 * Clic en un canal = verlo en la vista previa · clic otra vez o doble clic = pantalla completa ·
 * clic derecho o ☆ = favorito. Botón "Guía por horas" para la parrilla.
 */
@Composable
fun LiveScreen(live: LiveController, onToggleFull: () -> Unit, onToggleMini: () -> Unit) {
    val repo = live.repo
    val lib = repo.library
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val catV = lib.catVersion.intValue
    val cats by produceState<Result<List<Category>>?>(null, reload, catV) {
        value = runCatching { repo.shownCategories(Section.LIVE) }
    }
    val catList = cats?.getOrNull()
    val allCats = remember(catList) {
        listOf(Category(FAV_ID, "★  Favoritos"), Category(RECENT_ID, "Recientes")) + catList.orEmpty()
    }
    var selected by remember { mutableStateOf<Category?>(null) }
    var guideMode by remember { mutableStateOf(Settings.string("live.view") == "guide") }
    var catFilter by remember { mutableStateOf("") }
    var chFilter by remember { mutableStateOf("") }

    // Al entrar: la última categoría elegida (se recuerda entre sesiones)
    LaunchedEffect(catList) {
        if (selected == null && catList != null) {
            val saved = Settings.string("live.category")
            selected = allCats.firstOrNull { it.id == saved }
                ?: if (lib.favorites.any { it.kind == "live" }) allCats[0] else catList.firstOrNull()
        }
    }
    LaunchedEffect(selected?.id) { selected?.id?.let { Settings.put("live.category", it); chFilter = "" } }

    // Canales de la categoría (favoritos y recientes salen de la biblioteca)
    val favKey = if (selected?.id == FAV_ID) lib.favorites.size else -1
    val recentKey = if (selected?.id == RECENT_ID) lib.recentChannels.firstOrNull()?.id else null
    val channels by produceState<Result<List<Channel>>?>(null, selected, favKey, recentKey, reload) {
        val cat = selected ?: return@produceState
        if (value?.getOrNull()?.firstOrNull()?.categoryId != cat.id) value = null
        value = runCatching {
            when (cat.id) {
                FAV_ID -> lib.favorites.filter { it.kind == "live" }.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
                RECENT_ID -> lib.recentChannels.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
                else -> repo.channels(cat.id)
            }
        }
    }
    val list = channels?.getOrNull()
    val shownChannels = remember(list, chFilter) {
        val q = TvRepo.normalize(chFilter.trim())
        if (q.isEmpty()) list.orEmpty() else list.orEmpty().filter { TvRepo.normalize(it.name).contains(q) || it.num.toString() == q }
    }

    if (cats == null) { Status("Cargando categorías…"); return }
    if (catList == null) {
        Status("No se pudieron cargar las categorías: ${cats?.exceptionOrNull()?.message}", "Reintentar") { reload++ }
        return
    }

    Row(Modifier.fillMaxSize()) {
        // ── Categorías ──
        Column(Modifier.width(250.dp).fillMaxHeight().background(Jx.Rail).padding(top = 16.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallToggle(if (guideMode) Ico.LIST else Ico.GUIDE, if (guideMode) "Ver en lista" else "Guía por horas", Modifier.weight(1f)) {
                    guideMode = !guideMode
                    Settings.put("live.view", if (guideMode) "guide" else "list")
                }
                SmallToggle(Ico.REFRESH, "", Modifier.width(40.dp), tip = "Actualizar canales y guía") {
                    live.flash("Actualizando…")
                    scope.launch { repo.clear(); reload++ }
                }
            }
            Spacer(Modifier.height(12.dp))
            SearchField(catFilter, { catFilter = it }, "Buscar categoría", Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))
            val shownCats = remember(allCats, catFilter) {
                val q = TvRepo.normalize(catFilter.trim())
                if (q.isEmpty()) allCats else allCats.filter { it.id == FAV_ID || it.id == RECENT_ID || TvRepo.normalize(it.name).contains(q) }
            }
            val catState = rememberLazyListState()
            // ← → pasan de categoría; la lista se desplaza sola hasta la elegida
            androidx.compose.runtime.DisposableEffect(shownCats) {
                live.categoryStep = { d ->
                    val i = shownCats.indexOfFirst { it.id == selected?.id }
                    shownCats.getOrNull((i + d).coerceIn(0, shownCats.size - 1))?.let { selected = it }
                }
                onDispose { live.categoryStep = null }
            }
            LaunchedEffect(selected?.id) {
                val i = shownCats.indexOfFirst { it.id == selected?.id }
                if (i >= 0 && (i < catState.firstVisibleItemIndex || i > catState.firstVisibleItemIndex + catState.layoutInfo.visibleItemsInfo.size - 2)) {
                    catState.animateScrollToItem((i - 3).coerceAtLeast(0))
                }
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = catState, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, bottom = 16.dp)) {
                    items(shownCats, key = { it.id }) { c ->
                        val isSel = c.id == selected?.id
                        ListRow(isSel, onClick = { selected = c }) {
                            Text(c.name, color = if (isSel) Jx.Accent else Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(catState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }

        // ── Guía por horas ──
        if (guideMode) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    channels == null -> Status("Cargando…")
                    list == null -> Status("No se pudo cargar la categoría", "Reintentar") { reload++ }
                    list.isEmpty() -> Status(emptyText(selected?.id))
                    else -> LiveGuideGrid(live, list, selected?.name.orEmpty()) { ch ->
                        live.play(ch, list, selected?.name.orEmpty())
                        onToggleFull()
                    }
                }
            }
            return@Row
        }

        // ── Canales ──
        Column(Modifier.width(390.dp).fillMaxHeight().padding(top = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.name.orEmpty().removePrefix("★  "), color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (list != null) Text("${list.size}", color = Jx.Faint, fontFamily = Exo2, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            SearchField(chFilter, { chFilter = it }, "Buscar canal o número", Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            when {
                channels == null -> Status("Cargando…")
                list == null -> Status("No se pudo cargar la categoría", "Reintentar") { reload++ }
                shownChannels.isEmpty() -> Status(if (chFilter.isNotBlank()) "Ningún canal coincide" else emptyText(selected?.id))
                else -> {
                    val state = rememberLazyListState()
                    // Al volver de pantalla completa, la lista muestra el canal que se está viendo
                    // ↑ ↓ sin canal sonando: desplazan la lista
                    androidx.compose.runtime.DisposableEffect(state) {
                        live.listScroll = { d -> scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex + d * 5).coerceAtLeast(0)) } }
                        onDispose { live.listScroll = null }
                    }
                    LaunchedEffect(live.mode, selected?.id, live.current?.id) {
                        if (live.mode != ViewMode.EMBEDDED) return@LaunchedEffect
                        val i = shownChannels.indexOfFirst { it.id == live.current?.id }
                        if (i >= 0 && (i < state.firstVisibleItemIndex || i > state.firstVisibleItemIndex + 8)) {
                            state.scrollToItem((i - 3).coerceAtLeast(0))
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 8.dp, end = 14.dp, bottom = 16.dp)) {
                            items(shownChannels, key = { it.id }) { ch ->
                                val playing = live.current?.id == ch.id
                                ChannelRow(
                                    ch, repo, favorite = lib.isFavorite("live", ch.id), playing = playing,
                                    onClick = { if (playing) onToggleFull() else live.play(ch, list, selected?.name.orEmpty()) },
                                    onDoubleClick = { if (!playing) live.play(ch, list, selected?.name.orEmpty()); onToggleFull() },
                                    onFavorite = {
                                        val added = lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
                                        live.flash(if (added) "Agregado a favoritos" else "Quitado de favoritos")
                                    },
                                )
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }

        // ── Vista previa + guía ──
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 16.dp, end = 24.dp, start = 8.dp, bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                if (live.mode == ViewMode.EMBEDDED) PlayerView(live, Modifier.fillMaxSize(), onToggleFull, onToggleMini)
                else Box(Modifier.fillMaxSize().background(Jx.Surface), contentAlignment = Alignment.Center) {
                    Text("Reproduciendo en otra vista", color = Jx.Faint, fontFamily = Exo2)
                }
            }
            Spacer(Modifier.height(16.dp))
            val ch = live.current
            if (ch != null) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(ch.logo, ch.name, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Canal ${ch.num} · ${live.listName.removePrefix("★  ")}", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val fav = lib.isFavorite("live", ch.id)
                    RoundButton(if (fav) Ico.STAR_FILLED else Ico.STAR, if (fav) "Quitar de favoritos" else "Agregar a favoritos", small = true) {
                        lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
                    }
                }
                Spacer(Modifier.height(12.dp))
                EpgNowNext(repo, ch, now, compact = true)
            } else {
                Text("Atajos: espacio pausa · F pantalla completa · ↑↓ cambiar canal · T mini reproductor",
                    color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
            }
        }
    }
}

private fun emptyText(catId: String?) = when (catId) {
    FAV_ID -> "Aún no tienes favoritos. Usa ☆ o clic derecho sobre un canal."
    RECENT_ID -> "Aquí aparecen los últimos canales que viste."
    else -> "Esta categoría no tiene canales"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    ch: Channel, repo: TvRepo, favorite: Boolean, playing: Boolean,
    onClick: () -> Unit, onDoubleClick: () -> Unit, onFavorite: () -> Unit,
) {
    val version by repo.epgVersion
    // Lo que se emite ahora: se pide solo si la fila queda visible un instante
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) {
        if (value.isNullOrEmpty()) { delay(250); value = repo.epg(ch) }
    }
    val now = epg?.let { TvRepo.nowPlaying(it) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem(if (favorite) "Quitar de favoritos" else "Agregar a favoritos", onFavorite),
            ContextMenuItem("Ver en pantalla completa", onDoubleClick),
        )
    }) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp))
                .background(when { playing -> Jx.SurfaceHi; hovered -> Jx.Surface; else -> Color.Transparent })
                .hoverable(source)
                .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${ch.num}", color = if (playing || hovered) Jx.Accent else Jx.Faint, fontFamily = Exo2, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.width(38.dp))
            RemoteImage(ch.logo, ch.name, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ch.name, color = if (playing) Jx.Accent else Jx.Text, fontFamily = Exo2, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Text(now.label, color = if (hovered || playing) Jx.TextSoft else Jx.Faint, fontFamily = Exo2, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressLine(now.progress(), Modifier.fillMaxWidth(), height = 2.dp)
                }
            }
            if (playing) JxIcon(Ico.PLAY, 12.dp, Jx.Accent)
            if (favorite || hovered) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(50))
                    .combinedClickable(onClick = onFavorite).pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center) {
                    JxIcon(if (favorite) Ico.STAR_FILLED else Ico.STAR, 16.dp, if (favorite) Jx.Gold else Jx.Faint)
                }
            }
        }
    }
}

/** Fila de lista con fondo al pasar el mouse. */
@Composable
fun ListRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(when { selected -> Jx.SurfaceHi; hovered -> Jx.Surface; else -> Color.Transparent })
            .hoverable(source)
            .combinedClickableCompat(onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

/** Campo de búsqueda pequeño. Mientras se escribe, los atajos de teclado no actúan. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Typing.active = false } }
    Row(
        modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(Jx.Surface).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JxIcon(Ico.SEARCH, 14.dp, Jx.Faint)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Jx.Faint, fontFamily = Exo2, fontSize = 13.sp)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = Jx.Text, fontFamily = Exo2, fontSize = 13.sp),
                cursorBrush = SolidColor(Jx.Accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { Typing.active = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).combinedClickableCompat { onChange("") }
                .pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
                JxIcon(Ico.CLOSE, 11.dp, Jx.Faint)
            }
        }
    }
}

@Composable
private fun SmallToggle(ico: Ico, label: String, modifier: Modifier, @Suppress("UNUSED_PARAMETER") tip: String? = null, onClick: () -> Unit) {
    ListRowLike(modifier, onClick) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            JxIcon(ico, 15.dp, Jx.Accent)
            if (label.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(label, color = Jx.Text, fontFamily = Exo2, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ListRowLike(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (hovered) Jx.SurfaceHi else Jx.Surface)
            .hoverable(source).combinedClickableCompat(onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Mensaje centrado (cargando, vacío, error) con botón opcional. */
@Composable
fun Status(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp)
            if (action != null) { Spacer(Modifier.height(12.dp)); PillButton(action, onAction) }
        }
    }
}
