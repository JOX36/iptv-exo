package com.jox3.iptvexo.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Guía de programación sacada de internet (XMLTV) para los canales cuyo servidor no la trae.
 * Traída tal cual de JOX3 Verifier (mismo emparejamiento de canales), con la descarga
 * pasando por el cliente HTTP compartido de la app.
 *
 * - Descarga una vez al día los archivos por país de epgshare01 (comprimidos, pocos cientos de KB).
 * - Los lee en segundo plano, sin cargar el archivo entero en memoria, y guarda solo
 *   lo que va de unas horas atrás a día y medio adelante.
 * - Empareja cada canal del servidor por su código de guía (tvg-id) o por el nombre limpio.
 *   El usuario puede corregir a mano un canal; eso se recuerda.
 *
 * Es un objeto único del proceso: lo comparten la interfaz TV y su reproductor en vivo.
 */
object ExternalEpg {
    private const val BASE = "https://epgshare01.online/epgshare01/epg_ripper_"
    private const val PREFS = "ext_epg"
    private const val MAX_AGE_MS = 20L * 3600 * 1000      // volver a descargar cada ~20 h
    private const val UA = "Mozilla/5.0 (Linux; Android) JOX3TV"

    data class Prog(
        val start: Long, val stop: Long, val title: String, val desc: String,
        val icon: String = "", val category: String = "", val live: Boolean = false, val isNew: Boolean = false,
        val episode: String = "",
    ) {
        fun toItem() = EpgItem(title, desc, start * 1000, stop * 1000, icon, category, live, isNew, episode)
    }

    /**
     * Segunda fuente: open-epg.com (más canales en Latinoamérica). Por cada archivo de
     * epgshare01 se bajan también estos. En equipos con poca memoria, solo el primero.
     */
    private val OPEN_EPG = mapOf(
        "CO1" to listOf("colombia1", "colombia2", "colombia3", "colombia4"),
        "MX1" to listOf("mexico1", "mexico2", "mexico3", "mexico4"),
        "AR1" to listOf("argentina1", "argentina2", "argentina3", "argentina4"),
        "CL1" to listOf("chile1", "chile2"), "PE1" to listOf("peru1", "peru2"), "EC1" to listOf("ecuador1"),
        "UY1" to listOf("uruguay1", "uruguay2"), "CR1" to listOf("costarica1", "costarica2"),
        "PA1" to listOf("panama1", "panama2", "panama3"), "DO1" to listOf("dominican1", "dominican2", "dominican3"),
        "ES1" to listOf("spain1", "spain2", "spain3", "spain4"), "PT1" to listOf("portugal1", "portugal2"),
        "BR1" to listOf("brazil2", "brazil3"), "CA2" to listOf("canada1"),
        "US_SPORTS1" to listOf("sports1", "sports4"),
    )
    private const val OE_URL = "https://www.open-epg.com/files/"
    /** País de cada archivo (para preferir la guía del país del canal). */
    private fun countryOf(code: String): String =
        if (code.startsWith("OE_")) OPEN_EPG.entries.firstOrNull { (_, l) -> code.removePrefix("OE_") in l }?.key ?: code else code
    @Volatile var lowMemory = false
    class Chan(val key: String, val id: String, val code: String, val names: List<String>) {
        val progs = ArrayList<Prog>()
    }

    @Volatile private var chans: Map<String, Chan> = emptyMap()             // "CODE|id" → canal
    @Volatile private var index: Map<String, List<Chan>> = emptyMap()       // nombre normalizado o id → canales
    @Volatile private var naturalKeys: Set<String> = emptySet()
    @Volatile private var loadedCodes: Set<String> = emptySet()
    @Volatile private var loadedAt = 0L
    @Volatile private var pending: List<String>? = null
    @Volatile var loading = false
        private set
    @Volatile var lastError = ""
        private set

    fun isReady() = chans.isNotEmpty()
    fun channelCount() = chans.size

    fun savedCodes(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("codes", "")!!
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Avisos pendientes: TODOS los que pidieron la guía mientras se descargaba (no solo el último). */
    private val callbacks = ArrayList<() -> Unit>()
    @Volatile private var lastFailAt = 0L
    @Volatile private var retries = 0

    /**
     * Asegura que las guías de estos países estén descargadas y leídas. No bloquea.
     * [onReady] se llama siempre: al terminar la descarga, o enseguida si ya estaba lista.
     */
    fun ensure(ctx: Context, codes: List<String>, onReady: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val want = codes.map { it.trim().uppercase(Locale.ROOT) }.filter { it.matches(Regex("[A-Z0-9_]{2,20}")) }.distinct()
        if (want.isEmpty()) return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("codes", want.joinToString(",")).apply()
        var callNow = false
        synchronized(this) {
            val now = System.currentTimeMillis()
            val fresh = want.toSet() == loadedCodes && now - loadedAt < 3L * 3600 * 1000
            when {
                // Ya está: avisar igual, para que quien la pidió vuelva a consultar
                fresh -> callNow = true
                // Descargando: se anota (los países nuevos se bajan al terminar)
                loading -> { pending = want; onReady?.let { if (it !in callbacks) callbacks.add(it) }; return }
                // Falló hace menos de 1 minuto: esperar antes de reintentar
                now - lastFailAt < 60_000 -> { onReady?.let { if (it !in callbacks) callbacks.add(it) }; return }
                else -> { loading = true; onReady?.let { if (it !in callbacks) callbacks.add(it) } }
            }
        }
        if (callNow) { onReady?.let { runCatching(it) }; return }
        Thread {
            try {
                val dir = File(app.filesDir, "epg").apply { mkdirs() }
                val files = want.mapNotNull { code -> download(dir, code)?.let { code to it } }
                // Segunda fuente (open-epg): si falla alguna, no importa
                val extra = want.flatMap { c -> OPEN_EPG[c].orEmpty().let { if (lowMemory) it.take(1) else it } }.distinct()
                    .mapNotNull { name -> download(dir, "OE_$name")?.let { "OE_$name" to it } }
                if (files.isNotEmpty() || extra.isNotEmpty()) parseAll(files + extra)
                if (files.size == want.size) {
                    loadedCodes = want.toSet()
                    loadedAt = System.currentTimeMillis()
                    lastFailAt = 0L
                } else {
                    // Faltó algún país (sin internet, servidor caído…): se reintenta pronto, no en 3 horas
                    loadedCodes = emptySet()
                    lastFailAt = System.currentTimeMillis()
                }
            } catch (e: Throwable) {
                lastError = e.message ?: "error"
                loadedCodes = emptySet()
                lastFailAt = System.currentTimeMillis()
            } finally {
                loading = false
            }
            val failed = loadedCodes.isEmpty()
            if (failed && retries < 5) {
                // Reintento solo en 1 minuto (los avisos pendientes esperan a que salga bien)
                retries++
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ensure(app, want, null) }, 61_000)
            } else if (!failed) retries = 0
            // Aviso a todos los que esperaban (si falló, ven lo que haya de una copia anterior)
            val cbs = synchronized(this) { if (failed && retries in 1..4 && !isReady()) emptyList() else callbacks.toList().also { callbacks.clear() } }
            cbs.forEach { runCatching(it) }
            val next = synchronized(this) { val n = pending; pending = null; n }
            if (next != null && next.toSet() != loadedCodes) ensure(app, next, null)
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private fun download(dir: File, code: String): File? {
        val f = File(dir, "$code.xml.gz")
        if (f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return f
        val tmp = File(dir, "$code.part")
        val url = if (code.startsWith("OE_")) OE_URL + code.removePrefix("OE_") + ".xml.gz" else "$BASE$code.xml.gz"
        try {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).build()
            com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
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
        }
        // Si no se pudo bajar, se usa la copia anterior (aunque sea vieja).
        return if (f.exists() && f.length() > 0) f else null
    }

    private fun parseAll(files: List<Pair<String, File>>) {
        val now = System.currentTimeMillis() / 1000
        val from = now - 4 * 3600
        val to = now + 36 * 3600
        val outChans = HashMap<String, Chan>()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun time(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            val t = s.trim()
            return try {
                (if (t.length > 14) fmt.parse(t) else fmtNoTz.parse(t.take(14)))?.time?.div(1000) ?: 0
            } catch (e: Exception) { 0 }
        }
        for ((code, file) in files) {
            try {
                GZIPInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
                    val p = XmlPullParserFactory.newInstance().newPullParser()
                    p.setInput(input, "UTF-8")
                    val names = ArrayList<String>()
                    var chId: String? = null
                    var pCh: String? = null; var pStart = 0L; var pStop = 0L
                    var pTitle = ""; var pDesc = ""
                    var pIcon = ""; var pCat = ""; var pLive = false; var pNew = false; var pEp = ""
                    var inProg = false
                    val pending = HashMap<String, ArrayList<Prog>>()
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (p.name) {
                                "channel" -> { chId = p.getAttributeValue(null, "id"); names.clear() }
                                "display-name" -> if (chId != null) names.add(p.nextText().trim())
                                "programme" -> {
                                    inProg = true
                                    pCh = p.getAttributeValue(null, "channel")
                                    pStart = time(p.getAttributeValue(null, "start"))
                                    pStop = time(p.getAttributeValue(null, "stop"))
                                    pTitle = ""; pDesc = ""
                                    pIcon = ""; pCat = ""; pLive = false; pNew = false; pEp = ""
                                }
                                "title" -> if (inProg && pTitle.isEmpty()) pTitle = p.nextText().trim()
                                "desc" -> if (inProg && pDesc.isEmpty()) pDesc = p.nextText().trim()
                                "icon" -> if (inProg && pIcon.isEmpty()) pIcon = p.getAttributeValue(null, "src").orEmpty()
                                "category" -> if (inProg && pCat.isEmpty()) pCat = p.nextText().trim().take(40)
                                "live" -> if (inProg) pLive = true
                                "new", "premiere" -> if (inProg) pNew = true
                                "episode-num" -> if (inProg && pEp.isEmpty()) {
                                    val sys = p.getAttributeValue(null, "system").orEmpty()
                                    pEp = episodeText(sys, p.nextText().trim())
                                }
                            }
                        } else if (ev == XmlPullParser.END_TAG) {
                            when (p.name) {
                                "channel" -> {
                                    val id = chId
                                    if (!id.isNullOrBlank()) {
                                        val key = "$code|$id"
                                        outChans[key] = Chan(key, id, code, names.toList())
                                    }
                                    chId = null
                                }
                                "programme" -> {
                                    inProg = false
                                    val c = pCh
                                    if (c != null && pTitle.isNotEmpty() && pStop > from && pStart < to && pStop > pStart) {
                                        pending.getOrPut(c) { ArrayList() }.add(Prog(pStart, pStop, pTitle, pDesc.take(400),
                                            pIcon.takeIf { it.startsWith("http") }.orEmpty().take(300), pCat, pLive, pNew, pEp))
                                    }
                                }
                            }
                        }
                        ev = p.next()
                    }
                    for ((id, list) in pending) {
                        val ch = outChans["$code|$id"] ?: Chan("$code|$id", id, code, emptyList()).also { outChans["$code|$id"] = it }
                        ch.progs.addAll(list)
                    }
                }
            } catch (e: Throwable) {
                lastError = "$code: ${e.message}"
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

    // ═══ Emparejar canales ═══
    private val QUALITY = setOf("hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "1080p", "720p", "1080", "720", "hq", "lq", "fullhd", "backup", "alt", "vip", "col")
    private val PREFIX = setOf("co", "col", "mx", "ar", "cl", "pe", "ec", "us", "usa", "es", "lat", "latam", "br", "uy", "py", "bo", "ve", "cr", "pa", "do", "pr", "sv", "gt", "hn", "ni", "cu", "int")
    private val STOP = setOf("en", "de", "del", "la", "el", "y", "the")
    private val COUNTRY_WORDS = setOf("colombia", "mexico", "argentina", "chile", "peru", "ecuador", "venezuela", "uruguay",
        "paraguay", "bolivia", "panama", "espana", "spain", "latam", "latino", "latinoamerica", "latin", "america", "internacional",
        "international", "usa", "brasil", "brazil", "andina", "sur", "norte")
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
        "winsportsplus" to "winsportsplus",
        "winsportspremiumhd" to "winsportsplus",
        "winplusfutbol" to "winsportsplusfutbol",
        "espnlatinoamerica" to "espn",
        "espninternacional" to "espn",
        "tntsportschile" to "tntsports",
        "telemundointernacional" to "telemundo",
        "univisiontlnovelas" to "tlnovelas",
        "historychannel" to "history",
        "discoverychannel" to "discovery",
        "cartoonnetwork" to "cartoon",
        "nickelodeon" to "nick",
    )
    private fun alias(k: String): String {
        for ((a, b) in ALIAS) if (k.startsWith(a)) return b + k.substring(a.length)
        return k
    }

    /** "DSPORTS.+.HD(DTS+HD).co" → "DSPORTS + HD" */
    private fun idFormName(id: String): String {
        var s = id.replace(Regex("\\([^)]*\\)"), " ")
        // Sufijo de la fuente: ".co", ".ec", ".dtvsp"…
        s = s.replace(Regex("\\.[a-z]{2,6}$"), "")
        return s.replace('.', ' ').replace('_', ' ')
    }

    private fun tokens(name: String): List<String> {
        var s = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        s = s.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        s = s.replace("&amp;", "&").replace("+", " plus ")
        val t = s.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toMutableList()
        // Prefijos de país y números de orden al principio ("01 • WIN+ FÚTBOL", "CO 12 Caracol")
        while (t.size > 1 && (t[0] in PREFIX || (t[0].all { it.isDigit() } && t[0].length <= 3 && t.size > 2))) t.removeAt(0)
        // "01 WIN SPORTS" con dos palabras: también se quita el número
        if (t.size == 2 && t[0].all { it.isDigit() } && t[0].startsWith("0")) t.removeAt(0)
        return t.filter { it !in QUALITY && it !in STOP }
    }

    /** Formas en que se puede reconocer un nombre: completo y sin "tv"/"channel"/"canal" al final. */
    private fun keysOf(name: String): List<String> {
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
        // "ESPN COLOMBIA" → "ESPN", "FOX SPORTS ARGENTINA" → "FOX SPORTS": el país va aparte
        val noCountry = short.filter { it !in COUNTRY_WORDS }
        if (noCountry.isNotEmpty() && noCountry.size < short.size) add(noCountry)
        return out.distinct()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manualKey(name: String) = "m:" + (keysOf(name).firstOrNull() ?: name.lowercase(Locale.ROOT))

    /** Canal de la guía para un canal del servidor (o null). */
    private val matchCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun match(ctx: Context, name: String, tvgId: String?, countryHint: String? = null): Chan? {
        val map = chans
        if (map.isEmpty()) return null
        val manual = prefs(ctx).getString(manualKey(name), null)
        if (manual != null) return if (manual == "none") null else map[manual]
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
                .thenByDescending { country != null && countryOf(it.code).startsWith(country) }
                .thenByDescending { it.progs.size.coerceAtMost(20) }
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
            if (ik.length >= 6 && !ik.startsWith("id:") && k.startsWith(ik) && letters.matches(k.substring(ik.length)) && (pre == null || ik.length > pre.length)) pre = ik
        }
        pre?.let { p -> best(idx[p], p)?.let { return it } }
        // "TyC Sports" → "TyC Sports International": solo si hay un único candidato.
        for (k in keys) {
            if (k.length < 6) continue
            val cands = idx.filterKeys { !it.startsWith("id:") && it.startsWith(k) && letters.matches(it.substring(k.length)) }.values.flatten().distinct()
            if (cands.size == 1) return cands[0]
        }
        // Último intento: palabras en común ("FOX SPORTS 2 ARGENTINA" ~ "Fox Sports 2 AR")
        return fuzzy(name, country)
    }

    /** Parecido por palabras (Jaccard); solo si es claro (≥ 0,75) y el número del canal coincide. */
    private fun fuzzy(name: String, country: String?): Chan? {
        val mine = tokens(name).map(::alias).toSet()
        if (mine.isEmpty()) return null
        val digits = mine.filter { t -> t.all { it.isDigit() } }.toSet()
        var best: Chan? = null; var bestScore = 0.0
        for (c in chans.values) {
            if (c.progs.isEmpty()) continue
            for (n in c.names + idFormName(c.id)) {
                val other = tokens(n).map(::alias).toSet()
                if (other.isEmpty()) continue
                if (other.filter { t -> t.all { it.isDigit() } }.toSet() != digits) continue
                var sc = (mine intersect other).size.toDouble() / (mine union other).size
                if (country != null && countryOf(c.code).startsWith(country)) sc += 0.05
                if (sc > bestScore) { bestScore = sc; best = c }
            }
        }
        return best.takeIf { bestScore >= 0.75 }
    }

    /** Para "Elegir guía": las guías más parecidas a este canal, la mejor primero. */
    fun candidates(name: String, limit: Int = 14): List<Chan> {
        val mine = tokens(name).map(::alias).toSet()
        if (mine.isEmpty()) return emptyList()
        return chans.values.asSequence().filter { it.progs.isNotEmpty() }.map { c ->
            val sc = (c.names + idFormName(c.id)).maxOf { n ->
                val o = tokens(n).map(::alias).toSet()
                if (o.isEmpty()) 0.0 else (mine intersect o).size.toDouble() / (mine union o).size
            }
            c to sc
        }.filter { it.second > 0.0 }.sortedByDescending { it.second }.map { it.first }
            .distinctBy { (it.names.firstOrNull() ?: it.id).lowercase(Locale.ROOT) + it.progs.firstOrNull()?.title }
            .take(limit).toList()
    }

    /** Nombre para mostrar de una guía. */
    fun displayName(c: Chan): String = c.names.firstOrNull() ?: idFormName(c.id)

    /** Fuente legible: "epgshare01" u "open-epg". */
    fun sourceName(c: Chan): String = if (c.code.startsWith("OE_")) "open-epg" else "epgshare01"

    /** Clave manual guardada para este canal ("" = automático). */
    fun manualFor(ctx: Context, name: String): String = prefs(ctx).getString(manualKey(name), null).orEmpty()

    /** "E5" / "S1.E4" → "T1 E5". */
    private fun episodeText(system: String, raw: String): String {
        if (raw.isBlank()) return ""
        if (system == "xmltv_ns") {
            val parts = raw.split('.')
            val se = parts.getOrNull(0)?.trim()?.substringBefore('/')?.toIntOrNull()
            val ep = parts.getOrNull(1)?.trim()?.substringBefore('/')?.toIntOrNull()
            return listOfNotNull(se?.let { "T${it + 1}" }, ep?.let { "E${it + 1}" }).joinToString(" ")
        }
        val m = Regex("(?i)s\\s*(\\d+)\\s*[ .:-]?\\s*e\\s*(\\d+)").find(raw)
        if (m != null) return "T${m.groupValues[1].toInt()} E${m.groupValues[2].toInt()}"
        return raw.replace(Regex("(?i)^e\\s*"), "E").take(12)
    }

    /** País al principio del nombre ("MX | Azteca 7" → "MX") para preferir esa guía. */
    private fun tokensPrefix(name: String): String? {
        val m = Regex("^\\s*[|\\[(]?\\s*([A-Za-z]{2,3})\\s*[|:\\-\\])]").find(name) ?: return null
        return m.groupValues[1].uppercase(Locale.ROOT).let { if (it == "COL") "CO" else if (it == "USA") "US" else it }
    }

    /** Programas de un canal entre dos horas, en JSON para la interfaz. */
    fun lookupJson(ctx: Context, name: String, tvgId: String?, fromSec: Long, toSec: Long, countryHint: String? = null): String {
        val c = match(ctx, name, tvgId, countryHint) ?: return ""
        val arr = JSONArray()
        for (p in c.progs) {
            if (p.stop <= fromSec || p.start >= toSec) continue
            arr.put(JSONObject().put("t", p.title).put("d", p.desc).put("s", p.start).put("e", p.stop))
            if (arr.length() >= 40) break
        }
        return JSONObject().put("k", c.key).put("n", c.names.firstOrNull() ?: idFormName(c.id)).put("list", arr).toString()
    }

    fun programs(ctx: Context, name: String, tvgId: String?, fromSec: Long, toSec: Long): List<Prog> {
        val c = match(ctx, name, tvgId) ?: return emptyList()
        return c.progs.filter { it.stop > fromSec && it.start < toSec }
    }

    /** Lista para elegir a mano: canales que tienen programación. */
    fun channelsJson(): String {
        val arr = JSONArray()
        chans.values.filter { it.progs.isNotEmpty() }
            .sortedBy { (it.names.firstOrNull() ?: it.id).lowercase(Locale.ROOT) }
            .forEach { arr.put(JSONObject().put("k", it.key).put("n", it.names.firstOrNull() ?: idFormName(it.id)).put("c", it.code)) }
        return arr.toString()
    }

    /** key = "CODE|id" para fijarlo, "none" para "este canal no tiene guía", "" para volver a lo automático. */
    fun setManual(ctx: Context, name: String, key: String) {
        val e = prefs(ctx).edit()
        if (key.isEmpty()) e.remove(manualKey(name)) else e.putString(manualKey(name), key)
        e.apply()
        matchCache.clear()
    }

    fun status(): String = JSONObject()
        .put("ready", isReady()).put("loading", loading).put("channels", chans.size)
        .put("codes", JSONArray(loadedCodes.toList())).put("error", lastError).put("at", loadedAt).toString()
}
