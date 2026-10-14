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
class TvRepo(val api: XtreamApi, val library: LibraryStore) {

    private val lock = Mutex()
    private val categories = HashMap<Section, List<Category>>()
    private val channels = HashMap<String, List<Channel>>()
    private val movies = HashMap<String, List<Movie>>()
    private val series = HashMap<String, List<Series>>()
    private val epg = HashMap<String, Pair<Long, List<EpgItem>>>()
    /** Como mucho 3 peticiones de guía a la vez, para no saturar el servidor. */
    private val epgPermits = Semaphore(3)

    suspend fun categories(s: Section): List<Category> =
        lock.withLock { categories[s] } ?: api.categories(s).also { lock.withLock { categories[s] = it } }

    suspend fun channels(catId: String): List<Channel> =
        lock.withLock { channels[catId] } ?: api.channels(catId).also { lock.withLock { channels[catId] = it } }

    suspend fun movies(catId: String): List<Movie> =
        lock.withLock { movies[catId] } ?: api.movies(catId).also { lock.withLock { movies[catId] = it } }

    suspend fun series(catId: String): List<Series> =
        lock.withLock { series[catId] } ?: api.series(catId).also { lock.withLock { series[catId] = it } }

    fun cachedEpg(streamId: String): List<EpgItem>? {
        val hit = synchronized(epg) { epg[streamId] } ?: return null
        return if (System.currentTimeMillis() - hit.first < 5 * 60_000) hit.second else null
    }

    suspend fun epg(streamId: String): List<EpgItem> {
        cachedEpg(streamId)?.let { return it }
        val list = epgPermits.withPermit {
            try { api.shortEpg(streamId) } catch (e: Exception) { emptyList() }
        }
        synchronized(epg) { epg[streamId] = System.currentTimeMillis() to list }
        return list
    }

    /** Programa que se emite ahora (si la guía lo trae). */
    fun nowPlaying(list: List<EpgItem>): EpgItem? {
        val now = System.currentTimeMillis()
        return list.firstOrNull { now in it.start until it.end } ?: list.firstOrNull()
    }

    // ── Búsqueda en todo el catálogo ──
    data class Index(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>)

    private var index: Index? = null

    suspend fun searchIndex(): Index = lock.withLock { index } ?: coroutineScope {
        val c = async { runCatching { api.channels() }.getOrDefault(emptyList()) }
        val m = async { runCatching { api.movies() }.getOrDefault(emptyList()) }
        val s = async { runCatching { api.series() }.getOrDefault(emptyList()) }
        Index(c.await(), m.await(), s.await())
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
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    }
}
