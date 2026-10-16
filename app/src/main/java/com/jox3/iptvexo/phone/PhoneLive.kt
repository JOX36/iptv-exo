package com.jox3.iptvexo.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.Category
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.EpgItem
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.Section
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.player.LivePlayback
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import kotlinx.coroutines.delay

private const val FAV_ID = "__fav"
private const val RECENT_ID = "__recent"

/** Video en vivo con controles (vertical u horizontal). */
@Composable
fun LiveVideo(live: LivePlayback, fullscreen: Boolean, onFullscreen: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(controls, touched, live.current?.id) {
        if (controls) { delay(3500); controls = false }
    }
    val ch = live.current
    Box(modifier.background(Color.Black)) {
        if (ch == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Elige un canal para verlo aquí", style = PType.Label, color = Jx.TextSoft)
                Text("Gira el celular para verlo en grande", style = PType.Tiny, color = Jx.Faint)
            }
            return@Box
        }
        PlayerSurface(live.player, Modifier.fillMaxSize(), live.resizeMode, keepOn = live.playingNow)
        GestureLayer(onTap = { controls = !controls; touched = System.currentTimeMillis() }, onSeek = null)
        live.status?.let { st ->
            Text(st, style = PType.Small, color = Jx.Text, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xCC070A0F), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color.Transparent, Color(0xB3000000)))))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (fullscreen) RoundIcon(R.drawable.ic_back, "Salir de pantalla completa", onClick = onBack)
                Text("EN VIVO", style = PType.Tiny, color = Color.White,
                    modifier = Modifier.padding(start = 10.dp).background(Jx.Live, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
                if (fullscreen) Text(ch.name, style = PType.Label, color = Jx.Text, maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp).weight(1f)) else Spacer(Modifier.weight(1f))
                if (live.quality.isNotEmpty()) Text(live.quality, style = PType.Tiny, color = Jx.Accent, modifier = Modifier.padding(end = 10.dp))
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(R.drawable.ic_prev, "Canal anterior", bg = Color(0x80070A0F)) { live.zap(-1); touched = System.currentTimeMillis() }
                val playing = live.player?.isPlaying == true || live.playingNow
                RoundIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pausa" else "Reproducir",
                    size = 58.dp, bg = Color(0xEEEAF4FA), tint = Jx.Bg, iconSize = 24.dp) {
                    live.player?.let { if (it.isPlaying) it.pause() else it.play() }; touched = System.currentTimeMillis()
                }
                RoundIcon(R.drawable.ic_next, "Canal siguiente", bg = Color(0x80070A0F)) { live.zap(+1); touched = System.currentTimeMillis() }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                RoundIcon(R.drawable.ic_fullscreen, if (fullscreen) "Salir de pantalla completa" else "Pantalla completa",
                    onClick = if (fullscreen) onBack else onFullscreen)
            }
        }
    }
}

/**
 * En vivo: el video queda fijo arriba; debajo, el canal y lo que dan ahora, y dos
 * pestañas: Canales (categorías en pastillas + lista) y Guía (programación del canal).
 */
@Composable
fun PhoneLiveScreen(repo: TvRepo, live: LivePlayback, onFullscreen: () -> Unit) {
    val lib = repo.library
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(0) }
    val cats by produceState<List<Category>?>(null) { value = runCatching { repo.categories(Section.LIVE) }.getOrDefault(emptyList()) }
    val allCats = remember(cats) { listOf(Category(FAV_ID, "★ Favoritos"), Category(RECENT_ID, "Recientes")) + cats.orEmpty() }
    var selected by remember { mutableStateOf<Category?>(null) }
    LaunchedEffect(cats) {
        if (selected == null && cats != null) {
            val saved = prefs.getString("phone_live_cat", null)
            selected = allCats.firstOrNull { it.id == saved }
                ?: if (lib.favorites.any { it.kind == "live" }) allCats[0] else cats!!.firstOrNull() ?: allCats[0]
        }
    }
    LaunchedEffect(selected?.id) { selected?.id?.let { prefs.edit().putString("phone_live_cat", it).apply() } }
    val favKey = if (selected?.id == FAV_ID) lib.favorites.size else -1
    val channels by produceState<List<Channel>?>(null, selected, favKey) {
        val c = selected ?: return@produceState
        value = null
        value = runCatching {
            when (c.id) {
                FAV_ID -> lib.favorites.filter { it.kind == "live" }.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
                RECENT_ID -> lib.recentChannels.mapIndexed { i, s -> Channel(s.id, s.name, s.image, s.categoryId, i + 1) }
                else -> repo.channels(c.id)
            }
        }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        LiveVideo(live, fullscreen = false, onFullscreen = onFullscreen, onBack = {}, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))

        // Canal actual
        val ch = live.current
        if (ch != null) NowPlayingRow(ch, repo, lib.isFavorite("live", ch.id)) {
            lib.toggleFavorite(SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId))
        }

        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Canales", tab == 0) { tab = 0 }
            Pill("Guía", tab == 1) { tab = 1 }
        }

        if (tab == 0) {
            val chipState = rememberLazyListState()
            LazyRow(state = chipState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allCats, key = { it.id }) { c -> Pill(c.name, c.id == selected?.id) { selected = c } }
            }
            LaunchedEffect(cats, selected?.id) {
                val i = allCats.indexOfFirst { it.id == selected?.id }
                if (i > 0) runCatching { chipState.scrollToItem((i - 1).coerceAtLeast(0)) }
            }
            val list = channels
            when {
                cats == null || list == null -> CenterText("Cargando…")
                list.isEmpty() -> CenterText(if (selected?.id == FAV_ID) "Mantén pulsado un canal para agregarlo a favoritos" else "Sin canales")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 16.dp)) {
                    itemsIndexed(list, key = { _, c -> c.id }) { _, c ->
                        ChannelRow(c, repo, playing = live.current?.id == c.id, favorite = lib.isFavorite("live", c.id),
                            onClick = {
                                lib.addRecentChannel(SavedItem("live", c.id, c.name, c.logo, categoryId = c.categoryId))
                                live.play(c, list, selected?.name.orEmpty())
                            },
                            onLongClick = { lib.toggleFavorite(SavedItem("live", c.id, c.name, c.logo, categoryId = c.categoryId)) })
                    }
                }
            }
        } else {
            if (ch == null) CenterText("Elige un canal para ver su guía") else ChannelGuideList(ch, repo)
        }
    }
}

@Composable
private fun NowPlayingRow(ch: Channel, repo: TvRepo, favorite: Boolean, onFav: () -> Unit) {
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(ch), ch.id, version) { value = repo.epg(ch) }
    val now = epg?.let { repo.nowPlaying(it) }
    Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteImage(ch.logo, ch.name, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Jx.SurfaceHi), scale = ContentScale.Fit)
            Column(Modifier.weight(1f)) {
                Text("${ch.num} · ${ch.name}", style = PType.Small, color = Jx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now?.title ?: "Sin guía", style = PType.H2, color = Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundIcon(if (favorite) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star, if (favorite) "En favoritos" else "Agregar a favoritos",
                tint = if (favorite) Jx.Gold else Jx.Muted, onClick = onFav)
        }
        if (now != null) Row(Modifier.padding(top = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(clock(now.start), style = PType.Tiny, color = Jx.Faint)
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Jx.Line)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress()).background(Jx.Accent))
            }
            Text(clock(now.end), style = PType.Tiny, color = Jx.Faint)
        }
    }
}

@Composable
private fun ChannelRow(c: Channel, repo: TvRepo, playing: Boolean, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val version by repo.epgVersion
    val epg by produceState(repo.cachedEpg(c), c.id, version) {
        if (value.isNullOrEmpty()) { delay(250); value = repo.epg(c) }
    }
    val now = epg?.let { repo.nowPlaying(it) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (playing) Modifier.background(Jx.SurfaceHi).border(1.dp, Jx.Accent, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteImage(c.logo, c.name, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Jx.Surface), scale = ContentScale.Fit)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${c.num} · ${c.name}", style = PType.Label, color = if (playing) Jx.Accent else Jx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Text(now.title, style = PType.Small, color = Jx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Jx.Line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress()).background(if (playing) Jx.Accent else Jx.Muted))
                }
            }
        }
        if (favorite) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.tv_ic_star_filled), "Favorito",
            Modifier.size(16.dp), tint = Jx.Gold)
    }
}

@Composable
private fun ChannelGuideList(ch: Channel, repo: TvRepo) {
    val version by repo.epgVersion
    val epg by produceState<List<EpgItem>?>(repo.cachedEpg(ch), ch.id, version) { value = repo.epg(ch) }
    val list = epg
    val nowMs = System.currentTimeMillis()
    when {
        list == null -> CenterText("Cargando guía…")
        list.isEmpty() -> CenterText("Este canal no tiene guía")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list.filter { it.end > nowMs }) { p ->
                val isNow = nowMs in p.start until p.end
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .then(if (isNow) Modifier.background(Jx.SurfaceHi).border(1.dp, Jx.Accent, RoundedCornerShape(14.dp)) else Modifier)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.width(74.dp)) {
                        Text(if (isNow) "AHORA" else clock(p.start), style = PType.Label, color = if (isNow) Jx.Accent else Jx.TextSoft)
                        if (isNow) Text(clock(p.start), style = PType.Tiny, color = Jx.Muted)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(p.title, style = PType.Label, color = Jx.Text)
                        if (p.description.isNotBlank()) Text(p.description, style = PType.Small, color = Jx.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (isNow) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Jx.Line)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(p.progress()).background(Jx.Accent))
                        }
                    }
                }
            }
        }
    }
}
