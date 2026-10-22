package com.jox3.tvdesktop.data

import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Guía de programación de internet (XMLTV de epgshare01) para los canales cuyo servidor
 * no la trae. Misma lógica y mismo emparejamiento de canales que la app Android
 * (y JOX3 Verifier); aquí el XML se lee con StAX de Java.
 *
 * - Descarga una vez al día los archivos por país (comprimidos) en %APPDATA%\JOX3 TV\epg.
 * - Los lee en segundo plano sin cargar el archivo entero, y guarda solo desde unas
 *   horas atrás hasta día y medio adelante.
 * - Si no hay internet, usa la última copia descargada.
 */
object ExternalEpg {
    private const val BASE = "https://epgshare01.online/epgshare01/epg_ripper_"
    private const val MAX_AGE_MS = 20L * 3600 * 1000      // volver a descargar cada ~20 h
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOX3TV-PC"

    data class Prog(val start: Long, val stop: Long, val title: String, val desc: String)
    class Chan(val key: String, val id: String, val code: String, val names: List<String>) {
        val progs = ArrayList<Prog>()
    }

    @Volatile private var chans: Map<String, Chan> = emptyMap()             // "CODE|id" → canal
    @Volatile private var index: Map<String, List<Chan>> = emptyMap()       // nombre normalizado o id → canales
    @Volatile private var naturalKeys: Set<String> = emptySet()
    @Volatile private var loadedCodes: Set<String> = emptySet()
    @Volatile private var loadedAt = 0L
    @Volatile private var pending: List<String>? = null
    @Volatile private var pendingCb: (() -> Unit)? = null
    @Volatile var loading = false
        private set
    @Volatile var lastError = ""
        private set

    fun isReady() = chans.isNotEmpty()
    fun channelCount() = chans.size

    /** Asegura que las guías de estos países estén descargadas y leídas. No bloquea. */
    fun ensure(codes: List<String>, onReady: (() -> Unit)? = null) {
        val want = codes.map { it.trim().uppercase(Locale.ROOT) }.filter { it.matches(Regex("[A-Z0-9_]{2,20}")) }.distinct()
        if (want.isEmpty()) return
        synchronized(this) {
            val fresh = want.toSet() == loadedCodes && System.currentTimeMillis() - loadedAt < 3L * 3600 * 1000
            if (fresh) return
            // Si ya está descargando, se anota para repetir con los países nuevos al terminar.
            if (loading) { pending = want; pendingCb = onReady; return }
            loading = true
        }
        Thread {
            try {
                val dir = File(AppDirs.data, "epg").apply { mkdirs() }
                val files = want.mapNotNull { code -> download(dir, code)?.let { code to it } }
                if (files.isNotEmpty()) parseAll(files)
                loadedCodes = want.toSet()
                loadedAt = System.currentTimeMillis()
                Log.i("Guía de internet lista: ${chans.size} canales (${want.joinToString(",")})")
            } catch (e: Throwable) {
                lastError = e.message ?: "error"
                Log.w("Falló la guía de internet", e)
            } finally {
                loading = false
            }
            runCatching { onReady?.invoke() }
            val next = synchronized(this) { val n = pending; pending = null; n }
            if (next != null) ensure(next, pendingCb)
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true; name = "epg-internet" }.start()
    }

    private fun download(dir: File, code: String): File? {
        val f = File(dir, "$code.xml.gz")
        if (f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return f
        val tmp = File(dir, "$code.part")
        try {
            val req = Request.Builder().url("$BASE$code.xml.gz").header("User-Agent", UA).build()
            Net.api.newCall(req).execute().use { r ->
                if (r.code != 200) throw RuntimeException("HTTP ${r.code}")
                val body = r.body ?: throw RuntimeException("sin contenido")
                body.byteStream().use { inp -> tmp.outputStream().use { out -> inp.copyTo(out, 32 * 1024) } }
            }
            if (tmp.length() < 200) throw RuntimeException("archivo vacío")
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            tmp.delete()
            lastError = "$code: ${e.message}"
            Log.w("No se pudo descargar la guía $code: ${e.message}")
        }
        // Si no se pudo bajar, se usa la copia anterior (aunque sea vieja).
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Hora XMLTV ("20261022180000 -0500") a segundos. */
    internal fun xmltvTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val t = s.trim()
        return try {
            val d = if (t.length > 14) SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(t)
            else SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(t.take(14))
            (d?.time ?: 0) / 1000
        } catch (e: Exception) { 0 }
    }

    private fun parseAll(files: List<Pair<String, File>>) {
        val now = System.currentTimeMillis() / 1000
        val from = now - 4 * 3600
        val to = now + 36 * 3600
        val outChans = HashMap<String, Chan>()
        val factory = XMLInputFactory.newInstance().apply {
            // Seguridad: sin entidades externas (un XML malicioso no puede leer archivos del PC)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        for ((code, file) in files) {
            try {
                GZIPInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
                    val r = factory.createXMLStreamReader(input, "UTF-8")
                    try {
                        val names = ArrayList<String>()
                        var chId: String? = null
                        var pCh: String? = null; var pStart = 0L; var pStop = 0L
                        var pTitle = ""; var pDesc = ""
                        var inProg = false
                        val pending = HashMap<String, ArrayList<Prog>>()
                        while (r.hasNext()) {
                            when (r.next()) {
                                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                                    "channel" -> { chId = r.getAttributeValue(null, "id"); names.clear() }
                                    "display-name" -> if (chId != null) names.add(r.elementText.trim())
                                    "programme" -> {
                                        inProg = true
                                        pCh = r.getAttributeValue(null, "channel")
                                        pStart = xmltvTime(r.getAttributeValue(null, "start"))
                                        pStop = xmltvTime(r.getAttributeValue(null, "stop"))
                                        pTitle = ""; pDesc = ""
                                    }
                                    "title" -> if (inProg && pTitle.isEmpty()) pTitle = r.elementText.trim()
                                    "desc" -> if (inProg && pDesc.isEmpty()) pDesc = r.elementText.trim()
                                }
                                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                                    "channel" -> {
                                        val id = chId
                                        if (!id.isNullOrBlank()) outChans["$code|$id"] = Chan("$code|$id", id, code, names.toList())
                                        chId = null
                                    }
                                    "programme" -> {
                                        inProg = false
                                        val c = pCh
                                        if (c != null && pTitle.isNotEmpty() && pStop > from && pStart < to && pStop > pStart) {
                                            pending.getOrPut(c) { ArrayList() }.add(Prog(pStart, pStop, pTitle, pDesc.take(400)))
                                        }
                                    }
                                }
                            }
                        }
                        for ((id, list) in pending) {
                            val ch = outChans["$code|$id"] ?: Chan("$code|$id", id, code, emptyList()).also { outChans["$code|$id"] = it }
                            ch.progs.addAll(list)
                        }
                    } finally {
                        r.close()
                    }
                }
            } catch (e: Throwable) {
                lastError = "$code: ${e.message}"
                Log.w("No se pudo leer la guía $code", e)
            }
        }
        val idx = HashMap<String, MutableList<Chan>>()
        val natural = HashSet<String>()
        fun put(k: String, c: Chan) { if (k.length >= 2) { val l = idx.getOrPut(k) { ArrayList() }; if (!l.contains(c)) l.add(c) } }
        for (c in outChans.values) {
            c.progs.sortBy { it.start }
            put("id:" + c.id.lowercase(Locale.ROOT), c)
            val forms = (c.names + idFormName(c.id)).flatMap { keysOf(it) }.toSet()
            forms.forEach { put(it, c) }
            // Para desempatar: el nombre propio pesa más que uno sacado de un alias (DirecTV Sports → DSports).
            (c.names + idFormName(c.id)).forEach { n -> natural.add(tokens(n).joinToString("") + "|" + c.key) }
        }
        chans = outChans
        index = idx
        naturalKeys = natural
        matchCache.clear()
    }

    // ═══ Emparejar canales (igual que Android) ═══
    private val QUALITY = setOf("hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "1080p", "720p", "1080", "720", "hq", "lq", "fullhd", "backup", "alt", "vip", "col")
    private val PREFIX = setOf("co", "col", "mx", "ar", "cl", "pe", "ec", "us", "usa", "es", "lat", "latam", "br", "uy", "py", "bo", "ve", "cr", "pa", "do", "pr", "sv", "gt", "hn", "ni", "cu", "int")
    private val STOP = setOf("en", "de", "del", "la", "el", "y", "the")
    private val ALIAS = listOf(
        "directvsports" to "dsports",
        "winsportspremium" to "winsportsplus",
        "winplus" to "winsportsplus",
        "canaluno" to "canal1",
        "foxsports1" to "foxsports",
        "espn1" to "espn",
        "hbo1" to "hbo",
        "caracoltelevision" to "caracol",
        "rcntelevision" to "rcn",
        "gamatv" to "gamavision",
        "goltvlatinoamerica" to "goltv",
    )

    private fun alias(k: String): String {
        for ((a, b) in ALIAS) if (k.startsWith(a)) return b + k.substring(a.length)
        return k
    }

    /** "DSPORTS.+.HD(DTS+HD).co" → "DSPORTS + HD" */
    private fun idFormName(id: String): String {
        var s = id.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\.[a-z]{2,6}$"), "")
        return s.replace('.', ' ').replace('_', ' ')
    }

    internal fun tokens(name: String): List<String> {
        var s = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        s = s.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        s = s.replace("&amp;", "&").replace("+", " plus ")
        val t = s.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toMutableList()
        while (t.size > 1 && t[0] in PREFIX) t.removeAt(0)
        return t.filter { it !in QUALITY && it !in STOP }
    }

    /** Formas en que se puede reconocer un nombre: completo y sin "tv"/"channel"/"canal" al final. */
    internal fun keysOf(name: String): List<String> {
        val t = tokens(name)
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        fun add(list: List<String>) {
            val k = list.joinToString("")
            if (k.length >= 2) out.add(alias(k))
        }
        add(t)
        var short = t.filter { it != "tv" && it != "channel" && it != "television" && it != "network" }
        // "Canal RCN" → "RCN", pero "Canal 1" se queda igual.
        if (short.size > 1 && short[0] == "canal" && !short[1].all { it.isDigit() }) short = short.drop(1)
        if (short.isNotEmpty() && short != t) add(short)
        return out.distinct()
    }

    private val matchCache = ConcurrentHashMap<String, String>()

    /** Canal de la guía para un canal del servidor (o null). */
    fun match(name: String, tvgId: String?, countryHint: String? = null): Chan? {
        val map = chans
        if (map.isEmpty()) return null
        val ck = name + "\u0000" + (tvgId ?: "") + "\u0000" + (countryHint ?: "")
        matchCache[ck]?.let { return if (it.isEmpty()) null else map[it] }
        val found = findMatch(name, tvgId, countryHint)
        matchCache[ck] = found?.key ?: ""
        return found
    }

    private fun findMatch(name: String, tvgId: String?, countryHint: String?): Chan? {
        val idx = index
        // País del canal: el que trae el nombre ("EC | …") o el de su categoría.
        val country = tokensPrefix(name) ?: countryHint?.uppercase(Locale.ROOT)?.takeIf { it.length in 2..3 }
        val nat = naturalKeys
        fun best(list: List<Chan>?, k: String = ""): Chan? {
            if (list.isNullOrEmpty()) return null
            return list.sortedWith(compareByDescending<Chan> { it.progs.isNotEmpty() }
                .thenByDescending { country != null && it.code.startsWith(country) }
                .thenByDescending { nat.contains("$k|${it.key}") }
                .thenBy { it.id.length }).first()
        }
        if (!tvgId.isNullOrBlank()) {
            best(idx["id:" + tvgId.trim().lowercase(Locale.ROOT)])?.let { return it }
            keysOf(idFormName(tvgId)).forEach { k -> best(idx[k])?.let { return it } }
        }
        val keys = keysOf(name)
        keys.forEach { k -> best(idx[k], k)?.let { return it } }
        // "Citytv Bogotá" → "City TV": la guía tiene el nombre corto y el servidor le agrega la ciudad.
        val letters = Regex("[a-z]{4,}")
        var pre: String? = null
        for (k in keys) for (ik in idx.keys) {
            if (ik.length >= 6 && !ik.startsWith("id:") && k.startsWith(ik) && letters.matches(k.substring(ik.length)) &&
                (pre == null || ik.length > pre!!.length)) pre = ik
        }
        pre?.let { p -> best(idx[p], p)?.let { return it } }
        // "TyC Sports" → "TyC Sports International": solo si hay un único candidato.
        for (k in keys) {
            if (k.length < 6) continue
            val cands = idx.filterKeys { !it.startsWith("id:") && it.startsWith(k) && letters.matches(it.substring(k.length)) }
                .values.flatten().distinct()
            if (cands.size == 1) return cands[0]
        }
        return null
    }

    /** País al principio del nombre ("MX | Azteca 7" → "MX") para preferir esa guía. */
    private fun tokensPrefix(name: String): String? {
        val m = Regex("^\\s*[|\\[(]?\\s*([A-Za-z]{2,3})\\s*[|:\\-\\])]").find(name) ?: return null
        return m.groupValues[1].uppercase(Locale.ROOT).let { if (it == "COL") "CO" else if (it == "USA") "US" else it }
    }

    /** Solo para pruebas: carga canales ya armados como si vinieran del XML. */
    internal fun loadForTest(list: List<Chan>) {
        val idx = HashMap<String, MutableList<Chan>>()
        list.forEach { c ->
            idx.getOrPut("id:" + c.id.lowercase(Locale.ROOT)) { ArrayList() }.add(c)
            (c.names + idFormName(c.id)).flatMap { keysOf(it) }.toSet().forEach { k -> idx.getOrPut(k) { ArrayList() }.add(c) }
        }
        chans = list.associateBy { it.key }
        index = idx
        naturalKeys = emptySet()
        matchCache.clear()
    }
}
