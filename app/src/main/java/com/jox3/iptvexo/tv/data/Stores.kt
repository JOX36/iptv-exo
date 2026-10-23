package com.jox3.iptvexo.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/** Cuentas guardadas y la cuenta activa. */
class AccountStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("tv_accounts", Context.MODE_PRIVATE)

    fun all(): List<Account> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Account(o.optString("host"), o.optString("user"), o.optString("pass"))
        }
    }

    fun current(): Account? {
        val key = prefs.getString("current", null) ?: return null
        return all().firstOrNull { it.key == key }
    }

    /** Guarda la cuenta (la más reciente primero) y la marca como activa. */
    fun use(acc: Account) {
        val list = listOf(acc) + all().filter { it.key != acc.key }
        val arr = JSONArray()
        list.take(10).forEach { a -> arr.put(JSONObject().put("host", a.host).put("user", a.user).put("pass", a.pass)) }
        prefs.edit().putString("list", arr.toString()).putString("current", acc.key).apply()
    }

    fun remove(acc: Account) {
        val arr = JSONArray()
        all().filter { it.key != acc.key }
            .forEach { a -> arr.put(JSONObject().put("host", a.host).put("user", a.user).put("pass", a.pass)) }
        val ed = prefs.edit().putString("list", arr.toString())
        if (prefs.getString("current", null) == acc.key) ed.remove("current")
        ed.apply()
    }

    /** Elimina la cuenta del equipo junto con sus favoritos, recientes e historial. */
    fun delete(acc: Account) {
        remove(acc)
        ServerHub.forget(acc)
        val lib = app.getSharedPreferences("tv_library", Context.MODE_PRIVATE)
        val ns = acc.key.hashCode().toString() + "."
        val ed = lib.edit()
        lib.all.keys.filter { it.startsWith(ns) }.forEach { ed.remove(it) }
        ed.apply()
    }

    fun logout() { prefs.edit().remove("current").apply() }
}

/**
 * Biblioteca local de la cuenta activa: favoritos, canales recientes y
 * "continuar viendo". Los cambios se reflejan al instante en la interfaz
 * (listas observables de Compose).
 */
class LibraryStore(context: Context, accountKey: String) {
    private val prefs = context.getSharedPreferences("tv_library", Context.MODE_PRIVATE)
    /** Posiciones de reproducción que guarda el reproductor (PlayerActivity). */
    private val progress = context.getSharedPreferences("vod_progress", Context.MODE_PRIVATE)
    private val ns = accountKey.hashCode().toString()

    val favorites = mutableStateListOf<SavedItem>().apply { addAll(load("fav")) }
    val recentChannels = mutableStateListOf<SavedItem>().apply { addAll(load("recent")) }
    /** Sube cada vez que cambia el progreso: obliga a recalcular "continuar viendo". */
    val progressVersion = mutableStateOf(0)

    /** Búsquedas recientes (como en TiviMate), la más nueva primero. */
    val searchHistory = mutableStateListOf<String>().apply {
        val arr = try { JSONArray(prefs.getString("$ns.search", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
    }

    fun addSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        // Mientras se escribe no se guardan los pedazos: "los" → "los simps" → "los simpson"
        // queda solo "los simpson" (se quitan las búsquedas que eran el comienzo de esta,
        // y si esta es el comienzo de una ya guardada, no se agrega).
        if (searchHistory.any { it.length > q.length && it.startsWith(q, ignoreCase = true) }) return
        searchHistory.removeAll { q.startsWith(it, ignoreCase = true) }
        searchHistory.add(0, q)
        while (searchHistory.size > 12) searchHistory.removeAt(searchHistory.size - 1)
        saveSearches()
    }

    fun removeSearch(query: String) { searchHistory.remove(query); saveSearches() }
    fun clearSearches() { searchHistory.clear(); saveSearches() }
    private fun saveSearches() {
        prefs.edit().putString("$ns.search", JSONArray(searchHistory.toList()).toString()).apply()
    }

    fun isFavorite(kind: String, id: String) = favorites.any { it.kind == kind && it.id == id }

    fun toggleFavorite(item: SavedItem): Boolean {
        val i = favorites.indexOfFirst { it.kind == item.kind && it.id == item.id }
        val added = i < 0
        if (added) favorites.add(0, item) else favorites.removeAt(i)
        save("fav", favorites)
        return added
    }

    fun setFavorite(item: SavedItem, fav: Boolean) {
        if (isFavorite(item.kind, item.id) != fav) toggleFavorite(item)
    }

    fun addRecentChannel(item: SavedItem) {
        recentChannels.removeAll { it.id == item.id }
        recentChannels.add(0, item)
        while (recentChannels.size > 20) recentChannels.removeAt(recentChannels.size - 1)
        save("recent", recentChannels)
    }

    /** Recuerda título e imagen de lo que se va a ver, para "continuar viendo". */
    fun rememberPlayback(item: SavedItem) {
        val meta = loadMeta()
        meta.put(item.id, toJson(item.copy(ts = System.currentTimeMillis())))
        // Conservar solo los 200 más recientes
        if (meta.length() > 200) {
            val oldest = meta.keys().asSequence().minByOrNull { meta.optJSONObject(it)?.optLong("ts") ?: 0L }
            if (oldest != null) meta.remove(oldest)
        }
        prefs.edit().putString("$ns.meta", meta.toString()).apply()
    }

    data class Resume(val item: SavedItem, val position: Long, val duration: Long) {
        val fraction get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val remainingMin get() = ((duration - position) / 60000).coerceAtLeast(1)
    }

    /** Películas y episodios empezados y no terminados, el más reciente primero. */
    fun continueWatching(): List<Resume> {
        val meta = loadMeta()
        return meta.keys().asSequence().mapNotNull { id ->
            val pos = progress.getLong("pos_$id", 0)
            val dur = progress.getLong("dur_$id", 0)
            if (pos <= 0 || dur <= 0 || pos.toFloat() / dur > 0.95f) return@mapNotNull null
            val o = meta.optJSONObject(id) ?: return@mapNotNull null
            Resume(fromJson(o), pos, dur)
        }.sortedByDescending { it.item.ts }.take(20).toList()
    }

    fun positionOf(id: String): Pair<Long, Long> =
        progress.getLong("pos_$id", 0) to progress.getLong("dur_$id", 0)

    fun refreshProgress() { progressVersion.value++ }

    fun clearProgress(id: String) {
        progress.edit().remove("pos_$id").remove("dur_$id").apply()
        refreshProgress()
    }

    // ── Persistencia ──
    private fun loadMeta(): JSONObject = try {
        JSONObject(prefs.getString("$ns.meta", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    private fun load(name: String): List<SavedItem> = try {
        val arr = JSONArray(prefs.getString("$ns.$name", "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    } catch (e: Exception) { emptyList() }

    private fun save(name: String, list: List<SavedItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("$ns.$name", arr.toString()).apply()
    }

    private fun toJson(i: SavedItem) = JSONObject()
        .put("kind", i.kind).put("id", i.id).put("name", i.name).put("image", i.image)
        .put("ext", i.ext).put("seriesId", i.seriesId).put("categoryId", i.categoryId).put("ts", i.ts)

    private fun fromJson(o: JSONObject) = SavedItem(
        kind = o.optString("kind"), id = o.optString("id"),
        name = Names.clean(o.optString("name"), titles = o.optString("kind") != "live"),
        image = o.optString("image"), ext = o.optString("ext"), seriesId = o.optString("seriesId"),
        categoryId = o.optString("categoryId"), ts = o.optLong("ts"),
    )
}
