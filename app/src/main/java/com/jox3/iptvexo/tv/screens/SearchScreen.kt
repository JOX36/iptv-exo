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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.Nav
import com.jox3.iptvexo.tv.Route
import com.jox3.iptvexo.tv.TvHost
import com.jox3.iptvexo.tv.player.LocalLive
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.Movie
import com.jox3.iptvexo.tv.data.Series
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.ServerHub
import com.jox3.iptvexo.tv.ui.ChannelTile
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.ShelfRow
import com.jox3.iptvexo.tv.ui.StatusBox
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val KEYS = "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ0123456789".map { it.toString() }


/**
 * Búsqueda con teclado en pantalla: con el control es mucho más rápido que el
 * teclado de Android. Busca en canales, películas y series a la vez, sin
 * importar tildes ni mayúsculas.
 */
@Composable
fun SearchScreen(repo: TvRepo, nav: Nav, host: TvHost, account: Account, accounts: List<Account>, onSwitch: (Account) -> Unit) {
    val live = LocalLive.current
    val lib = repo.library
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    // Servidor elegido en el filtro (null = todos)
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf<ServerHub.Hit?>(null) }
    val multi = accounts.size > 1 && ServerHub.searchAllEnabled(ctx)
    val others = remember(accounts, account) { accounts.filter { it.key != account.key } }
    LaunchedEffect(multi) { if (multi) ServerHub.ensureIndexes(others) }
    val indexVersion by ServerHub.indexVersion
    val index by produceState<TvRepo.Index?>(null) { value = repo.searchIndex() }
    val results by produceState<ServerHub.Results?>(null, query, index, only, indexVersion) {
        val idx = index ?: return@produceState
        val q = TvRepo.normalize(query.trim())
        if (q.length < 2) { value = null; return@produceState }
        delay(220)   // esperar a que termine de escribir (si pulsa otra letra, esto se cancela)
        value = ServerHub.search(query, account, idx, accounts, only, includeOthers = multi)
    }
    fun letters(h: ServerHub.Hit) = if (multi) h.servers.map { ServerHub.letter(accounts, it) } else emptyList()
    /** OK en un resultado: si solo está en este servidor se abre; si no, se elige de dónde. */
    fun open(h: ServerHub.Hit, direct: () -> Unit) {
        lib.addSearch(query)
        if (h.copies.all { it.account.key == account.key }) direct() else choose = h
    }
    fun openCopy(c: ServerHub.Copy, channels: List<Channel>) {
        choose = null
        if (c.account.key != account.key) { ServerHub.pendingOpen = c; onSwitch(c.account); return }
        when (c.kind) {
            "movie" -> nav.open(Route.MovieDetail(Movie(c.id, c.name, c.image, c.rating, c.ext, c.categoryId, 0L)))
            "series" -> nav.open(Route.SeriesDetail(c.id, c.name, c.image))
            else -> {
                val i = channels.indexOfFirst { it.id == c.id }
                if (i >= 0) live.playFullscreen(channels, i, "Búsqueda")
                else live.playFullscreen(listOf(Channel(c.id, c.name, c.image, c.categoryId, 1)), 0, "Búsqueda")
            }
        }
    }
    val firstKey = remember { FocusRequester() }
    // ── Búsqueda por voz (reconocedor de Google del equipo) ──
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val said = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) query = said.lowercase()
    }
    fun listen() {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag().ifBlank { "es-CO" })
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Di qué quieres ver")
        try { voice.launch(i) } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Este equipo no tiene búsqueda por voz", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // Se llegó con el botón de búsqueda del control: abrir el micrófono
    LaunchedEffect(Unit) {
        if (com.jox3.iptvexo.tv.GoogleTv.voiceRequested) { com.jox3.iptvexo.tv.GoogleTv.voiceRequested = false; delay(250); listen() }
    }
    // Si se queda unos segundos en una búsqueda con resultados, se guarda en el historial
    LaunchedEffect(results) {
        val r = results ?: return@LaunchedEffect
        if (r.isEmpty) return@LaunchedEffect
        delay(4000)
        lib.addSearch(query)
    }

    Row(Modifier.fillMaxSize()) {
        // ── Teclado ──
        Column(Modifier.width(300.dp).fillMaxHeight().background(Jx.Rail).padding(horizontal = 20.dp, vertical = 27.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("BUSCAR", style = Type.Eyebrow, color = Jx.Muted)
            Box(Modifier.fillMaxWidth().height(44.dp).background(Jx.Surface, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    JxIcon(R.drawable.tv_ic_search, 16.dp, Jx.Muted)
                    Text(query.ifEmpty { "Escribe con el teclado…" }, style = Type.Label,
                        color = if (query.isEmpty()) Jx.Faint else Jx.Text, maxLines = 1)
                }
            }
            KEYS.chunked(6).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEachIndexed { c, k ->
                        Key(k, Modifier.weight(1f).let { if (r == 0 && c == 0) it.focusRequester(firstKey) else it }) { query += k.lowercase() }
                    }
                    repeat(6 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Key("Voz", Modifier.weight(1f)) { listen() }
                Key("Espacio", Modifier.weight(1f)) { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " }
                Key("Borrar", Modifier.weight(1f)) { query = query.dropLast(1) }
                Key("Limpiar", Modifier.weight(1f)) { query = "" }
            }
        }

        // ── Resultados ──
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val r = results
            // Filtro por servidor + en qué servidores se está buscando
            if (multi) ServerFilter(accounts, account, only) { only = it }
            when {
                index == null -> StatusBox("Preparando la búsqueda en todo el catálogo…")
                query.trim().length < 2 -> SearchHistory(
                    lib.searchHistory.toList(),
                    onPick = { query = it },
                    onRemove = { lib.removeSearch(it) },
                    onClear = { lib.clearSearches() },
                )
                r == null -> StatusBox("Buscando…")
                r.isEmpty -> StatusBox("Sin resultados para “${query.trim()}”")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 27.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Canales del servidor actual (para cambiar con ▲▼ dentro de los resultados)
                    val here = r.channels.mapNotNull { h -> h.copies.firstOrNull { it.account.key == account.key } }
                        .mapIndexed { i, c -> Channel(c.id, c.name, c.image, c.categoryId, i + 1) }
                    if (r.channels.isNotEmpty()) item(key = "ch") {
                        ShelfRow("En vivo · ${r.channels.size}") {
                            items(r.channels, key = { "c" + it.key }) { h ->
                                ChannelTile(h.name, h.image, badges = letters(h), onClick = {
                                    open(h) { openCopy(h.copies.first(), here) }
                                })
                            }
                        }
                    }
                    if (r.movies.isNotEmpty()) item(key = "mv") {
                        ShelfRow("Películas · ${r.movies.size}") {
                            items(r.movies, key = { "m" + it.key }) { h ->
                                PosterCard(h.name, h.image, onClick = { open(h) { openCopy(h.copies.first(), here) } }, width = 96.dp,
                                    subtitle = h.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                                    favorite = h.copies.any { it.account.key == account.key && lib.isFavorite("movie", it.id) },
                                    badges = letters(h))
                            }
                        }
                    }
                    if (r.series.isNotEmpty()) item(key = "sr") {
                        ShelfRow("Series · ${r.series.size}") {
                            items(r.series, key = { "s" + it.key }) { h ->
                                PosterCard(h.name, h.image, onClick = { open(h) { openCopy(h.copies.first(), here) } }, width = 96.dp,
                                    subtitle = h.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "",
                                    favorite = h.copies.any { it.account.key == account.key && lib.isFavorite("series", it.id) },
                                    badges = letters(h))
                            }
                        }
                    }
                }
            }
        }
    }
    choose?.let { h ->
        val here = results?.channels.orEmpty().mapNotNull { x -> x.copies.firstOrNull { it.account.key == account.key } }
            .mapIndexed { i, c -> Channel(c.id, c.name, c.image, c.categoryId, i + 1) }
        ServerChooser(h, accounts, account, onPick = { c -> openCopy(c, here) }, onDismiss = { choose = null })
    }
    LaunchedEffect(Unit) { delay(50); runCatching { firstKey.requestFocus() } }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.1f,
        container = Jx.Surface,
        focusedContainer = Jx.Accent,
        content = Jx.TextSoft,
        focusedContent = Jx.OnAccent,
        ring = false,
    ) {
        Text(label, style = if (label.length == 1) Type.Number else Type.Small, modifier = Modifier.align(Alignment.Center))
    }
}

/** Búsquedas recientes: OK la repite, OK mantenido la borra. */
@Composable
private fun SearchHistory(items: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (items.isEmpty()) {
        StatusBox("Escribe al menos 2 letras para buscar canales, películas y series.\nTus búsquedas aparecerán aquí.")
        return
    }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 40.dp, top = 27.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BÚSQUEDAS RECIENTES", style = Type.Eyebrow, color = Jx.Muted, modifier = Modifier.weight(1f))
            Text("OK: buscar otra vez · OK mantenido: borrar", style = Type.Small, color = Jx.Faint)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items, key = { it }) { q ->
                FocusSurface(
                    onClick = { onPick(q) },
                    onLongClick = { onRemove(q) },
                    modifier = Modifier.width(420.dp).height(44.dp),
                    focusedScale = 1.02f,
                    container = Jx.Surface,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JxIcon(R.drawable.tv_ic_search, 14.dp)
                        Text(q, style = Type.Label, maxLines = 1)
                    }
                }
            }
            item(key = "__clear") {
                FocusSurface(onClick = onClear, modifier = Modifier.padding(top = 6.dp).height(40.dp), focusedScale = 1.02f) {
                    Text("Borrar historial", style = Type.Label, color = Jx.Live,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp))
                }
            }
        }
    }
}


/** Fila de filtro: Todos / cada servidor (letra + nombre), con aviso mientras carga su catálogo. */
@Composable
private fun ServerFilter(accounts: List<Account>, current: Account, only: String?, onPick: (String?) -> Unit) {
    val loading = accounts.filter { it.key != current.key && ServerHub.loadingIndex[it.key] == true }
    Column(Modifier.padding(start = 28.dp, end = 32.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Todos", only == null) { onPick(null) }
            accounts.forEach { a ->
                FilterChip(ServerHub.letter(accounts, a) + " · " + ServerHub.name(accounts, a), only == a.key) { onPick(a.key) }
            }
        }
        Text(
            if (loading.isEmpty()) "Buscando en ${accounts.size} servidores"
            else "Preparando el catálogo de " + loading.joinToString(", ") { ServerHub.letter(accounts, it) } + "… sus resultados aparecen solos",
            style = Type.Small, color = Jx.Faint,
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.05f,
        container = if (selected) Jx.Text else Jx.Surface,
        content = if (selected) Jx.Bg else Jx.TextSoft,
        focusedContainer = Jx.Accent,
        focusedContent = Jx.OnAccent,
        ring = false,
    ) {
        Text(label, style = Type.Small, maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp))
    }
}
