package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
 * Biblioteca de una cuenta y un perfil: favoritos, canales recientes, "continuar viendo"
 * y búsquedas. Un archivo por (cuenta, perfil) en %APPDATA%\JOX3 TV\library.
 * Los cambios se ven al instante (listas observables) y se guardan en segundo plano.
 *
 * Nube (ver [CloudSync]): mismo formato que la app Android, así el historial se comparte con
 * el TV Box y el celular. Los cambios que aún no llegan al servidor quedan en "pendientes";
 * lo que llega de otros equipos solo se aplica si es más nuevo. Todo se llama en el hilo de la ventana.
 */
class LibraryStore(
    account: Account,
    val profileId: String = PROFILE_MAIN,
    dir: File = File(AppDirs.data, "library"),
) {
    private val file = File(dir, fileName(account.key, profileId))
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "library-save").apply { isDaemon = true } }

    val favorites = mutableStateListOf<SavedItem>()
    val recentChannels = mutableStateListOf<SavedItem>()
    /** Sube con cada cambio de progreso: obliga a recalcular "continuar viendo". */
    val progressVersion = mutableIntStateOf(0)
    private val progress = LinkedHashMap<String, Resume>()
    /** Búsquedas recientes, la más nueva primero (como en Android). */
    val searchHistory = mutableStateListOf<String>()

    // ── Estado de la sincronización ──
    /** "lista|tipo:id" → cambio pendiente de subir ({list, item, ts, deleted, data}). */
    private val pending = LinkedHashMap<String, JSONObject>()
    /** id → última posición enviada (para no subir cada segundo de la película). */
    private val sent = HashMap<String, Long>()
    internal var syncCursor = 0L
        private set
    internal var syncStarted = false
        private set

    /** Lo pone CloudSync: avisa que hay algo nuevo para subir pronto. */
    @Volatile internal var onLocalChange: () -> Unit = {}

    /** Organización de categorías por sección (LIVE, VOD, SERIES): ocultas, orden y nombres propios. */
    private val cats = HashMap<String, CatPrefs>()
    /** Sube cuando cambia la organización: las pantallas vuelven a pedir las categorías. */
    val catVersion = mutableIntStateOf(0)

    /** "Porque viste…": título que se terminó y lo recomendado (para la fila en Películas y Series). */
    val because = mutableStateOf<Pair<String, List<SavedItem>>?>(null)

    init {
        SafeFile.read(file) { JSONObject(it) }?.let { o ->
            favorites.addAll(load(o.optJSONArray("fav")))
            recentChannels.addAll(load(o.optJSONArray("recent")))
            o.optJSONArray("search")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { searchHistory.add(it) } }
            o.optJSONArray("progress")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val item = load(JSONArray().put(p.optJSONObject("item") ?: continue)).firstOrNull() ?: continue
                    progress[item.id] = Resume(item, p.optLong("pos"), p.optLong("dur"), p.optLong("ts"))
                }
            }
            o.optJSONObject("sync")?.let { s ->
                syncStarted = s.optBoolean("started")
                syncCursor = s.optLong("cursor")
                s.optJSONObject("pending")?.let { p -> p.keys().forEach { k -> p.optJSONObject(k)?.let { pending[k] = it } } }
                s.optJSONObject("sent")?.let { p -> p.keys().forEach { k -> sent[k] = p.optLong(k) } }
            }
            o.optJSONObject("cats")?.let { c ->
                c.keys().forEach { k -> c.optJSONObject(k)?.let { cats[k] = catFromJson(it) } }
            }
            o.optJSONObject("because")?.let { b ->
                val items = load(b.optJSONArray("items"))
                if (items.isNotEmpty()) because.value = b.optString("title") to items
            }
        }
    }

    // ═══ Continuar viendo ═══

    /** Guarda dónde va una película o episodio (lo llama el reproductor cada pocos segundos). */
    fun saveProgress(item: SavedItem, position: Long, duration: Long) {
        if (duration <= 0 || position < 0) return
        val now = System.currentTimeMillis()
        progress.remove(item.id)
        progress[item.id] = Resume(item.copy(ts = 0), position, duration, now)
        // Solo los 300 más recientes
        while (progress.size > MAX_PROGRESS) progress.remove(progress.keys.first())
        // A la nube solo si avanzó 5 s o más desde lo último enviado (igual que Android)
        val last = sent[item.id]
        if (last == null || kotlin.math.abs(position - last) >= 5_000) {
            markChange("resume", item.kind, item.id, resumeJson(item, position, duration, now), now)
            sent[item.id] = position
        }
        progressVersion.intValue++
        save()
    }

    fun positionOf(id: String): Resume? = progress[id]

    fun clearProgress(id: String) {
        val r = progress.remove(id) ?: return
        // Terminada o reiniciada: sale de "Seguir viendo" en todos los equipos
        markChange("resume", r.item.kind, id, null, System.currentTimeMillis())
        sent.remove(id)
        progressVersion.intValue++
        save()
    }

    /** Películas y episodios empezados y sin terminar, el más reciente primero. */
    fun continueWatching(kind: String? = null): List<Resume> =
        progress.values.filter { it.position > 5_000 && !it.finished && (kind == null || it.item.kind == kind) }
            .sortedByDescending { it.ts }.take(30)

    /** Último episodio visto de una serie (para "Continuar" en su ficha). */
    fun lastEpisodeOf(seriesId: String): Resume? =
        progress.values.filter { it.item.kind == "episode" && it.item.seriesId == seriesId }.maxByOrNull { it.ts }

    // ═══ Búsquedas ═══

    /**
     * Guarda una búsqueda. Mientras se escribe no se guardan los pedazos:
     * "los" → "los simps" → "los simpson" deja solo "los simpson".
     */
    fun addSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        if (searchHistory.any { it.length > q.length && it.startsWith(q, ignoreCase = true) }) return
        searchHistory.removeAll { q.startsWith(it, ignoreCase = true) }
        searchHistory.add(0, q)
        while (searchHistory.size > 12) searchHistory.removeAt(searchHistory.size - 1)
        save()
    }

    fun removeSearch(query: String) { searchHistory.remove(query); save() }
    fun clearSearches() { searchHistory.clear(); save() }

    // ═══ Favoritos y recientes ═══

    fun isFavorite(kind: String, id: String) = favorites.any { it.kind == kind && it.id == id }

    /** Agrega o quita de favoritos. Devuelve true si quedó como favorito. */
    fun toggleFavorite(item: SavedItem): Boolean {
        val i = favorites.indexOfFirst { it.kind == item.kind && it.id == item.id }
        val added = i < 0
        val now = System.currentTimeMillis()
        if (added) favorites.add(0, item.copy(ts = now)) else favorites.removeAt(i)
        markChange("fav", item.kind, item.id, if (added) toJson(item.copy(ts = now)) else null, now)
        save()
        return added
    }

    fun addRecentChannel(item: SavedItem) {
        if (recentChannels.firstOrNull()?.id == item.id) return
        val now = System.currentTimeMillis()
        val it2 = item.copy(ts = now)
        recentChannels.removeAll { it.id == item.id }
        recentChannels.add(0, it2)
        while (recentChannels.size > MAX_RECENT) recentChannels.removeAt(recentChannels.size - 1)
        markChange("recent", item.kind, item.id, toJson(it2), now)
        save()
    }

    // ═══ Nube ═══

    private fun resumeJson(item: SavedItem, pos: Long, dur: Long, ts: Long) =
        toJson(item.copy(ts = ts)).put("pos", pos).put("dur", dur)

    private fun markChange(list: String, kind: String, id: String, data: JSONObject?, ts: Long) {
        pending["$list|$kind:$id"] = JSONObject().put("list", list).put("item", "$kind:$id")
            .put("ts", ts).put("deleted", data == null).apply { if (data != null) put("data", data.toString()) }
        onLocalChange()
    }

    /** Primera vez en este equipo: sube lo que ya tenía (después de haber bajado lo de la nube). */
    internal fun markInitialUpload() {
        val base = 1L
        favorites.forEach { markChange("fav", it.kind, it.id, toJson(it), it.ts.takeIf { t -> t > 0 } ?: base) }
        recentChannels.forEach { markChange("recent", it.kind, it.id, toJson(it), it.ts.takeIf { t -> t > 0 } ?: base) }
        progress.values.forEach { r ->
            if (r.position <= 0 || r.duration <= 0 || sent[r.item.id] == r.position) return@forEach
            val ts = r.ts.takeIf { it > 0 } ?: base
            markChange("resume", r.item.kind, r.item.id, resumeJson(r.item, r.position, r.duration, ts), ts)
            sent[r.item.id] = r.position
        }
        syncStarted = true
        save()
    }

    /** Hasta dónde se bajó de la nube (hora del servidor). */
    internal fun updateCursor(v: Long) { syncCursor = v; save() }

    internal fun pendingChanges(): List<JSONObject> = pending.values.map { JSONObject(it.toString()) }

    /** Quita de pendientes lo que ya se envió (si no volvió a cambiar mientras tanto). */
    internal fun clearSent(sentList: List<JSONObject>) {
        sentList.forEach { c ->
            val k = c.optString("list") + "|" + c.optString("item")
            if (pending[k]?.optLong("ts") == c.optLong("ts")) pending.remove(k)
        }
        save()
    }

    /** Aplica lo que llegó de otros equipos (mismas reglas que Android: gana el más nuevo). */
    internal fun applyRemote(items: List<JSONObject>) {
        if (items.isEmpty()) return
        var fav = false; var rec = false; var res = false
        items.forEach { r ->
            val list = r.optString("list"); val key = r.optString("item"); val ts = r.optLong("ts")
            val mine = pending["$list|$key"]
            if (mine != null && mine.optLong("ts") >= ts) return@forEach   // lo del equipo es más nuevo
            val kind = key.substringBefore(':'); val id = key.substringAfter(':')
            val deleted = r.optBoolean("deleted")
            val data = if (deleted) null else runCatching { JSONObject(r.optString("data")) }.getOrNull()
            if (!deleted && data == null) return@forEach
            // El id puede faltar en "data": se toma del item ("tipo:id"), igual que lo guarda Android
            val remote = data?.let { fromJson(if (it.optString("id").isBlank()) JSONObject(it.toString()).put("id", id).put("kind", kind) else it) }
            if (!deleted && remote == null) return@forEach
            when (list) {
                "fav" -> {
                    val i = favorites.indexOfFirst { it.kind == kind && it.id == id }
                    if (deleted) { if (i >= 0) { favorites.removeAt(i); fav = true } }
                    else if (i < 0) { favorites.add(0, remote!!); fav = true }
                }
                "recent" -> if (!deleted) {
                    val item = remote!!
                    val old = recentChannels.firstOrNull { it.id == id }
                    if (old == null || old.ts < item.ts) {
                        recentChannels.removeAll { it.id == id }; recentChannels.add(item); rec = true
                    }
                }
                "resume" -> {
                    val local = progress[id]
                    if (deleted) {
                        if (local != null) { progress.remove(id); res = true }
                        sent.remove(id)
                    } else if (local == null || ts >= local.ts || local.position <= 0) {
                        val pos = data!!.optLong("pos"); val dur = data.optLong("dur")
                        if (pos > 0 && dur > 0) {
                            val item = remote!!.copy(ts = 0)
                            progress.remove(id)
                            progress[id] = Resume(item, pos, dur, ts)
                            sent[id] = pos; res = true
                        }
                    }
                }
            }
        }
        if (rec) {
            val sorted = recentChannels.sortedByDescending { it.ts }.take(MAX_RECENT)
            recentChannels.clear(); recentChannels.addAll(sorted)
        }
        if (res) progressVersion.intValue++
        if (fav || rec || res) Log.i("Nube: llegaron cambios de otro equipo (favoritos=$fav, recientes=$rec, seguir viendo=$res)")
        save()
    }

    // ═══ Organizar categorías (por perfil, igual que Android) ═══

    class CatPrefs(val hidden: Set<String>, val order: List<String>, val names: Map<String, String>)

    fun catPrefs(section: Section): CatPrefs = synchronized(cats) { cats[section.name] } ?: CatPrefs(emptySet(), emptyList(), emptyMap())

    fun saveCatPrefs(section: Section, p: CatPrefs) {
        synchronized(cats) { cats[section.name] = p }
        catVersion.intValue++
        save()
    }

    fun resetCatPrefs(section: Section) {
        synchronized(cats) { cats.remove(section.name) }
        catVersion.intValue++
        save()
    }

    private fun catToJson(p: CatPrefs) = JSONObject().put("hidden", JSONArray(p.hidden.toList())).put("order", JSONArray(p.order))
        .put("names", JSONObject().apply { p.names.forEach { (k, v) -> put(k, v) } })

    private fun catFromJson(o: JSONObject): CatPrefs {
        val h = o.optJSONArray("hidden") ?: JSONArray()
        val ord = o.optJSONArray("order") ?: JSONArray()
        val nm = o.optJSONObject("names") ?: JSONObject()
        return CatPrefs((0 until h.length()).map { h.optString(it) }.toSet(), (0 until ord.length()).map { ord.optString(it) },
            nm.keys().asSequence().associateWith { nm.optString(it) })
    }

    // ═══ Porque viste… ═══

    /** Guarda la última recomendación (igual que Android: solo en este equipo, no viaja a la nube). */
    fun setBecause(title: String, items: List<SavedItem>) {
        if (items.isEmpty()) return
        because.value = title to items
        save()
    }

    // ═══ Guardado ═══

    private fun save() {
        val o = JSONObject()
            .put("version", 2)
            .put("fav", JSONArray().apply { favorites.forEach { put(toJson(it)) } })
            .put("recent", JSONArray().apply { recentChannels.forEach { put(toJson(it)) } })
            .put("search", JSONArray(searchHistory.toList()))
            .put("progress", JSONArray().apply {
                progress.values.forEach { r ->
                    put(JSONObject().put("item", toJson(r.item)).put("pos", r.position).put("dur", r.duration).put("ts", r.ts))
                }
            })
            .put("sync", JSONObject()
                .put("started", syncStarted)
                .put("cursor", syncCursor)
                .put("pending", JSONObject().apply { pending.forEach { (k, v) -> put(k, v) } })
                .put("sent", JSONObject().apply { sent.forEach { (k, v) -> put(k, v) } }))
        synchronized(cats) { if (cats.isNotEmpty()) o.put("cats", JSONObject().apply { cats.forEach { (k, v) -> put(k, catToJson(v)) } }) }
        because.value?.let { (title, items) ->
            o.put("because", JSONObject().put("title", title).put("items", JSONArray().apply { items.forEach { put(toJson(it)) } }))
        }
        val text = o.toString()
        saver.execute {
            try { SafeFile.write(file, text) } catch (e: Exception) { Log.e("No se pudo guardar la biblioteca", e) }
        }
    }

    private fun load(arr: JSONArray?): List<SavedItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
    }

    private fun fromJson(o: JSONObject): SavedItem? {
        val id = o.optString("id").ifBlank { return null }
        return SavedItem(
            // Nombres guardados con versiones viejas: se limpian igual que los nuevos (como en Android)
            kind = o.optString("kind"), id = id,
            name = o.optString("name").let { n -> if (o.optString("kind") == "live") Names.channel(n) else Names.clean(n, titles = true) },
            image = o.optString("image"), ext = o.optString("ext"), seriesId = o.optString("seriesId"),
            categoryId = o.optString("categoryId"), ts = o.optLong("ts"),
        )
    }

    private fun toJson(i: SavedItem) = JSONObject()
        .put("kind", i.kind).put("id", i.id).put("name", i.name).put("image", i.image)
        .put("ext", i.ext).put("seriesId", i.seriesId).put("categoryId", i.categoryId).put("ts", i.ts)

    companion object {
        const val MAX_RECENT = 20
        const val MAX_PROGRESS = 300
        /** Perfil de la casa (el que ya existía antes de los perfiles). Mismo id que en Android. */
        const val PROFILE_MAIN = "principal"

        /** Nombre de archivo estable por cuenta, sin revelar servidor ni usuario. */
        fun keyHash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        /** El perfil principal conserva el archivo de siempre; los demás tienen el suyo. */
        fun fileName(accountKey: String, profileId: String) =
            keyHash(accountKey) + (if (profileId == PROFILE_MAIN) "" else "-$profileId") + ".json"
    }
}
