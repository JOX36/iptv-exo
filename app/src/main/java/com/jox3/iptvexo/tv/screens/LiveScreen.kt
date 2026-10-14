package com.jox3.iptvexo.tv.screens

import android.view.ViewGroup
import androidx.annotation.OptIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.jox3.iptvexo.NetClient
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.TvHost
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
private fun hhmm(ms: Long) = if (ms > 0) clock.format(Date(ms)) else ""

/**
 * En vivo, estilo guía: categorías | canales con lo que se emite | vista previa.
 * Moverse por los canales cambia la vista previa (con sonido) tras un instante;
 * OK abre el canal a pantalla completa y OK mantenido lo marca como favorito.
 */
@Composable
fun LiveScreen(repo: TvRepo, host: TvHost) {
    val lib = repo.library
    var reload by remember { mutableIntStateOf(0) }
    val cats by produceState<Result<List<Category>>?>(null, reload) {
        value = runCatching { repo.categories(Section.LIVE) }
    }
    var selected by remember { mutableStateOf<Category?>(null) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    val channelsReq = remember { FocusRequester() }
    var firstFocusDone by remember { mutableStateOf(false) }

    val catList = cats?.getOrNull()
    val allCats = remember(catList) {
        listOf(Category(FAV_ID, "Favoritos"), Category(RECENT_ID, "Recientes")) + catList.orEmpty()
    }
    // Primera categoría real al entrar
    LaunchedEffect(catList) {
        if (selected == null && catList != null) {
            selected = if (lib.favorites.any { it.kind == "live" }) allCats[0] else catList.firstOrNull()
        }
    }

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

    if (cats == null) { StatusBox("Cargando canales…"); return }
    if (catList == null) {
        StatusBox("No se pudieron cargar las categorías: ${cats?.exceptionOrNull()?.message}", onRetry = { reload++ }); return
    }

    Row(Modifier.fillMaxSize()) {
        // ── Categorías ──
        Column(Modifier.width(210.dp).fillMaxHeight().background(Jx.Rail).padding(top = 27.dp)) {
            Text("CATEGORÍAS", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            LazyColumn(
                Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 27.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(allCats, key = { it.id }) { c ->
                    val isSel = c.id == selected?.id
                    var focused by remember { mutableStateOf(false) }
                    // Al detenerse sobre una categoría se carga sola (sin pulsar OK)
                    LaunchedEffect(focused) { if (focused && !isSel) { delay(350); selected = c } }
                    FocusSurface(
                        onClick = { selected = c; runCatching { channelsReq.requestFocus() } },
                        modifier = Modifier.fillMaxWidth().height(38.dp).onFocusChanged { focused = it.isFocused },
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

        // ── Canales ──
        Column(Modifier.width(340.dp).fillMaxHeight().padding(top = 27.dp)) {
            Text(selected?.name.orEmpty(), style = Type.H1, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp))
            val list = channels?.getOrNull()
            when {
                channels == null -> StatusBox("Cargando…")
                list == null -> StatusBox("No se pudo cargar la categoría", onRetry = { reload++ })
                list.isEmpty() -> StatusBox(if (selected?.id == FAV_ID) "Mantén OK sobre un canal para agregarlo a favoritos" else "Sin canales")
                else -> LazyColumn(
                    Modifier.fillMaxSize().focusRequester(channelsReq).focusRestorer(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 27.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { i, ch ->
                        ChannelRow(
                            ch, repo,
                            favorite = lib.isFavorite("live", ch.id),
                            onFocus = { focusedChannel = ch },
                            onClick = { host.player.playChannel(list, i, selected?.name.orEmpty()) },
                            onLongClick = { lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId)) },
                        )
                    }
                }
            }
            LaunchedEffect(list) {
                if (!firstFocusDone && !list.isNullOrEmpty()) {
                    firstFocusDone = true
                    delay(50); runCatching { channelsReq.requestFocus() }
                }
            }
        }

        // ── Vista previa + guía ──
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 27.dp, end = 32.dp, start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val ch = focusedChannel
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Jx.Surface)) {
                LivePreview(ch?.let { repo.api.liveUrl(it.id) }, Modifier.fillMaxSize())
                if (ch != null) {
                    Text("EN VIVO", style = Type.Eyebrow, color = Jx.Bg,
                        modifier = Modifier.padding(10.dp).background(Jx.Live, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            if (ch != null) ChannelGuide(ch, repo)
        }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel, repo: TvRepo, favorite: Boolean,
    onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Lo que se emite ahora: se pide solo si la fila queda visible un instante
    val epg by produceState(repo.cachedEpg(ch.id), ch.id) {
        if (value == null) { delay(300); value = repo.epg(ch.id) }
    }
    val now = epg?.let { repo.nowPlaying(it) }
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(58.dp).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        },
        focusedScale = 1.03f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${ch.num}", style = Type.Number, color = if (focused) Jx.Accent else Jx.Faint, modifier = Modifier.width(34.dp))
            RemoteImage(ch.logo, ch.name, Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)), scale = ContentScale.Fit)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ch.name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Text(now.title, style = Type.Small, color = if (focused) Jx.TextSoft else Jx.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (focused) ThinProgress(now.progress(), Modifier.fillMaxWidth(), height = 2.dp)
                }
            }
            if (favorite) JxIcon(R.drawable.tv_ic_star_filled, 14.dp, Jx.Gold, "Favorito")
        }
    }
}

@Composable
private fun ChannelGuide(ch: Channel, repo: TvRepo) {
    val epg by produceState<List<EpgItem>?>(repo.cachedEpg(ch.id), ch.id) {
        if (value == null) value = repo.epg(ch.id)
    }
    val list = epg.orEmpty()
    val now = repo.nowPlaying(list)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ch.name, style = Type.Label, color = Jx.Muted)
        if (now == null) {
            Text(if (epg == null) "Cargando guía…" else "Sin guía para este canal", style = Type.Body, color = Jx.Faint)
            return@Column
        }
        Text("AHORA · ${hhmm(now.start)} – ${hhmm(now.end)}", style = Type.Eyebrow, color = Jx.Accent)
        Text(now.title, style = Type.H1, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ThinProgress(now.progress(), Modifier.fillMaxWidth().padding(vertical = 4.dp), height = 3.dp)
        if (now.description.isNotBlank()) {
            Text(now.description, style = Type.Body, color = Jx.TextSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val next = list.filter { it.start >= now.end }.take(2)
        if (next.isNotEmpty()) {
            Text("A CONTINUACIÓN", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(top = 8.dp))
            next.forEach { n ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(hhmm(n.start), style = Type.Label, color = Jx.Muted, modifier = Modifier.width(64.dp))
                    Text(n.title, style = Type.Label, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Vista previa del canal enfocado. Espera un instante antes de conectar (así
 * recorrer la lista rápido no abre decenas de conexiones) y se detiene al abrir
 * el reproductor a pantalla completa.
 */
@OptIn(UnstableApi::class)
@Composable
private fun LivePreview(url: String?, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(NetClient.player())))
            .build()
    }
    var active by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { active = false; player.stop() }
                Lifecycle.Event.ON_RESUME -> active = true
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); player.release() }
    }
    LaunchedEffect(url, active) {
        player.stop()
        if (url == null || !active) return@LaunchedEffect
        delay(700)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(0xFF111923.toInt())
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = player
            }
        },
        modifier = modifier,
    )
}
