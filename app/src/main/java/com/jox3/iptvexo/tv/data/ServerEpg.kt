package com.jox3.iptvexo.tv.data

import android.content.Context
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Guía completa del servidor IPTV (xmltv.php): todos los canales y varios días, en un solo
 * archivo. Es la más exacta (usa el mismo código de canal que el servidor) y evita preguntar
 * canal por canal. Si el servidor no la tiene, se usan las otras (guía corta y de internet).
 *
 * Para no gastar memoria en el TV Box solo se guardan los canales que existen en el servidor
 * y los programas de las próximas ~36 horas.
 */
object ServerEpg {
    private const val MAX_AGE_MS = 12L * 3600 * 1000
    private const val MAX_BYTES = 180L * 1024 * 1024

    /** cuenta → (código de guía en minúsculas → programas) */
    private val data = ConcurrentHashMap<String, Map<String, List<ExternalEpg.Prog>>>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    @Volatile var lastError = ""

    fun isReady(acc: Account) = data.containsKey(acc.key)

    /** Programas del canal (por su epg_channel_id), o null si el servidor no tiene guía completa. */
    fun lookup(acc: Account, epgId: String): List<ExternalEpg.Prog>? {
        if (epgId.isBlank()) return null
        return data[acc.key]?.get(epgId.trim().lowercase(Locale.ROOT))
    }

    /**
     * Descarga (si hace falta) y lee la guía completa de esta cuenta en segundo plano.
     * [ids]: códigos de guía de los canales del servidor (solo esos se guardan).
     */
    fun ensure(ctx: Context, api: XtreamApi, ids: () -> Set<String>, onReady: () -> Unit) {
        val acc = api.account
        if (data.containsKey(acc.key) || !loading.add(acc.key)) return
        if (System.currentTimeMillis() - (failedAt[acc.key] ?: 0L) < 30 * 60_000) { loading.remove(acc.key); return }
        val app = ctx.applicationContext
        Thread {
            try {
                val want = ids().map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
                if (want.isEmpty()) { failedAt[acc.key] = System.currentTimeMillis(); return@Thread }
                val dir = File(app.filesDir, "epg").apply { mkdirs() }
                val f = File(dir, "srv_" + acc.key.hashCode().toString().replace('-', 'm') + ".xml")
                if (!(f.exists() && f.length() > 500 && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS)) download(api, f)
                if (!f.exists() || f.length() < 500) { failedAt[acc.key] = System.currentTimeMillis(); return@Thread }
                val parsed = parse(f, want)
                if (parsed.isEmpty()) { failedAt[acc.key] = System.currentTimeMillis(); return@Thread }
                data[acc.key] = parsed
                Log.i("JOX3", "Guía del servidor: ${parsed.size} canales")
                onReady()
            } catch (e: Throwable) {
                lastError = e.message ?: "error"
                failedAt[acc.key] = System.currentTimeMillis()
            } finally { loading.remove(acc.key) }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private fun download(api: XtreamApi, f: File) {
        val tmp = File(f.parentFile, f.name + ".part")
        try {
            val req = okhttp3.Request.Builder().url(api.xmltvUrl()).header("User-Agent", com.jox3.iptvexo.NetClient.API_USER_AGENT).build()
            com.jox3.iptvexo.NetClient.api().newBuilder()
                .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val body = r.body ?: throw RuntimeException("sin contenido")
                    body.byteStream().use { inp ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var total = 0L
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw RuntimeException("guía demasiado grande")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            if (tmp.length() < 500) throw RuntimeException("guía vacía")
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            tmp.delete()
            lastError = e.message ?: "error"
        }
    }

    private fun open(f: File): InputStream {
        val bis = BufferedInputStream(FileInputStream(f), 64 * 1024)
        bis.mark(2)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 64 * 1024) else bis
    }

    private fun parse(f: File, want: Set<String>): Map<String, List<ExternalEpg.Prog>> {
        val now = System.currentTimeMillis() / 1000
        val from = now - 4 * 3600
        val to = now + 36 * 3600
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun time(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            val t = s.trim()
            return try { (if (t.length > 14) fmt.parse(t) else fmtNoTz.parse(t.take(14)))?.time?.div(1000) ?: 0 } catch (e: Exception) { 0 }
        }
        val out = HashMap<String, ArrayList<ExternalEpg.Prog>>()
        open(f).use { input ->
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(input, null)
            var inProg = false; var keep = false
            var ch = ""; var start = 0L; var stop = 0L
            var title = ""; var desc = ""; var icon = ""; var cat = ""; var live = false; var isNew = false; var ep = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            ch = p.getAttributeValue(null, "channel")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                            start = time(p.getAttributeValue(null, "start")); stop = time(p.getAttributeValue(null, "stop"))
                            keep = ch in want && stop > from && start < to && stop > start
                            inProg = true
                            title = ""; desc = ""; icon = ""; cat = ""; live = false; isNew = false; ep = ""
                        }
                        "title" -> if (inProg && keep && title.isEmpty()) title = p.nextText().trim()
                        "desc" -> if (inProg && keep && desc.isEmpty()) desc = p.nextText().trim().take(400)
                        "icon" -> if (inProg && keep && icon.isEmpty()) icon = p.getAttributeValue(null, "src").orEmpty().takeIf { it.startsWith("http") }.orEmpty().take(300)
                        "category" -> if (inProg && keep && cat.isEmpty()) cat = p.nextText().trim().take(40)
                        "live" -> if (inProg) live = true
                        "new", "premiere" -> if (inProg) isNew = true
                        "episode-num" -> if (inProg && keep && ep.isEmpty()) {
                            val sys = p.getAttributeValue(null, "system").orEmpty()
                            val raw = p.nextText().trim()
                            ep = if (sys == "xmltv_ns") {
                                val parts = raw.split('.')
                                listOfNotNull(parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.let { "T${it + 1}" },
                                    parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.let { "E${it + 1}" }).joinToString(" ")
                            } else raw.take(12)
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                    if (keep && title.isNotEmpty()) {
                        out.getOrPut(ch) { ArrayList() }.add(ExternalEpg.Prog(start, stop, title, desc, icon, cat, live, isNew, ep))
                    }
                    inProg = false; keep = false
                }
                ev = p.next()
            }
        }
        out.values.forEach { l -> l.sortBy { it.start } }
        return out
    }
}
