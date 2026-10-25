package com.jox3.tvdesktop.data

import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Guía completa del servidor IPTV (xmltv.php): todos los canales y varios días, en un solo
 * archivo. Es la más exacta (usa el mismo código de canal que el servidor) y evita preguntar
 * canal por canal. Si el servidor no la tiene, se usan las otras (guía corta y de internet).
 * Misma lógica que la app Android (tv/data/ServerEpg.kt).
 *
 * Solo se guardan los canales que existen en el servidor y los programas de las próximas ~36 horas.
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
    fun ensure(api: XtreamApi, ids: () -> Set<String>, onReady: () -> Unit) {
        val acc = api.account
        if (data.containsKey(acc.key) || !loading.add(acc.key)) return
        if (System.currentTimeMillis() - (failedAt[acc.key] ?: 0L) < 30 * 60_000) { loading.remove(acc.key); return }
        Thread {
            try {
                val want = ids().map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
                if (want.isEmpty()) { failedAt[acc.key] = System.currentTimeMillis(); return@Thread }
                val dir = File(AppDirs.data, "epg").apply { mkdirs() }
                val f = File(dir, "srv_" + LibraryStore.keyHash(acc.key) + ".xml")
                if (!(f.exists() && f.length() > 500 && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS)) download(api, f)
                if (!f.exists() || f.length() < 500) { failedAt[acc.key] = System.currentTimeMillis(); return@Thread }
                val parsed = parse(f, want)
                if (parsed.isEmpty()) { failedAt[acc.key] = System.currentTimeMillis(); return@Thread }
                data[acc.key] = parsed
                Log.i("Guía del servidor: ${parsed.size} canales")
                onReady()
            } catch (e: Throwable) {
                lastError = e.message ?: "error"
                failedAt[acc.key] = System.currentTimeMillis()
                Log.w("Guía del servidor: no se pudo leer (${e.message})")
            } finally { loading.remove(acc.key) }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true; name = "epg-servidor" }.start()
    }

    private fun download(api: XtreamApi, f: File) {
        val tmp = File(f.parentFile, f.name + ".part")
        try {
            val req = Request.Builder().url(api.xmltvUrl()).header("User-Agent", Net.API_USER_AGENT).build()
            Net.api.newBuilder().readTimeout(90, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
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
            Log.i("Guía del servidor no disponible: ${e.message}")
        }
    }

    private fun open(f: File): InputStream {
        val bis = BufferedInputStream(FileInputStream(f), 64 * 1024)
        bis.mark(2)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 64 * 1024) else bis
    }

    internal fun parse(f: File, want: Set<String>): Map<String, List<ExternalEpg.Prog>> {
        val now = System.currentTimeMillis() / 1000
        val from = now - 4 * 3600
        val to = now + 36 * 3600
        val factory = XMLInputFactory.newInstance().apply {
            // Seguridad: sin entidades externas
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        val out = HashMap<String, ArrayList<ExternalEpg.Prog>>()
        open(f).use { input ->
            val r = factory.createXMLStreamReader(input)
            try {
                var inProg = false; var keep = false
                var ch = ""; var start = 0L; var stop = 0L
                var title = ""; var desc = ""; var icon = ""; var cat = ""; var live = false; var isNew = false; var ep = ""
                while (r.hasNext()) {
                    when (r.next()) {
                        XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                            "programme" -> {
                                ch = r.getAttributeValue(null, "channel")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                                start = ExternalEpg.xmltvTime(r.getAttributeValue(null, "start"))
                                stop = ExternalEpg.xmltvTime(r.getAttributeValue(null, "stop"))
                                keep = ch in want && stop > from && start < to && stop > start
                                inProg = true
                                title = ""; desc = ""; icon = ""; cat = ""; live = false; isNew = false; ep = ""
                            }
                            "title" -> if (inProg && keep && title.isEmpty()) title = r.elementText.trim()
                            "desc" -> if (inProg && keep && desc.isEmpty()) desc = r.elementText.trim().take(400)
                            "icon" -> if (inProg && keep && icon.isEmpty())
                                icon = r.getAttributeValue(null, "src").orEmpty().takeIf { it.startsWith("http") }.orEmpty().take(300)
                            "category" -> if (inProg && keep && cat.isEmpty()) cat = r.elementText.trim().take(40)
                            "live" -> if (inProg) live = true
                            "new", "premiere" -> if (inProg) isNew = true
                            "episode-num" -> if (inProg && keep && ep.isEmpty()) {
                                val sys = r.getAttributeValue(null, "system").orEmpty()
                                ep = ExternalEpg.episodeText(sys, r.elementText.trim())
                            }
                        }
                        XMLStreamConstants.END_ELEMENT -> if (r.localName == "programme") {
                            if (keep && title.isNotEmpty()) {
                                out.getOrPut(ch) { ArrayList() }.add(ExternalEpg.Prog(start, stop, title, desc, icon, cat, live, isNew, ep))
                            }
                            inProg = false; keep = false
                        }
                    }
                }
            } finally { r.close() }
        }
        out.values.forEach { l -> l.sortBy { it.start } }
        return out
    }
}
