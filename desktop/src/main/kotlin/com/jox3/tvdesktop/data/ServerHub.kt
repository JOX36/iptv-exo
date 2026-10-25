package com.jox3.tvdesktop.data

import com.jox3.tvdesktop.core.Settings
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Varios servidores a la vez (cada cuenta guardada es un "servidor"):
 * - Estado de cada uno: si responde, en cuánto tiempo, cuándo vence y conexiones.
 * - Buscador universal: el catálogo de los otros servidores se descarga en segundo
 *   plano (una vez por sesión) y la búsqueda junta los resultados de todos. Cada
 *   título sale una sola vez, con los servidores donde está.
 * - Abrir algo de otro servidor: se cambia a ese servidor y, ya adentro, se abre
 *   ([pendingOpen]). Así favoritos, progreso e historial quedan en su servidor.
 *
 * Misma lógica que la app Android. En el PC hay memoria de sobra: los catálogos de los
 * otros servidores se preparan solos al entrar (se puede apagar en Cuenta).
 */
object ServerHub {
    data class Status(
        val ok: Boolean,
        val pingMs: Long,
        val info: ServerInfo?,
        val error: String = "",
        val checkedAt: Long = System.currentTimeMillis(),
    ) {
        /** Días que faltan para vencer (null = sin fecha). */
        val daysLeft: Long? get() = info?.expires?.let { ((it - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) }
        val active: Boolean get() = ok && (info?.status?.equals("Active", true) != false)
        /** Verde: bien · Naranja: lento o por vencer · Rojo: no responde o vencido. */
        val level: Int get() = when {
            !active -> 2
            (daysLeft ?: 99) <= 5 || pingMs > 1500 -> 1
            else -> 0
        }
    }

    /** Una copia de un título en un servidor. */
    data class Copy(
        val account: Account,
        val kind: String,          // live | movie | series
        val id: String,
        val name: String,
        val image: String,
        val ext: String = "",
        val categoryId: String = "",
        val rating: String = "",
        val tag: String = "",      // 4K, FHD, Latino… sacado del nombre
    )

    /** Un resultado del buscador universal: el título y dónde está. */
    data class Hit(val key: String, val kind: String, val name: String, val image: String, val rating: String, val copies: List<Copy>) {
        val servers: List<Account> get() = copies.map { it.account }.distinctBy { it.key }
    }

    data class Results(val channels: List<Hit>, val movies: List<Hit>, val series: List<Hit>) {
        val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val status = mutableStateMapOf<String, Status>()
    private val checking = HashSet<String>()

    /** Catálogos de los OTROS servidores (el actual usa el de su TvRepo). */
    private val indexes = HashMap<String, TvRepo.Index>()
    val loadingIndex = mutableStateMapOf<String, Boolean>()
    /** Sube cada vez que termina de cargar un catálogo: la búsqueda se repite. */
    var indexVersion = mutableIntStateOf(0)

    /** Lo que se abre al entrar al otro servidor. */
    var pendingOpen by mutableStateOf<Copy?>(null)

    // ── Lo que dan ahora (buscador y elegir servidor), igual que Android ──

    private val nowCache = HashMap<String, Pair<Long, String>>()
    private val nowPermits = Semaphore(3)

    /**
     * Programa que se emite ahora en ese canal de ESE servidor: primero su propia guía y,
     * si no trae, la guía de internet. "" si no hay. Se guarda 3 minutos (sin guía: 1 minuto).
     */
    suspend fun nowOn(c: Copy): String {
        if (c.kind != "live") return ""
        val key = c.account.key + "|" + c.id
        synchronized(nowCache) { nowCache[key] }?.let { (t, v) ->
            if (System.currentTimeMillis() - t < if (v.isEmpty()) 60_000 else 180_000) return v
        }
        val nowMs = System.currentTimeMillis()
        var items = nowPermits.withPermit {
            withContext(Dispatchers.IO) { runCatching { XtreamApi(c.account).shortEpg(c.id, 4) }.getOrDefault(emptyList()) }
        }
        if (items.isEmpty() && ExternalEpg.isReady()) {
            items = withContext(Dispatchers.Default) {
                runCatching {
                    ExternalEpg.match(c.name, null, null)?.progs
                        ?.map { it.toItem() }.orEmpty()
                }.getOrDefault(emptyList())
            }
        }
        val cur = items.firstOrNull { nowMs in it.start until it.end } ?: items.firstOrNull { it.end > nowMs }
        val title = cur?.title.orEmpty()
        synchronized(nowCache) { nowCache[key] = System.currentTimeMillis() to title }
        return title
    }

    /** Lo que dan ahora en un canal encontrado: del servidor actual si tiene guía; si no, del primero que la tenga. */
    suspend fun nowForHit(h: Hit, current: Account): Pair<String, Account?> {
        val copies = h.copies.distinctBy { it.account.key }.sortedBy { if (it.account.key == current.key) 0 else 1 }
        for (c in copies) {
            val t = nowOn(c)
            if (t.isNotEmpty()) return t to c.account
        }
        return "" to null
    }

    // ── Preferencias ──
    fun searchAllEnabled() = Settings.bool("servers.searchAll", true)
    fun setSearchAll(on: Boolean) = Settings.put("servers.searchAll", on)

    /** Letra de cada servidor según su orden en la lista guardada (A, B, C…). */
    fun letter(all: List<Account>, acc: Account): String {
        val i = all.indexOfFirst { it.key == acc.key }.coerceAtLeast(0)
        return ('A' + (i % 26)).toString()
    }

    /** Nombre para mostrar: el dominio; si dos cuentas comparten servidor, también el usuario. */
    fun name(all: List<Account>, acc: Account): String =
        if (all.count { it.label == acc.label } > 1) "${acc.label} · ${acc.user}" else acc.label

    // ── Estado ──

    /** Consulta el estado de estos servidores (en paralelo). No repite si se consultó hace poco. */
    fun refresh(accounts: List<Account>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        accounts.forEach { acc ->
            val prev = status[acc.key]
            if (!force && prev != null && now - prev.checkedAt < 5 * 60_000) return@forEach
            synchronized(checking) { if (!checking.add(acc.key)) return@forEach }
            scope.launch {
                val t0 = System.currentTimeMillis()
                val st = try {
                    val info = XtreamApi(acc).serverInfo()
                    Status(true, System.currentTimeMillis() - t0, info)
                } catch (e: Exception) {
                    Status(false, System.currentTimeMillis() - t0, null, e.message ?: "No responde")
                }
                withContext(Dispatchers.Main) { status[acc.key] = st }
                synchronized(checking) { checking.remove(acc.key) }
            }
        }
    }

    // ── Catálogos y búsqueda ──

    /** Cola: los catálogos se descargan de a UNO (antes iban todos a la vez y llenaban la memoria). */
    private val queueLock = kotlinx.coroutines.sync.Mutex()

    /** Descarga en segundo plano el catálogo de los otros servidores (una vez por sesión, de a uno). */
    fun ensureIndexes(others: List<Account>) {
        others.forEach { acc ->
            if (hasIndex(acc) || loadingIndex[acc.key] == true) return@forEach
            loadingIndex[acc.key] = true
            scope.launch {
                val built = queueLock.withLock {
                    if (hasIndex(acc)) return@withLock null
                    val api = XtreamApi(acc)
                    runCatching {
                        // Uno detrás de otro: así nunca hay dos listas grandes llegando a la vez
                        val ch = runCatching { api.channels() }.getOrDefault(emptyList())
                        val mv = runCatching { api.movies() }.getOrDefault(emptyList())
                        val sr = runCatching { api.series() }.getOrDefault(emptyList())
                        withContext(Dispatchers.Default) { TvRepo.Index(ch, mv, sr) }
                    }.getOrNull()
                }
                synchronized(indexes) { if (built != null) indexes[acc.key] = built }
                withContext(Dispatchers.Main) {
                    loadingIndex[acc.key] = false
                    indexVersion.intValue++
                }
            }
        }
    }

    fun hasIndex(acc: Account) = synchronized(indexes) { indexes.containsKey(acc.key) }

    /** Olvida el catálogo de un servidor (al borrarlo). */
    fun forget(acc: Account) { synchronized(indexes) { indexes.remove(acc.key) }; status.remove(acc.key) }

    /**
     * Busca en el servidor actual (su índice) y en los otros ya cargados, y junta los
     * resultados por título. [only] limita a un servidor (null = todos).
     */
    suspend fun search(
        query: String, current: Account, currentIndex: TvRepo.Index, all: List<Account>, only: String? = null,
        includeOthers: Boolean = true,
    ): Results = withContext(Dispatchers.Default) {
        val q = TvRepo.normalize(query.trim())
        val sources = buildList {
            if (only == null || only == current.key) add(current to currentIndex)
            if (includeOthers) all.filter { it.key != current.key && (only == null || only == it.key) }.forEach { acc ->
                synchronized(indexes) { indexes[acc.key] }?.let { add(acc to it) }
            }
        }
        val ch = LinkedHashMap<String, MutableList<Copy>>()
        val mv = LinkedHashMap<String, MutableList<Copy>>()
        val sr = LinkedHashMap<String, MutableList<Copy>>()
        for ((acc, idx) in sources) {
            var n = 0
            for (i in idx.channelKeys.indices) {
                if (!idx.channelKeys[i].contains(q)) continue
                val c = idx.channels[i]
                ch.getOrPut(TvRepo.titleKey(c.name).ifBlank { c.name }) { ArrayList() }
                    .add(Copy(acc, "live", c.id, c.name, c.logo, categoryId = c.categoryId, tag = tagOf(c.name)))
                if (++n == 40) break
            }
            n = 0
            for (i in idx.movieKeys.indices) {
                if (!idx.movieKeys[i].contains(q)) continue
                val m = idx.movies[i]
                mv.getOrPut(TvRepo.titleKey(m.name).ifBlank { m.name }) { ArrayList() }
                    .add(Copy(acc, "movie", m.id, m.name, m.poster, m.ext, m.categoryId, m.rating, tagOf(m.name)))
                if (++n == 60) break
            }
            n = 0
            for (i in idx.seriesKeys.indices) {
                if (!idx.seriesKeys[i].contains(q)) continue
                val s = idx.series[i]
                sr.getOrPut(TvRepo.titleKey(s.name).ifBlank { s.name }) { ArrayList() }
                    .add(Copy(acc, "series", s.id, s.name, s.poster, "", s.categoryId, s.rating, tagOf(s.name)))
                if (++n == 60) break
            }
        }
        fun hits(map: Map<String, List<Copy>>, kind: String, limit: Int) = map.entries.take(limit).map { (k, copies) ->
            // Se muestra la copia del servidor actual si la hay, y la que trae imagen
            val main = copies.firstOrNull { it.account.key == current.key && it.image.startsWith("http") }
                ?: copies.firstOrNull { it.image.startsWith("http") } ?: copies.first()
            Hit(k, kind, main.name, main.image, copies.firstNotNullOfOrNull { it.rating.ifBlank { null } }.orEmpty(),
                // Orden: servidor actual primero, luego el orden de la lista
                copies.sortedBy { c -> if (c.account.key == current.key) -1 else all.indexOfFirst { it.key == c.account.key } })
        }
        Results(hits(ch, "live", 40), hits(mv, "movie", 50), hits(sr, "series", 50))
    }

    /**
     * Dónde más está este mismo título (mismo nombre limpio), sin contar [currentId] del
     * servidor actual. Para "Disponible en" de las fichas.
     */
    suspend fun copiesOf(kind: String, name: String, current: Account, currentIndex: TvRepo.Index?, all: List<Account>): List<Copy> =
        withContext(Dispatchers.Default) {
            val key = TvRepo.titleKey(name)
            if (key.isBlank()) return@withContext emptyList()
            val out = ArrayList<Copy>()
            val sources = buildList {
                if (currentIndex != null) add(current to currentIndex)
                all.filter { it.key != current.key }.forEach { a -> synchronized(indexes) { indexes[a.key] }?.let { add(a to it) } }
            }
            for ((acc, idx) in sources) {
                if (kind == "movie") idx.movies.forEach { m ->
                    if (TvRepo.titleKey(m.name) == key) out += Copy(acc, "movie", m.id, m.name, m.poster, m.ext, m.categoryId, m.rating, tagOf(m.name))
                } else idx.series.forEach { sr ->
                    if (TvRepo.titleKey(sr.name) == key) out += Copy(acc, "series", sr.id, sr.name, sr.poster, "", sr.categoryId, sr.rating, tagOf(sr.name))
                }
            }
            out
        }

    /** Etiqueta corta de calidad o idioma sacada del nombre ("[4K]", "Latino"…). */
    fun tagOf(name: String): String {
        val n = TvRepo.normalize(name)
        val parts = ArrayList<String>(2)
        when {
            Regex("\\b(4k|uhd|2160)\\b").containsMatchIn(n) -> parts += "4K"
            Regex("\\b(fhd|1080p?)\\b").containsMatchIn(n) -> parts += "FHD"
            Regex("\\bhd\\b").containsMatchIn(n) -> parts += "HD"
            Regex("\\bsd\\b").containsMatchIn(n) -> parts += "SD"
        }
        when {
            "latino" in n || Regex("\\blat\\b").containsMatchIn(n) -> parts += "Latino"
            "castellano" in n -> parts += "Castellano"
            Regex("\\b(sub|subtitulad[ao]|vose)\\b").containsMatchIn(n) -> parts += "Subtitulada"
        }
        return parts.joinToString(" · ")
    }
}


