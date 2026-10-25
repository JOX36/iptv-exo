package com.jox3.iptvexo.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer

/**
 * Datos de la cuenta activa con caché en memoria: cada categoría se pide al
 * servidor una sola vez por sesión, y la guía (EPG) se guarda 5 minutos.
 */
class TvRepo(private val context: android.content.Context, val api: XtreamApi, val library: LibraryStore) {

    private val lock = Mutex()
    private val categories = HashMap<Section, List<Category>>()
    private val channels = HashMap<String, List<Channel>>()
    private val movies = HashMap<String, List<Movie>>()
    private val series = HashMap<String, List<Series>>()
    private val epg = HashMap<String, Pair<Long, List<EpgItem>>>()
    /** Como mucho 3 peticiones de guía a la vez, para no saturar el servidor. */
    private val epgPermits = Semaphore(3)

    suspend fun categories(s: Section): List<Category> =
        lock.withLock { categories[s] } ?: api.categories(s).also { list ->
            lock.withLock { categories[s] = list }
            if (s == Section.LIVE) startExternalEpg(list)
        }

    suspend fun channels(catId: String): List<Channel> =
        lock.withLock { channels[catId] } ?: api.channels(catId).also { lock.withLock { channels[catId] = it } }

    suspend fun movies(catId: String): List<Movie> =
        lock.withLock { movies[catId] } ?: api.movies(catId).also { lock.withLock { movies[catId] = it } }

    suspend fun series(catId: String): List<Series> =
        lock.withLock { series[catId] } ?: api.series(catId).also { lock.withLock { series[catId] = it } }

    // ── Guía (EPG) ──
    // Primero la del servidor; si no trae nada, la guía de internet por país
    // (ExternalEpg, la misma de JOX3 Verifier).

    /** Sube cuando termina de descargarse la guía de internet: la interfaz vuelve a pedirla. */
    val epgVersion = androidx.compose.runtime.mutableIntStateOf(0)
    private val catCountry = HashMap<String, String>()

    @Volatile private var epgCodes: List<String> = emptyList()

    /** Un solo aviso por pantalla (aunque se pida muchas veces mientras descarga). */
    private val onExternalReady: () -> Unit = {
        // Los canales que quedaron sin guía se vuelven a consultar
        synchronized(epg) { epg.entries.removeAll { it.value.second.isEmpty() } }
        android.os.Handler(android.os.Looper.getMainLooper()).post { epgVersion.intValue++ }
    }

    private fun startExternalEpg(liveCats: List<Category>) {
        synchronized(catCountry) { liveCats.forEach { catCountry[it.id] = EpgCountries.ofCategory(it.raw) } }
        epgCodes = EpgCountries.filesFor(liveCats)
        ExternalEpg.lowMemory = ServerHub.lowMemory(context)
        requestExternal()
        // Guía completa del servidor (si la tiene): solo los canales que existen en él
        ServerEpg.ensure(context, api, ids = {
            kotlinx.coroutines.runBlocking { runCatching { api.channels().map { it.epgId } }.getOrDefault(emptyList()).toSet() }
        }, onReady = onExternalReady)
    }

    /** Pide (o vuelve a pedir) la guía de internet; al estar lista, la interfaz la consulta otra vez. */
    private fun requestExternal() {
        val codes = epgCodes.ifEmpty { return }
        ExternalEpg.ensure(context, codes, onExternalReady)
    }

    // ── Elegir la guía de un canal a mano ──
    class GuideChoice(val key: String, val label: String, val selected: Boolean)

    /** "Automática", las guías más parecidas (con lo que dan ahora) y "Sin guía". */
    suspend fun guideChoices(ch: Channel): List<GuideChoice> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val manual = ExternalEpg.manualFor(context, ch.name)
        val now = System.currentTimeMillis() / 1000
        buildList {
            add(GuideChoice("", "Automática (la que elige la app)", manual.isEmpty()))
            ExternalEpg.candidates(ch.name).forEach { c ->
                val cur = c.progs.firstOrNull { now in it.start until it.stop }
                add(GuideChoice(c.key, ExternalEpg.displayName(c) + " · " + ExternalEpg.sourceName(c) +
                    (cur?.let { " — Ahora: ${it.title}" } ?: ""), manual == c.key))
            }
            add(GuideChoice("none", "Este canal no tiene guía", manual == "none"))
        }
    }

    /** Guarda la elección y vuelve a cargar la guía del canal. */
    fun chooseGuide(ch: Channel, key: String) {
        ExternalEpg.setManual(context, ch.name, key)
        synchronized(epg) { epg.remove(ch.id) }
        android.os.Handler(android.os.Looper.getMainLooper()).post { epgVersion.intValue++ }
    }

    fun cachedEpg(ch: Channel): List<EpgItem>? {
        val hit = synchronized(epg) { epg[ch.id] } ?: return null
        // Con guía: 5 minutos. Sin guía: solo 1 minuto (puede que aún se esté descargando)
        val ttl = if (hit.second.isEmpty()) 60_000 else 5 * 60_000
        return if (System.currentTimeMillis() - hit.first < ttl) hit.second else null
    }

    suspend fun epg(ch: Channel): List<EpgItem> {
        cachedEpg(ch)?.let { return it }
        val nowSec = System.currentTimeMillis() / 1000
        // 0. Elegida a mano: esa manda (aunque el servidor traiga otra)
        val manual = ExternalEpg.manualFor(context, ch.name)
        if (manual == "none") { synchronized(epg) { epg[ch.id] = System.currentTimeMillis() to emptyList() }; return emptyList() }
        if (manual.isNotEmpty() && ExternalEpg.isReady()) {
            val picked = ExternalEpg.match(context, ch.name, null)?.progs?.filter { it.stop > nowSec - 3600 && it.start < nowSec + 12 * 3600 }
                ?.map { it.toItem() }.orEmpty()
            if (picked.isNotEmpty()) { synchronized(epg) { epg[ch.id] = System.currentTimeMillis() to picked }; return picked }
        }
        // 1. Guía completa del servidor (xmltv.php), si la tiene para este canal
        val full = ServerEpg.lookup(api.account, ch.epgId)?.filter { it.stop > nowSec - 3600 }?.map { it.toItem() }.orEmpty()
        // 2. Guía corta del servidor (14 programas: alcanza para la guía por horas)
        var list = full.ifEmpty {
            epgPermits.withPermit {
                try { api.shortEpg(ch.id, 14) } catch (e: Exception) { emptyList() }
            }
        }
        // La guía de internet aún no está (falló o no ha terminado): se vuelve a pedir
        if (list.isEmpty() && !ExternalEpg.isReady() && !ExternalEpg.loading) requestExternal()
        if (list.isEmpty() && ExternalEpg.isReady()) {
            val now = System.currentTimeMillis() / 1000
            val hint = synchronized(catCountry) { catCountry[ch.categoryId] }
            // El emparejamiento por nombre recorre muchas claves: fuera del hilo principal
            list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    ExternalEpg.match(context, ch.name, ch.epgId.ifBlank { null }, hint?.ifBlank { null })
                        ?.progs?.filter { it.stop > now - 3600 && it.start < now + 12 * 3600 }
                        ?.map { it.toItem() }
                        .orEmpty()
                } catch (e: Exception) { emptyList() }
            }
        }
        synchronized(epg) { epg[ch.id] = System.currentTimeMillis() to list }
        return list
    }

    /** Programa que se emite ahora (si la guía lo trae). */
    fun nowPlaying(list: List<EpgItem>): EpgItem? {
        val now = System.currentTimeMillis()
        return list.firstOrNull { now in it.start until it.end } ?: list.firstOrNull { it.end > now }
    }

    fun nextAfter(list: List<EpgItem>, cur: EpgItem?): EpgItem? =
        cur?.let { c -> list.firstOrNull { it.start >= c.end } }

    // ── Búsqueda en todo el catálogo ──
    /** Catálogo completo con los nombres ya normalizados (sin tildes): buscar es solo comparar texto. */
    class Index(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>) {
        val channelKeys: List<String> = channels.map { normalize(it.name) }
        val movieKeys: List<String> = movies.map { normalize(it.name) }
        val seriesKeys: List<String> = series.map { normalize(it.name) }
    }

    private var index: Index? = null

    private val indexLock = kotlinx.coroutines.sync.Mutex()

    /** Catálogo completo para buscar. Se arma una sola vez y de a una lista (menos memoria). */
    suspend fun searchIndex(): Index = lock.withLock { index } ?: indexLock.withLock {
        lock.withLock { index } ?: run {
            val ch = runCatching { api.channels() }.getOrDefault(emptyList())
            val mv = runCatching { api.movies() }.getOrDefault(emptyList())
            val sr = runCatching { api.series() }.getOrDefault(emptyList())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Index(ch, mv, sr) }
                .also { built -> lock.withLock { index = built } }
        }
    }

    /**
     * Recomendaciones para una película:
     * 1. Primero, las de la MISMA SAGA (p. ej. todas las de Harry Potter), buscadas
     *    en todo el catálogo por el comienzo del título.
     * 2. Luego, parecidas por GÉNERO: solo de las categorías cuyo nombre coincide con
     *    los géneros de la película ("Acción", "Comedia"…; también en inglés). Si el
     *    servidor no tiene categorías por género, de la misma carpeta.
     */
    suspend fun similarMovies(movie: Movie, genre: String, limit: Int = 21): List<Movie> {
        val out = LinkedHashMap<String, Movie>()
        // 1. Saga
        val base = sagaKey(movie.name)
        if (base.length >= 5) {
            val idx = runCatching { searchIndex() }.getOrNull()
            if (idx != null) {
                val saga = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    idx.movies.indices.filter { idx.movieKeys[it].startsWith(base) }.map { idx.movies[it] }
                }
                bestCopies(saga).filter { it.id != movie.id && titleKey(it.name) != titleKey(movie.name) }
                    .sortedBy { normalize(it.name) }.take(12).forEach { out.putIfAbsent(it.id, it) }
            }
        }
        // 2. Género
        val words = genre.split(',', '/', '|', '&').map { normalize(it.trim()) }.filter { it.length >= 3 }
            .map { GENRES[it] ?: it }.distinct()
        val cats = runCatching { categories(Section.VOD) }.getOrDefault(emptyList())
        val matching = if (words.isEmpty()) emptyList() else cats.filter { c ->
            words.any { w -> normalize(c.name).contains(w.take(6)) }
        }.take(4)
        val pool = LinkedHashMap<String, Movie>()
        for (c in matching) {
            runCatching { movies(c.id) }.getOrNull()?.sortedByDescending { it.added }?.take(40)?.forEach { pool.putIfAbsent(it.id, it) }
        }
        if (matching.isEmpty()) runCatching { movies(movie.categoryId) }.getOrNull()?.forEach { pool.putIfAbsent(it.id, it) }
        val seen = out.values.map { titleKey(it.name) }.toMutableSet().apply { add(titleKey(movie.name)) }
        bestCopies(pool.values.toList()).shuffled(java.util.Random(movie.id.hashCode().toLong()))
            .forEach { if (seen.add(titleKey(it.name))) out.putIfAbsent(it.id, it) }
        out.remove(movie.id)
        return out.values.take(limit)
    }

    /**
     * "Porque viste…": títulos para quien terminó [source] (película o serie).
     * 1. Lo que TMDB recomienda a quien vio ese título, SOLO si existe en este servidor.
     * 2. Se completa con la misma saga y el mismo género (como "Películas similares").
     */
    suspend fun becauseYouWatched(ctx: android.content.Context, source: SavedItem, limit: Int = 14): List<SavedItem> {
        val isSeries = source.kind == "series"
        val out = LinkedHashMap<String, SavedItem>()
        val srcKey = titleKey(source.name)
        // 1. TMDB
        val titles = runCatching { Tmdb.related(ctx, if (isSeries) "tv" else "movie", source.name) }.getOrDefault(emptyList())
        if (titles.isNotEmpty() && !ServerHub.lowMemory(ctx)) {
            val idx = runCatching { searchIndex() }.getOrNull()
            if (idx != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                if (isSeries) {
                    val byKey = HashMap<String, Series>()
                    idx.series.forEach { sr -> byKey.putIfAbsent(titleKey(sr.name), sr)?.let { old -> if (!old.poster.startsWith("http") && sr.poster.startsWith("http")) byKey[titleKey(sr.name)] = sr } }
                    titles.forEach { t ->
                        val k = titleKey(t); if (k.isEmpty() || k == srcKey) return@forEach
                        byKey[k]?.let { sr -> out.putIfAbsent(k, SavedItem("series", sr.id, sr.name, sr.poster, categoryId = sr.categoryId)) }
                    }
                } else {
                    val byKey = bestCopies(idx.movies).associateBy { titleKey(it.name) }
                    titles.forEach { t ->
                        val k = titleKey(t); if (k.isEmpty() || k == srcKey) return@forEach
                        byKey[k]?.let { m -> out.putIfAbsent(k, SavedItem("movie", m.id, m.name, m.poster, ext = m.ext, categoryId = m.categoryId)) }
                    }
                }
            }
        }
        // 2. Misma saga / mismo género
        if (out.size < limit) {
            if (isSeries) {
                val genre = runCatching { api.seriesInfo(source.id).genre }.getOrDefault("")
                similarSeries(source, genre, limit * 2).forEach { sr ->
                    val k = titleKey(sr.name)
                    if (k != srcKey) out.putIfAbsent(k, SavedItem("series", sr.id, sr.name, sr.poster, categoryId = sr.categoryId))
                }
            } else {
                val genre = runCatching { api.movieInfo(source.id).genre }.getOrDefault("")
                val movie = Movie(source.id, source.name, source.image, "", source.ext, source.categoryId, 0L)
                similarMovies(movie, genre, limit * 2).forEach { m ->
                    val k = titleKey(m.name)
                    if (k != srcKey) out.putIfAbsent(k, SavedItem("movie", m.id, m.name, m.poster, ext = m.ext, categoryId = m.categoryId))
                }
            }
        }
        return out.values.filter { it.id != source.id }.take(limit)
    }

    /** Series del mismo género (por el nombre de la categoría); si no hay, de la misma carpeta. */
    private suspend fun similarSeries(source: SavedItem, genre: String, limit: Int): List<Series> {
        val words = genre.split(',', '/', '|', '&').map { normalize(it.trim()) }.filter { it.length >= 3 }
            .map { GENRES[it] ?: it }.distinct()
        val cats = runCatching { categories(Section.SERIES) }.getOrDefault(emptyList())
        val matching = if (words.isEmpty()) emptyList() else cats.filter { c -> words.any { w -> normalize(c.name).contains(w.take(6)) } }.take(4)
        val pool = LinkedHashMap<String, Series>()
        for (c in matching) runCatching { series(c.id) }.getOrNull()?.sortedByDescending { it.updated }?.take(40)?.forEach { pool.putIfAbsent(it.id, it) }
        if (pool.isEmpty() && source.categoryId.isNotBlank()) runCatching { series(source.categoryId) }.getOrNull()?.forEach { pool.putIfAbsent(it.id, it) }
        return pool.values.filter { it.id != source.id }.shuffled(java.util.Random(source.id.hashCode().toLong())).take(limit)
    }

    /**
     * Muchos servidores repiten la misma película en varias carpetas y algunas copias
     * vienen sin carátula. De cada título se queda la copia que sí trae imagen.
     */
    private fun bestCopies(list: List<Movie>): List<Movie> =
        list.groupBy { titleKey(it.name) }.values.map { copies ->
            copies.firstOrNull { it.poster.startsWith("http") } ?: copies.first()
        }

    /** Comienzo del título que identifica la saga: "harry potter", "rapidos y furiosos", "matchbox". */
    private fun sagaKey(name: String): String {
        var n = normalize(name).replace(Regex("\\(\\d{4}\\)|\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
        val colon = n.indexOf(':')
        n = if (colon >= 4) n.substring(0, colon) else {
            val words = n.split(' ')
            val keep = ArrayList<String>()
            var significant = 0
            for (w in words) {
                keep += w
                if (w !in STOP) significant++
                if (significant == 2) break
            }
            keep.joinToString(" ")
        }
        // Sin números ni romanos al final ("2", "III", "parte 1")
        return n.replace(Regex("(\\s+(parte|part|capitulo|chapter))?\\s+(\\d+|i{1,3}|iv|v|vi{1,3})$"), "").trim()
    }

    /** Películas recientes de la cuenta (para el inicio). */
    suspend fun latestMovies(limit: Int = 24): List<Movie> {
        val cats = categories(Section.VOD)
        val first = cats.firstOrNull() ?: return emptyList()
        return movies(first.id).sortedByDescending { it.added }.take(limit)
    }

    suspend fun latestSeries(limit: Int = 24): List<Series> {
        val cats = categories(Section.SERIES)
        val first = cats.firstOrNull() ?: return emptyList()
        return series(first.id).sortedByDescending { it.updated }.take(limit)
    }

    companion object {
        /** Título sin año ni etiquetas ([4K], (2023)…): para reconocer copias de la misma película. */
        fun titleKey(name: String): String =
            normalize(name).replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("[^a-z0-9]+"), " ")
                .replace(Regex("\\b(4k|uhd|fhd|hd|sd|latino|castellano|subtitulado|dual|19\\d\\d|20\\d\\d)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()

        /** Texto sin tildes y en minúsculas, para buscar "accion" y encontrar "Acción". */
        private val MARKS = Regex("\\p{Mn}+")
        private val STOP = setOf("el", "la", "los", "las", "the", "a", "an", "y", "e", "de", "del", "and", "of", "un", "una")
        /** Géneros en inglés → como suelen llamarse las categorías en español. */
        private val GENRES = mapOf(
            "action" to "accion", "comedy" to "comedia", "adventure" to "aventura", "horror" to "terror",
            "thriller" to "suspenso", "animation" to "animacion", "family" to "familia", "romance" to "romant",
            "science fiction" to "ciencia", "sci-fi" to "ciencia", "ciencia ficcion" to "ciencia", "fantasy" to "fantasia",
            "crime" to "crimen", "documentary" to "documental", "war" to "guerra", "mystery" to "misterio",
            "history" to "historia", "music" to "musica", "kids" to "infantil", "romantica" to "romant",
        )
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
    }
}
