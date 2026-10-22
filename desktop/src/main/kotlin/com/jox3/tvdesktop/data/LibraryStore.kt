package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Dónde se quedó una película o episodio. */
data class Resume(val item: SavedItem, val position: Long, val duration: Long, val ts: Long) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val remainingMin: Long get() = ((duration - position) / 60_000).coerceAtLeast(1)
    val finished: Boolean get() = duration > 0 && position.toFloat() / duration > 0.95f
}

/**
 * Biblioteca de la cuenta activa: favoritos, canales recientes y "continuar viendo".
 * Un archivo por cuenta en %APPDATA%\JOX3 TV\library.
 * Los cambios se ven al instante (listas observables) y se guardan en segundo plano.
 */
class LibraryStore(account: Account, dir: File = File(AppDirs.data, "library")) {
    private val file = File(dir, keyHash(account.key) + ".json")
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "library-save").apply { isDaemon = true } }

    val favorites = mutableStateListOf<SavedItem>()
    val recentChannels = mutableStateListOf<SavedItem>()
    /** Sube con cada cambio de progreso: obliga a recalcular "continuar viendo". */
    val progressVersion = mutableIntStateOf(0)
    private val progress = LinkedHashMap<String, Resume>()

    init {
        SafeFile.read(file) { JSONObject(it) }?.let { o ->
            favorites.addAll(load(o.optJSONArray("fav")))
            recentChannels.addAll(load(o.optJSONArray("recent")))
            o.optJSONArray("progress")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val item = load(org.json.JSONArray().put(p.optJSONObject("item") ?: continue)).firstOrNull() ?: continue
                    progress[item.id] = Resume(item, p.optLong("pos"), p.optLong("dur"), p.optLong("ts"))
                }
            }
        }
    }

    /** Guarda dónde va una película o episodio (lo llama el reproductor cada pocos segundos). */
    fun saveProgress(item: SavedItem, position: Long, duration: Long) {
        if (duration <= 0 || position < 0) return
        progress.remove(item.id)
        progress[item.id] = Resume(item.copy(ts = 0), position, duration, System.currentTimeMillis())
        // Solo los 300 más recientes
        while (progress.size > MAX_PROGRESS) progress.remove(progress.keys.first())
        progressVersion.intValue++
        save()
    }

    fun positionOf(id: String): Resume? = progress[id]

    fun clearProgress(id: String) {
        if (progress.remove(id) != null) { progressVersion.intValue++; save() }
    }

    /** Películas y episodios empezados y sin terminar, el más reciente primero. */
    fun continueWatching(kind: String? = null): List<Resume> =
        progress.values.filter { it.position > 5_000 && !it.finished && (kind == null || it.item.kind == kind) }
            .sortedByDescending { it.ts }.take(30)

    /** Último episodio visto de una serie (para "Continuar" en su ficha). */
    fun lastEpisodeOf(seriesId: String): Resume? =
        progress.values.filter { it.item.kind == "episode" && it.item.seriesId == seriesId }.maxByOrNull { it.ts }

    fun isFavorite(kind: String, id: String) = favorites.any { it.kind == kind && it.id == id }

    /** Agrega o quita de favoritos. Devuelve true si quedó como favorito. */
    fun toggleFavorite(item: SavedItem): Boolean {
        val i = favorites.indexOfFirst { it.kind == item.kind && it.id == item.id }
        val added = i < 0
        if (added) favorites.add(0, item.copy(ts = System.currentTimeMillis())) else favorites.removeAt(i)
        save()
        return added
    }

    fun addRecentChannel(item: SavedItem) {
        if (recentChannels.firstOrNull()?.id == item.id) return
        recentChannels.removeAll { it.id == item.id }
        recentChannels.add(0, item.copy(ts = System.currentTimeMillis()))
        while (recentChannels.size > MAX_RECENT) recentChannels.removeAt(recentChannels.size - 1)
        save()
    }

    private fun save() {
        val o = JSONObject()
            .put("version", 1)
            .put("fav", JSONArray().apply { favorites.forEach { put(toJson(it)) } })
            .put("recent", JSONArray().apply { recentChannels.forEach { put(toJson(it)) } })
            .put("progress", JSONArray().apply {
                progress.values.forEach { r ->
                    put(JSONObject().put("item", toJson(r.item)).put("pos", r.position).put("dur", r.duration).put("ts", r.ts))
                }
            })
        val text = o.toString()
        saver.execute {
            try { SafeFile.write(file, text) } catch (e: Exception) { Log.e("No se pudo guardar la biblioteca", e) }
        }
    }

    private fun load(arr: JSONArray?): List<SavedItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            SavedItem(
                kind = o.optString("kind"), id = id, name = o.optString("name"),
                image = o.optString("image"), ext = o.optString("ext"), seriesId = o.optString("seriesId"),
                categoryId = o.optString("categoryId"), ts = o.optLong("ts"),
            )
        }
    }

    private fun toJson(i: SavedItem) = JSONObject()
        .put("kind", i.kind).put("id", i.id).put("name", i.name).put("image", i.image)
        .put("ext", i.ext).put("seriesId", i.seriesId).put("categoryId", i.categoryId).put("ts", i.ts)

    companion object {
        const val MAX_RECENT = 20
        const val MAX_PROGRESS = 300

        /** Nombre de archivo estable por cuenta, sin revelar servidor ni usuario. */
        fun keyHash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
