package com.jox3.iptvexo.phone

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.player.VodPlayback
import com.jox3.iptvexo.tv.player.techInfo
import com.jox3.iptvexo.tv.player.trackLabel
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.produceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val clockFmt = SimpleDateFormat("h:mm a", Locale("es"))
internal fun clock(ms: Long): String = if (ms > 0) clockFmt.format(Date(ms)) else ""
internal fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
internal fun speedLabel(s: Float) = if (abs(s - 1f) < 0.01f) "Normal" else "${s.toString().trimEnd('0').trimEnd('.')}x"

/** Video de ExoPlayer (sin controles propios: los dibuja la app). */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(player: ExoPlayer?, modifier: Modifier = Modifier, resizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, keepOn: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { v ->
            if (v.player !== player) v.player = player
            v.resizeMode = resizeMode
            v.keepScreenOn = keepOn
        },
        modifier = modifier.background(Color.Black),
    )
}

/**
 * Capa de gestos de pantalla completa: un toque muestra/oculta los controles,
 * doble toque a los lados ±10 s (si [onSeek]), deslizar en vertical a la izquierda
 * cambia el brillo y a la derecha el volumen.
 */
@Composable
fun GestureLayer(onTap: () -> Unit, onSeek: ((Long) -> Unit)?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var hud by remember { mutableStateOf<Pair<String, Float>?>(null) }   // "Brillo"/"Volumen" + nivel
    var hudTick by remember { mutableIntStateOf(0) }
    var seekHud by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hudTick) { if (hudTick > 0) { delay(900); hud = null; seekHud = null } }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(onSeek) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { o ->
                        val s = onSeek ?: return@detectTapGestures
                        val forward = o.x > size.width / 2
                        s(if (forward) 10_000 else -10_000)
                        seekHud = if (forward) "+10 s" else "-10 s"; hudTick++
                    },
                )
            }
            .pointerInput(Unit) {
                var left = true
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        left = o.x < size.width / 2
                        level = if (left) {
                            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                        } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    },
                ) { _, dy ->
                    level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                    if (left) {
                        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.02f) } }
                        hud = "Brillo" to level
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                        hud = "Volumen" to level
                    }
                    hudTick++
                }
            },
    ) {
        val h = hud
        if (h != null) {
            Column(
                Modifier.align(if (h.first == "Brillo") Alignment.CenterStart else Alignment.CenterEnd).padding(28.dp)
                    .background(Color(0xCC070A0F), RoundedCornerShape(14.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(h.first, style = PType.Tiny, color = if (h.first == "Brillo") Jx.Gold else Jx.Accent)
                Box(Modifier.width(6.dp).height(110.dp).clip(RoundedCornerShape(3.dp)).background(Jx.Line), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(h.second).background(if (h.first == "Brillo") Jx.Gold else Jx.Accent))
                }
                Text("${(h.second * 100).toInt()}%", style = PType.Tiny, color = Jx.Text)
            }
        }
        seekHud?.let {
            Text(it, style = PType.H1, color = Jx.Text,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC070A0F), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
        }
    }
}

/** Barra de tiempo táctil (arrastrar para mover). */
@Composable
fun SeekSlider(vod: VodPlayback, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = vod.duration
    val pos = if (dragging) (dragValue * dur).toLong() else vod.position
    Column(modifier) {
        Slider(
            value = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { vod.player?.seekTo((dragValue * dur).toLong()); dragging = false },
            enabled = dur > 0,
            colors = SliderDefaults.colors(thumbColor = Jx.Accent, activeTrackColor = Jx.Accent, inactiveTrackColor = Color(0x55FFFFFF)),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(pos), style = PType.Tiny, color = Jx.TextSoft)
            Spacer(Modifier.weight(1f))
            if (dur > 0) {
                val left = ((dur - pos) / vod.speed).toLong()
                Text("-${fmtTime(dur - pos)} · termina ${clock(System.currentTimeMillis() + left)}", style = PType.Tiny, color = Jx.TextSoft)
            }
        }
    }
}

/** Controles sobre el video de película/serie (vertical u horizontal). */
@Composable
fun VodVideo(vod: VodPlayback, fullscreen: Boolean, onFullscreen: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
             onOption: (String) -> Unit = {}) {
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // El aviso de Ver juntos aparece y se esconde junto con los controles
    LaunchedEffect(controls) { com.jox3.iptvexo.tv.player.WatchTogether.controlsVisible = controls }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.jox3.iptvexo.tv.player.WatchTogether.controlsVisible = false } }
    LaunchedEffect(controls, touched, vod.playing) {
        if (controls && vod.playing) { delay(3500); controls = false }
    }
    val cur = vod.item
    var locked by remember { mutableStateOf(false) }
    var castSheet by remember { mutableStateOf(false) }
    if (castSheet) CastSheet { castSheet = false }
    val casting = LocalCast.current?.connectedTo != null
    Box(modifier.background(Color.Black)) {
        PlayerSurface(vod.player, Modifier.fillMaxSize(), vod.resizeMode, keepOn = vod.playing)
        // Transmitiendo al Chromecast: en lugar del video, sus controles
        if (casting && cur != null) {
            CastingOverlay(listOf(cur.title, cur.subtitle.takeIf { it != "Película" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                live = false, onOpenSheet = { castSheet = true })
            RoundIcon(if (fullscreen) R.drawable.ic_back else R.drawable.ic_chevron_down, if (fullscreen) "Salir de pantalla completa" else "Cerrar",
                Modifier.align(Alignment.TopStart).padding(4.dp), onClick = onBack)
            return@Box
        }
        if (locked) { LockOverlay { locked = false; controls = true; touched = System.currentTimeMillis() }; return@Box }
        GestureLayer(
            onTap = { controls = !controls; touched = System.currentTimeMillis() },
            onSeek = if (fullscreen) { ms -> vod.seekBy(ms) } else null,
        )
        vod.status?.let { st ->
            Text(st, style = PType.Small, color = Jx.Text, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xCC070A0F), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color.Transparent, Color(0xB3000000)))))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(if (fullscreen) R.drawable.ic_back else R.drawable.ic_chevron_down, if (fullscreen) "Salir de pantalla completa" else "Cerrar", onClick = onBack)
                if (fullscreen && cur != null) Column(Modifier.weight(1f)) {
                    Text(cur.subtitle.ifBlank { cur.title }, style = PType.Label, color = Jx.Text, maxLines = 1)
                    if (cur.subtitle.isNotBlank()) Text(cur.title, style = PType.Tiny, color = Jx.TextSoft, maxLines = 1)
                } else Spacer(Modifier.weight(1f))
                if (vod.quality.isNotEmpty()) Text(vod.quality, style = PType.Tiny, color = Jx.Accent, modifier = Modifier.padding(end = 8.dp))
                CastButton { castSheet = true }
                PipButton()
                if (fullscreen) RoundIcon(R.drawable.ic_lock_open, "Bloquear pantalla") { locked = true; controls = false }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(R.drawable.ic_rew10, "Atrasar 10 segundos", bg = Color(0x80070A0F)) { vod.seekBy(-10_000); touched = System.currentTimeMillis() }
                RoundIcon(if (vod.playing) R.drawable.ic_pause else R.drawable.ic_play, if (vod.playing) "Pausa" else "Reproducir",
                    size = 62.dp, bg = Color(0xEEEAF4FA), tint = Jx.Bg, iconSize = 26.dp) { vod.togglePlay(); touched = System.currentTimeMillis() }
                RoundIcon(R.drawable.ic_ffw10, "Adelantar 10 segundos", bg = Color(0x80070A0F)) { vod.seekBy(10_000); touched = System.currentTimeMillis() }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = if (fullscreen) 10.dp else 2.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeekSlider(vod, Modifier.weight(1f))
                    RoundIcon(R.drawable.ic_fullscreen, if (fullscreen) "Salir de pantalla completa" else "Pantalla completa",
                        onClick = if (fullscreen) onBack else onFullscreen)
                }
                // En pantalla completa, las mismas opciones del reproductor de TV
                if (fullscreen) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vodOptions(vod).forEach { (key, label, icon) ->
                        item(key = key) { OverlayPill(label, icon) { touched = System.currentTimeMillis(); onOption(key) } }
                    }
                }
            }
        }
    }
}

/**
 * Película o serie en vertical: video arriba; debajo título, opciones rápidas,
 * siguiente episodio y la lista de episodios. Al girar el celular → pantalla completa.
 */
@Composable
fun VodScreen(vod: VodPlayback, lib: LibraryStore, onFullscreen: () -> Unit, onClose: () -> Unit,
              repo: com.jox3.iptvexo.tv.data.TvRepo? = null, onPlayMovie: (com.jox3.iptvexo.tv.data.Movie) -> Unit = {}) {
    val cur = vod.item ?: return
    var sheet by remember { mutableStateOf<String?>(null) }
    val isMovie = cur.season == 0 && vod.queue.size == 1
    // Temporadas de la serie en reproducción: se cambian sin salir del reproductor
    val seasons = remember(vod.queue) { vod.queue.map { it.season }.distinct().sorted() }
    var season by remember(vod.queue) { mutableIntStateOf(cur.season) }
    Column(Modifier.fillMaxSize().background(Jx.Bg)) {
        VodVideo(vod, fullscreen = false, onFullscreen = onFullscreen, onBack = onClose,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), onOption = { sheet = it })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (isMovie) cur.title else cur.subtitle, style = PType.H1, color = Jx.Text)
                    val meta = vod.metaOf(cur)
                    Text(listOf(if (isMovie) "Película" else cur.title, meta).filter { it.isNotBlank() }.joinToString(" · "),
                        style = PType.Small, color = Jx.Muted, maxLines = 2)
                }
            }
            item {
                val isFav = lib.isFavorite(cur.fav.kind, cur.fav.id)
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vodOptions(vod).filter { it.first != "next" }.forEach { (key, label, icon) ->
                        item(key = key) {
                            Pill(label, (key == "speed" && abs(vod.speed - 1f) > 0.01f) || (key == "sleep" && vod.sleepAt != 0L), icon = icon, glass = true) { sheet = key }
                        }
                    }
                    item(key = "fav") { Pill(if (isFav) "En favoritos" else "Favorito", isFav,
                        icon = if (isFav) R.drawable.tv_ic_star_filled else R.drawable.tv_ic_star, glass = true) { lib.toggleFavorite(cur.fav) } }
                }
            }
            // Sinopsis (películas y episodios)
            val plot = vod.plotOf(cur)
            item {
                Text(plot.ifBlank { if (isMovie) "Cargando sinopsis…" else "" }, style = PType.Body, color = Jx.TextSoft,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
            }
            // Película: carátula con la ficha (dirección, reparto) y "Más como esta" en el espacio libre
            if (isMovie) {
                val cr = vod.credits[cur.id]
                item { MovieCard(cur, vod.metaOf(cur), cr) }
                if (repo != null) item {
                    val base = remember(cur.id) {
                        com.jox3.iptvexo.tv.data.Movie(cur.id, cur.title, cur.image, "", cur.resume.ext, cur.resume.categoryId, 0L)
                    }
                    val genre = cr?.third.orEmpty()
                    val similar by produceState(emptyList<com.jox3.iptvexo.tv.data.Movie>(), cur.id, cr != null) {
                        if (cr == null) return@produceState
                        value = runCatching { repo.similarMovies(base, genre, 12) }.getOrDefault(emptyList())
                    }
                    if (similar.isNotEmpty()) Column {
                        SectionTitle("Más como esta", Modifier.padding(top = 18.dp, bottom = 10.dp))
                        similar.chunked(3).forEach { row ->
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { m -> PosterTile(m.name, m.poster, Modifier.weight(1f), favorite = lib.isFavorite("movie", m.id)) { onPlayMovie(m) } }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            val next = vod.queue.getOrNull(vod.index + 1)
            if (next != null) item {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Jx.Surface).clickable { vod.next() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RemoteImage(next.image, next.title, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text("SIGUIENTE", style = PType.Eyebrow, color = Jx.Accent)
                        Text(next.title, style = PType.Label, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    RoundIcon(R.drawable.ic_next, "Ver siguiente episodio", bg = Jx.Text, tint = Jx.Bg) { vod.next() }
                }
            }
            if (vod.queue.size > 1) {
                item { SectionTitle("Episodios", Modifier.padding(top = 6.dp, bottom = 8.dp)) }
                if (seasons.size > 1) item {
                    LazyRow(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons.size) { k -> val sn = seasons[k]; Pill("Temporada $sn", sn == season, glass = true) { season = sn } }
                    }
                }
                itemsIndexed(vod.queue, key = { _, it -> it.id }) { i, ep ->
                    if (seasons.size > 1 && ep.season != season) return@itemsIndexed
                    val playing = i == vod.index
                    val (p, d) = lib.positionOf(ep.id)
                    Row(
                        Modifier.fillMaxWidth().clickable { if (!playing) vod.open(vod.queue, i) }.padding(horizontal = 18.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                            RemoteImage(ep.image, ep.title, Modifier.fillMaxSize())
                            val frac = if (playing && vod.duration > 0) vod.position.toFloat() / vod.duration else if (d > 0) p.toFloat() / d else 0f
                            if (frac > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(Jx.Accent))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(ep.title, style = PType.Label, color = if (playing) Jx.Accent else Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (playing) "Reproduciendo" else "", style = PType.Tiny, color = Jx.Muted)
                        }
                    }
                }
            }
        }
    }
    VodSheets(vod, sheet) { sheet = null }
}

/** Opciones del reproductor de películas y series (las mismas de la TV). */
internal fun vodOptions(vod: VodPlayback): List<Triple<String, String, Int>> = buildList {
    if (vod.hasNext) add(Triple("next", "Siguiente", R.drawable.ic_next))
    add(Triple("audio", "Audio", R.drawable.ic_audio))
    add(Triple("subs", "Subtítulos", R.drawable.ic_subs))
    add(Triple("speed", speedLabel(vod.speed).let { if (it == "Normal") "Velocidad" else it }, R.drawable.ic_ffw10))
    add(Triple("sleep", if (vod.sleepAt != 0L) "Apagado activo" else "Apagado", R.drawable.ic_stop))
    add(Triple("picture", "Imagen", R.drawable.ic_resize))
    add(Triple("info", "Información", R.drawable.tv_ic_info))
    add(Triple("together", "Ver juntos", R.drawable.ic_together))
}

/** Pastilla translúcida sobre el video (pantalla completa). */
@Composable
fun OverlayPill(label: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x66101722)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(16.dp), tint = Jx.TextSoft)
        Text(label, style = PType.Label, color = Jx.Text, maxLines = 1)
    }
}

/** Formato de imagen. */
@Composable
fun PictureSheet(mode: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    ChoiceSheet("Imagen", listOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar (sin recortar)",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Ampliar (llenar recortando)",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL to "Estirar",
    ).map { (m, label) -> Triple(label, m == mode) { onSet(m) } }, onDismiss)
}

/** Información: título, sinopsis/programa y datos técnicos. */
@Composable
fun InfoSheet(title: String, subtitle: String, body: String, tech: List<Pair<String, String>>, onDismiss: () -> Unit) {
    PlayerSheet(onDismiss) {
        LazyColumn(Modifier.padding(horizontal = 22.dp), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(title, style = PType.H1, color = Jx.Text) }
            if (subtitle.isNotBlank()) item { Text(subtitle, style = PType.Small, color = Jx.Muted) }
            if (body.isNotBlank()) item { Text(body, style = PType.Body, color = Jx.TextSoft) }
            item { Text("INFORMACIÓN TÉCNICA", style = PType.Eyebrow, color = Jx.Muted, modifier = Modifier.padding(top = 10.dp)) }
            items(tech.size) { k ->
                val (label, value) = tech[k]
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = PType.Label, color = Jx.Muted, modifier = Modifier.width(120.dp))
                    Text(value.ifBlank { "—" }, style = PType.Label, color = Jx.Text)
                }
            }
        }
    }
}

/** Hojas de opciones del reproductor de películas y series. */
@Composable
fun VodSheets(vod: VodPlayback, sheet: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cur = vod.item
    when (sheet) {
        "next" -> { LaunchedEffect(Unit) { vod.next(); onDismiss() } }
        "picture" -> PictureSheet(vod.resizeMode, { vod.resizeMode = it }, onDismiss)
        "info" -> if (cur != null) InfoSheet(
            title = if (cur.season == 0 && vod.queue.size == 1) cur.title else cur.subtitle,
            subtitle = listOf(if (cur.season == 0 && vod.queue.size == 1) "Película" else cur.title, vod.metaOf(cur)).filter { it.isNotBlank() }.joinToString(" · "),
            body = vod.plotOf(cur), tech = techInfo(ctx, vod.player, cur.url), onDismiss = onDismiss)
        "together" -> PhoneTogetherSheet(vod, onDismiss)
        else -> VodChoiceSheets(vod, sheet, onDismiss)
    }
}

@Composable
private fun VodChoiceSheets(vod: VodPlayback, sheet: String?, onDismiss: () -> Unit) {
    when (sheet) {
        "audio" -> TrackSheet(vod.player, vod.tracksVersion, C.TRACK_TYPE_AUDIO) { onDismiss() }
        "subs" -> TrackSheet(vod.player, vod.tracksVersion, C.TRACK_TYPE_TEXT) { onDismiss() }
        "speed" -> ChoiceSheet("Velocidad", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { s ->
            Triple(speedLabel(s), abs(s - vod.speed) < 0.01f) { vod.setPlaybackSpeed(s) } }) { onDismiss() }
        "sleep" -> ChoiceSheet("Apagado automático", listOf(0, 15, 30, 45, 60, 90, 120, -1).map { m ->
            Triple(when (m) { 0 -> "Desactivado"; -1 -> "Al terminar este video"; else -> "En $m minutos" },
                (m == 0 && vod.sleepAt == 0L) || (m == -1 && vod.sleepAt < 0)) { vod.setSleep(m) } }) { onDismiss() }
    }
}

/** Película o serie a pantalla completa (horizontal), con sus hojas de opciones. */
@Composable
fun VodFullscreenPhone(vod: VodPlayback, onBack: () -> Unit) {
    var sheet by remember { mutableStateOf<String?>(null) }
    VodVideo(vod, fullscreen = true, onFullscreen = {}, onBack = onBack, modifier = Modifier.fillMaxSize(), onOption = { sheet = it })
    androidx.compose.runtime.CompositionLocalProvider(LocalSidePanels provides true) { VodSheets(vod, sheet) { sheet = null } }
}

/** true dentro del reproductor a pantalla completa: los menús salen como panel lateral. */
val LocalSidePanels = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Menú del reproductor. En vertical: hoja inferior completa. A pantalla completa:
 * panel translúcido pegado a la izquierda (como en la TV), con el video visible
 * detrás; tocar fuera o Atrás lo cierra.
 */
@Composable
fun PlayerSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (LocalSidePanels.current) {
        androidx.activity.compose.BackHandler(onBack = onDismiss)
        val none = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(Modifier.fillMaxSize().clickable(interactionSource = none, indication = null, onClick = onDismiss)) {
            Column(
                Modifier.fillMaxHeight().width(400.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xE0070A0F), Color(0xB3070A0F), Color(0x40070A0F))))
                    .clickable(interactionSource = none, indication = null) { }
                    .padding(top = 18.dp, bottom = 8.dp),
                content = content,
            )
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Jx.Surface, sheetState = fullSheet(), content = content)
    }
}

/** Hojas inferiores siempre abiertas completas (no a medias): todo se ve sin arrastrar. */
@Composable
fun fullSheet() = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

/** Hoja inferior con opciones (la elegida lleva ✓). */
@Composable
fun ChoiceSheet(title: String, options: List<Triple<String, Boolean, () -> Unit>>, onDismiss: () -> Unit) {
    PlayerSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(title, style = PType.H2, color = Jx.Text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            options.forEach { (label, sel, action) ->
                Row(
                    Modifier.fillMaxWidth().clickable { action(); onDismiss() }.padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = PType.Body, color = if (sel) Jx.Accent else Jx.Text, modifier = Modifier.weight(1f))
                    if (sel) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.tv_ic_check), "Elegido",
                        Modifier.size(18.dp), tint = Jx.Ok)
                }
            }
        }
    }
}

/** Audio o subtítulos de un reproductor. */
@OptIn(UnstableApi::class)
@Composable
fun TrackSheet(player: ExoPlayer?, tracksVersion: Int, type: Int, onDismiss: () -> Unit) {
    val p = player ?: return onDismiss()
    @Suppress("UNUSED_VARIABLE") val v = tracksVersion
    val options = buildList<Triple<String, Boolean, () -> Unit>> {
        if (type == C.TRACK_TYPE_TEXT) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            add(Triple("Desactivados", off) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        var n = 0
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                add(Triple(trackLabel(g.getTrackFormat(i), type, n), g.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                })
            }
        }
        if (isEmpty()) add(Triple(if (type == C.TRACK_TYPE_AUDIO) "Audio principal" else "Sin subtítulos", true) {})
    }
    ChoiceSheet(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulos", options, onDismiss)
}


/** Ficha bajo el video: carátula + año/género/duración, dirección y reparto. */
@Composable
private fun MovieCard(cur: com.jox3.iptvexo.tv.player.VodItem, meta: String, credits: Triple<String, String, String>?) {
    Row(
        Modifier.padding(horizontal = 18.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Jx.Surface).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RemoteImage(cur.image, cur.title, Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(cur.title, style = PType.Label, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) Text(meta, style = PType.Small, color = Jx.Muted)
            val (director, cast, _) = credits ?: Triple("", "", "")
            if (director.isNotBlank()) Credit("Dirección", director)
            if (cast.isNotBlank()) Credit("Reparto", cast)
            if (credits == null) Text("Cargando ficha…", style = PType.Small, color = Jx.Faint)
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = PType.Eyebrow, color = Jx.Faint)
        Text(value, style = PType.Small, color = Jx.TextSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Pantalla bloqueada: los toques no hacen nada (ni pausan ni cambian de canal) y
 * Atrás no sale. Un toque muestra el candado; tocarlo desbloquea.
 */
@Composable
fun LockOverlay(onUnlock: () -> Unit) {
    var show by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(show, tick) { if (show) { delay(2500); show = false } }
    BackHandler { show = true; tick++ }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { show = true; tick++ } }) {
        if (show) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIcon(R.drawable.ic_lock, "Desbloquear pantalla", size = 64.dp, bg = Color(0xCC070A0F), iconSize = 28.dp, onClick = onUnlock)
            Text("Pantalla bloqueada · toca el candado para desbloquear", style = PType.Small, color = Jx.TextSoft,
                modifier = Modifier.background(Color(0x99070A0F), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}


/** Botón de ventana flotante (PiP): el video sigue encima de otras apps. */
@Composable
fun PipButton() {
    val act = LocalContext.current as? PhoneActivity ?: return
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
    RoundIcon(R.drawable.ic_pip, "Ventana flotante") { act.enterPip() }
}
