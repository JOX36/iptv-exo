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

    /** Vuelve a pedir todo al servidor (botón "Actualizar"). */
    suspend fun clear() {
        lock.withLock { categories.clear(); channels.clear() }
        synchronized(epg) { epg.clear() }
    }

    // ── Guía (EPG) ──
    // Primero la del servidor; si no trae nada, la guía de internet por país.

    /** Sube cuando termina de descargarse la guía de internet: la interfaz vuelve a pedirla. */
    val epgVersion = mutableIntStateOf(0)
    private val catCountry = HashMap<String, String>()

    private fun startExternalEpg(liveCats: List<Category>) {
        synchronized(catCountry) { liveCats.forEach { catCountry[it.id] = EpgCountries.ofCategory(it.name) } }
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

    companion object {
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
