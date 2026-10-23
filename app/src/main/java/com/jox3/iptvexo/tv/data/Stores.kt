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
        val sync = app.getSharedPreferences("tv_sync", Context.MODE_PRIVATE)
        val es = sync.edit()
        sync.all.keys.filter { it.startsWith(ns) }.forEach { es.remove(it) }
        es.apply()
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
        val now = System.currentTimeMillis()
        if (added) favorites.add(0, item.copy(ts = now)) else favorites.removeAt(i)
        save("fav", favorites)
        markChange("fav", item.kind, item.id, if (added) toJson(item.copy(ts = now)) else null, now)
        return added
    }

    fun setFavorite(item: SavedItem, fav: Boolean) {
        if (isFavorite(item.kind, item.id) != fav) toggleFavorite(item)
    }

    fun addRecentChannel(item: SavedItem) {
        val now = System.currentTimeMillis()
        val it2 = item.copy(ts = now)
        recentChannels.removeAll { it.id == item.id }
        recentChannels.add(0, it2)
        while (recentChannels.size > 20) recentChannels.removeAt(recentChannels.size - 1)
        save("recent", recentChannels)
        markChange("recent", item.kind, item.id, toJson(it2), now)
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

    fun refreshProgress() { progressVersion.value++; onLocalChange() }

    /** Lo pone CloudSync: avisa que hay algo nuevo para subir pronto. */
    @Volatile internal var onLocalChange: () -> Unit = {}

    fun clearProgress(id: String) {
        progress.edit().remove("pos_$id").remove("dur_$id").apply()
        refreshProgress()
    }

    // ── Sincronización en la nube (ver CloudSync) ──
    // Los cambios que aún no llegan al servidor se guardan en "pendientes"; lo que llega del
    // servidor se aplica solo si es más nuevo que lo del equipo. Todo se llama en el hilo principal.
    private val sync = context.getSharedPreferences("tv_sync", Context.MODE_PRIVATE)

    private fun obj(key: String): JSONObject =
        try { JSONObject(sync.getString("$ns.$key", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun putObj(key: String, o: JSONObject) { sync.edit().putString("$ns.$key", o.toString()).apply() }

    private fun markChange(list: String, kind: String, id: String, data: JSONObject?, ts: Long) {
        val p = obj("pending")
        p.put("$list|$kind:$id", JSONObject().put("list", list).put("item", "$kind:$id")
            .put("ts", ts).put("deleted", data == null).apply { if (data != null) put("data", data.toString()) })
        putObj("pending", p)
        onLocalChange()
    }

    internal var syncCursor: Long
        get() = sync.getLong("$ns.cursor", 0L)
        set(v) { sync.edit().putLong("$ns.cursor", v).apply() }
    internal val syncStarted: Boolean get() = sync.getBoolean("$ns.started", false)

    /** Primera vez: sube lo que el equipo ya tenía (después de haber bajado lo de la nube). */
    internal fun markInitialUpload() {
        val base = 1L
        favorites.forEach { markChange("fav", it.kind, it.id, toJson(it), it.ts.takeIf { t -> t > 0 } ?: base) }
        recentChannels.forEach { markChange("recent", it.kind, it.id, toJson(it), it.ts.takeIf { t -> t > 0 } ?: base) }
        val meta = loadMeta(); val sent = obj("sent")
        meta.keys().forEach { id ->
            val pos = progress.getLong("pos_$id", 0); val dur = progress.getLong("dur_$id", 0)
            if (pos <= 0 || dur <= 0 || sent.optLong(id, -1) == pos) return@forEach
            val o = meta.optJSONObject(id) ?: return@forEach
            markChange("resume", o.optString("kind"), id, JSONObject(o.toString()).put("pos", pos).put("dur", dur),
                o.optLong("ts").takeIf { it > 0 } ?: base)
            sent.put(id, pos)
        }
        putObj("sent", sent)
        sync.edit().putBoolean("$ns.started", true).apply()
    }

    /** Revisa las posiciones guardadas por el reproductor y marca las que cambiaron. */
    internal fun collectProgress() {
        val meta = loadMeta(); val sent = obj("sent"); val now = System.currentTimeMillis()
        var changed = false
        meta.keys().forEach { id ->
            val o = meta.optJSONObject(id) ?: return@forEach
            val pos = progress.getLong("pos_$id", 0); val dur = progress.getLong("dur_$id", 0)
            val last = sent.optLong(id, -1)
            if (pos > 0 && dur > 0) {
                if (last < 0 || kotlin.math.abs(pos - last) >= 5_000) {
                    markChange("resume", o.optString("kind"), id, JSONObject(o.toString()).put("pos", pos).put("dur", dur), now)
                    sent.put(id, pos); changed = true
                }
            } else if (last >= 0) {
                // Terminada o reiniciada: sale de "Seguir viendo" en todos los equipos
                markChange("resume", o.optString("kind"), id, null, now)
                sent.remove(id); changed = true
            }
        }
        if (changed) putObj("sent", sent)
    }

    internal fun pendingChanges(): List<JSONObject> {
        val p = obj("pending")
        return p.keys().asSequence().mapNotNull { p.optJSONObject(it) }.toList()
    }

    /** Quita de pendientes lo que ya se envió (si no volvió a cambiar mientras tanto). */
    internal fun clearSent(sentList: List<JSONObject>) {
        val p = obj("pending")
        sentList.forEach { c ->
            val k = c.optString("list") + "|" + c.optString("item")
            if (p.optJSONObject(k)?.optLong("ts") == c.optLong("ts")) p.remove(k)
        }
        putObj("pending", p)
    }

    /** Aplica lo que llegó de otros equipos. */
    internal fun applyRemote(items: List<JSONObject>) {
        if (items.isEmpty()) return
        val pending = obj("pending")
        val meta = loadMeta(); val sent = obj("sent")
        var fav = false; var rec = false; var res = false
        items.forEach { r ->
            val list = r.optString("list"); val key = r.optString("item"); val ts = r.optLong("ts")
            val mine = pending.optJSONObject("$list|$key")
            if (mine != null && mine.optLong("ts") >= ts) return@forEach   // lo del equipo es más nuevo
            val kind = key.substringBefore(':'); val id = key.substringAfter(':')
            val deleted = r.optBoolean("deleted")
            val data = if (deleted) null else runCatching { JSONObject(r.optString("data")) }.getOrNull()
            if (!deleted && data == null) return@forEach
            when (list) {
                "fav" -> {
                    val i = favorites.indexOfFirst { it.kind == kind && it.id == id }
                    if (deleted) { if (i >= 0) { favorites.removeAt(i); fav = true } }
                    else if (i < 0) { favorites.add(0, fromJson(data!!)); fav = true }
                }
                "recent" -> if (!deleted) {
                    val item = fromJson(data!!)
                    val old = recentChannels.firstOrNull { it.id == id }
                    if (old == null || old.ts < item.ts) {
                        recentChannels.removeAll { it.id == id }; recentChannels.add(item); rec = true
                    }
                }
                "resume" -> {
                    val localTs = meta.optJSONObject(id)?.optLong("ts") ?: 0L
                    if (deleted) {
                        if (progress.contains("pos_$id")) {
                            progress.edit().remove("pos_$id").remove("dur_$id").apply(); res = true
                        }
                        sent.remove(id)
                    } else if (ts >= localTs || progress.getLong("pos_$id", 0) <= 0) {
                        val pos = data!!.optLong("pos"); val dur = data.optLong("dur")
                        if (pos > 0 && dur > 0) {
                            data.remove("pos"); data.remove("dur"); data.put("ts", ts)
                            meta.put(id, data)
                            progress.edit().putLong("pos_$id", pos).putLong("dur_$id", dur).apply()
                            sent.put(id, pos); res = true
                        }
                    }
                }
            }
        }
        if (fav) save("fav", favorites)
        if (rec) {
            val sorted = recentChannels.sortedByDescending { it.ts }.take(20)
            recentChannels.clear(); recentChannels.addAll(sorted)
            save("recent", recentChannels)
        }
        if (res) {
            prefs.edit().putString("$ns.meta", meta.toString()).apply()
            progressVersion.value++
        }
        putObj("sent", sent)
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
        name = o.optString("name").let { n -> if (o.optString("kind") == "live") Names.channel(n) else Names.clean(n, titles = true) },
        image = o.optString("image"), ext = o.optString("ext"), seriesId = o.optString("seriesId"),
        categoryId = o.optString("categoryId"), ts = o.optLong("ts"),
    )
}
