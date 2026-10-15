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

    private fun startExternalEpg(liveCats: List<Category>) {
        synchronized(catCountry) { liveCats.forEach { catCountry[it.id] = EpgCountries.ofCategory(it.name) } }
        ExternalEpg.ensure(context, EpgCountries.filesFor(liveCats)) {
            // Los canales que quedaron sin guía se vuelven a consultar
            synchronized(epg) { epg.entries.removeAll { it.value.second.isEmpty() } }
            android.os.Handler(android.os.Looper.getMainLooper()).post { epgVersion.intValue++ }
        }
    }

    fun cachedEpg(ch: Channel): List<EpgItem>? {
        val hit = synchronized(epg) { epg[ch.id] } ?: return null
        return if (System.currentTimeMillis() - hit.first < 5 * 60_000) hit.second else null
    }

    suspend fun epg(ch: Channel): List<EpgItem> {
        cachedEpg(ch)?.let { return it }
        // 14 programas: alcanza para la guía por horas
        var list = epgPermits.withPermit {
            try { api.shortEpg(ch.id, 14) } catch (e: Exception) { emptyList() }
        }
        if (list.isEmpty() && ExternalEpg.isReady()) {
            val now = System.currentTimeMillis() / 1000
            val hint = synchronized(catCountry) { catCountry[ch.categoryId] }
            // El emparejamiento por nombre recorre muchas claves: fuera del hilo principal
            list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    ExternalEpg.match(context, ch.name, ch.epgId.ifBlank { null }, hint?.ifBlank { null })
                        ?.progs?.filter { it.stop > now - 3600 && it.start < now + 12 * 3600 }
                        ?.map { EpgItem(it.title, it.desc, it.start * 1000, it.stop * 1000) }
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

    suspend fun searchIndex(): Index = lock.withLock { index } ?: coroutineScope {
        val c = async { runCatching { api.channels() }.getOrDefault(emptyList()) }
        val m = async { runCatching { api.movies() }.getOrDefault(emptyList()) }
        val s = async { runCatching { api.series() }.getOrDefault(emptyList()) }
        val (ch, mv, sr) = Triple(c.await(), m.await(), s.await())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Index(ch, mv, sr) }
    }.also { built -> lock.withLock { index = built } }

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
        /** Texto sin tildes y en minúsculas, para buscar "accion" y encontrar "Acción". */
        private val MARKS = Regex("\\p{Mn}+")
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
    }
}
