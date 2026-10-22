package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.jox3.tvdesktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.Normalizer
import javax.swing.SwingUtilities

/**
 * Datos de la cuenta activa con caché en memoria (igual que Android): cada categoría
 * se pide al servidor una sola vez por sesión y la guía de cada canal se guarda 5 minutos.
 */
class TvRepo(val api: XtreamApi, val library: LibraryStore) {

    private val lock = Mutex()
    private val categories = HashMap<Section, List<Category>>()
    private val channels = HashMap<String, List<Channel>>()
    private val movies = HashMap<String, List<Movie>>()
    private val series = HashMap<String, List<Series>>()
    private val movieInfos = HashMap<String, MovieInfo>()
    private val seriesInfos = HashMap<String, SeriesInfo>()
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

    suspend fun movieInfo(id: String): MovieInfo =
        lock.withLock { movieInfos[id] } ?: api.movieInfo(id).also { lock.withLock { movieInfos[id] = it } }

    /** La ficha de serie se vuelve a pedir si pasaron más de 30 min (por si salieron episodios nuevos). */
    private val seriesInfoAt = HashMap<String, Long>()
    suspend fun seriesInfo(id: String): SeriesInfo {
        lock.withLock {
            val at = seriesInfoAt[id] ?: 0
            if (System.currentTimeMillis() - at < 30 * 60_000) seriesInfos[id]?.let { return it }
        }
        return api.seriesInfo(id).also { lock.withLock { seriesInfos[id] = it; seriesInfoAt[id] = System.currentTimeMillis() } }
    }

    /** Vuelve a pedir todo al servidor (botón "Actualizar"). */
    suspend fun clear() {
        index = null
        lock.withLock {
            categories.clear(); channels.clear(); movies.clear(); series.clear()
            movieInfos.clear(); seriesInfos.clear(); seriesInfoAt.clear()
        }
        synchronized(epg) { epg.clear() }
    }

    // ── Guía (EPG) ──
    // Primero la del servidor; si no trae nada, la guía de internet por país.

    /** Sube cuando termina de descargarse la guía de internet: la interfaz vuelve a pedirla. */
    val epgVersion = mutableIntStateOf(0)
    private val catCountry = HashMap<String, String>()

    private fun startExternalEpg(liveCats: List<Category>) {
        synchronized(catCountry) { liveCats.forEach { catCountry[it.id] = EpgCountries.ofCategory(it.name) } }
        if (!com.jox3.tvdesktop.core.Settings.bool("epg.internet", true)) return // apagada en Ajustes
        ExternalEpg.ensure(EpgCountries.filesFor(liveCats)) {
            // Los canales que quedaron sin guía se vuelven a consultar
            synchronized(epg) { epg.entries.removeAll { it.value.second.isEmpty() } }
            SwingUtilities.invokeLater { epgVersion.intValue++ }
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
            list = withContext(Dispatchers.Default) {
                try {
                    ExternalEpg.match(ch.name, ch.epgId.ifBlank { null }, hint?.ifBlank { null })
                        ?.progs?.filter { it.stop > now - 3600 && it.start < now + 24 * 3600 }
                        ?.map { EpgItem(it.title, it.desc, it.start * 1000, it.stop * 1000) }
                        .orEmpty()
                } catch (e: Exception) {
                    Log.w("Falló el emparejamiento de guía para ${ch.name}", e); emptyList()
                }
            }
        }
        synchronized(epg) { epg[ch.id] = System.currentTimeMillis() to list }
        return list
    }

    // ── Búsqueda en todo el catálogo ──
    /** Catálogo completo con los nombres ya normalizados (sin tildes): buscar es solo comparar texto. */
    class Index(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>) {
        val channelKeys: List<String> = channels.map { normalize(it.name) }
        val movieKeys: List<String> = movies.map { normalize(it.name) }
        val seriesKeys: List<String> = series.map { normalize(it.name) }
    }

    @Volatile private var index: Index? = null
    private val indexLock = Mutex()

    /** Catálogo completo para buscar. Se arma una sola vez por sesión, de a una lista (menos memoria). */
    suspend fun searchIndex(): Index = index ?: indexLock.withLock {
        index ?: run {
            val ch = runCatching { api.channels() }.getOrDefault(emptyList())
            val mv = runCatching { api.movies() }.getOrDefault(emptyList())
            val sr = runCatching { api.series() }.getOrDefault(emptyList())
            withContext(Dispatchers.Default) { Index(ch, mv, sr) }.also { index = it }
        }
    }

    fun cachedIndex(): Index? = index

    companion object {
        /** Título sin año ni etiquetas ([4K], (2023)…): para reconocer copias del mismo título. */
        fun titleKey(name: String): String =
            normalize(name).replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("[^a-z0-9]+"), " ")
                .replace(Regex("\\b(4k|uhd|fhd|hd|sd|latino|castellano|subtitulado|dual|19\\d\\d|20\\d\\d)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()

        /** Programa que se emite ahora (si la guía lo trae). */
        fun nowPlaying(list: List<EpgItem>, now: Long = System.currentTimeMillis()): EpgItem? =
            list.firstOrNull { now in it.start until it.end } ?: list.firstOrNull { it.end > now }

        fun nextAfter(list: List<EpgItem>, cur: EpgItem?): EpgItem? =
            cur?.let { c -> list.firstOrNull { it.start >= c.end } }

        private val MARKS = Regex("\\p{Mn}+")

        /** Texto sin tildes y en minúsculas, para buscar "accion" y encontrar "Acción". */
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
    }
}
