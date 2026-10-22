package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableStateListOf
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Biblioteca de la cuenta activa: favoritos y canales recientes (la etapa 3 suma
 * "continuar viendo"). Un archivo por cuenta en %APPDATA%\JOX3 TV\library.
 * Los cambios se ven al instante (listas observables) y se guardan en segundo plano.
 */
class LibraryStore(account: Account, dir: File = File(AppDirs.data, "library")) {
    private val file = File(dir, keyHash(account.key) + ".json")
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "library-save").apply { isDaemon = true } }

    val favorites = mutableStateListOf<SavedItem>()
    val recentChannels = mutableStateListOf<SavedItem>()

    init {
        SafeFile.read(file) { JSONObject(it) }?.let { o ->
            favorites.addAll(load(o.optJSONArray("fav")))
            recentChannels.addAll(load(o.optJSONArray("recent")))
        }
    }

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

        /** Nombre de archivo estable por cuenta, sin revelar servidor ni usuario. */
        fun keyHash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
