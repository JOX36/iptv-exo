package com.jox3.iptvexo.tv.player.skip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jox3.iptvexo.NetClient
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.CloudSync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Tiempos de un capítulo (ms del video). -1 = no se sabe; -2 = no tiene (o se reportó mal). */
data class EpSkip(val introStart: Long = -1, val introEnd: Long = -1, val creditsStart: Long = -1, val dur: Long = 0) {
    val hasIntro get() = introStart >= 0 && introEnd > introStart
    val hasCredits get() = creditsStart > 0
}

/** Lo que la interfaz necesita saber del capítulo que se está viendo. */
data class SkipState(
    val epId: String = "",
    val intro: LongRange? = null,
    val creditsAt: Long = -1,
    /** Para el panel de información: qué está pasando ("Aprendiendo…", "Intro detectada"…). */
    val note: String = "",
)

/**
 * Saltar intro y créditos de series, de forma automática.
 *
 * 1. Mientras se ve un capítulo, se saca la huella del audio que va sonando ([SkipTap]).
 * 2. Si otro capítulo de la misma temporada ya se escuchó (en este equipo o en cualquier otro
 *    que use el mismo servidor IPTV), se comparan: el tramo que suena igual es la intro (al
 *    principio) o los créditos (al final). Ese tramo queda como "plantilla".
 * 3. Con una plantilla, en cada capítulo nuevo la intro se reconoce a los ~4 s de empezar y
 *    aparece "Saltar intro" hasta el segundo exacto donde termina. Igual con los créditos:
 *    aparece "Siguiente episodio".
 * 4. Los tiempos y las plantillas se guardan en el servidor de Cloudflare de la app, solo para
 *    quienes usan el mismo servidor IPTV (se identifica con una huella del dominio, nunca con el
 *    usuario ni la contraseña). Un capítulo que ya vio alguien tiene el botón desde el segundo 0.
 */
class SkipDetector(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jox3-skip").apply { priority = Thread.MIN_PRIORITY } }
    private val store = SkipStore(app)

    /** Servidor actual (lo pone la interfaz). */
    @Volatile var account: Account? = null

    var state by mutableStateOf(SkipState()); private set

    /** Escuchar el audio para aprender (Ajustes). Lo ya aprendido se usa igual. */
    var detectEnabled: Boolean
        get() = store.prefs.getBoolean("detect", true)
        set(v) { store.prefs.edit().putBoolean("detect", v).apply() }
    /** Saltar la intro sin preguntar (Ajustes). */
    var autoSkip: Boolean
        get() = store.prefs.getBoolean("auto", false)
        set(v) { store.prefs.edit().putBoolean("auto", v).apply() }

    @Volatile private var gen = 0
    @Volatile private var listening = false
    @Volatile private var armed = false
    @Volatile private var durMs = 0L
    private var cur: Ep? = null   // solo se toca en el hilo [worker]
    private val fetchedAt = HashMap<String, Long>()   // ídem

    private class Ep(
        val gen: Int, val srv: String, val series: String, val season: Int, val id: String,
    ) {
        val phases = Array(4) { Prints() }
        var latest = IntArray(4) { -1 }
        /** latest = el último cuadro producido (tras retroceder, vuelve atrás: es "lo que acaba de sonar"). */
        val fps: Array<Fingerprinter> = Array(4) { p -> Fingerprinter(p) { f, v -> phases[p].put(f, v); latest[p] = f } }
        var res = EpSkip()
        var templates: List<Tpl> = emptyList()
        var badIntro = false
        var badCredits = false
        var triedIntro = false
        var triedCredits = false
        var lastCheckFrame = -1
        var heard = false
    }

    // ── Lo llama el reproductor ──

    /** Empieza un capítulo (o algo que no es capítulo: entonces no hace nada). */
    fun begin(epId: String?, seriesId: String?, season: Int, allowListen: Boolean) {
        val g = ++gen
        armed = false
        durMs = 0
        val acc = account
        val ok = epId != null && !seriesId.isNullOrBlank() && acc != null
        listening = ok && allowListen && detectEnabled
        state = SkipState(epId.orEmpty())
        if (!ok) { worker.execute { if (gen == g) cur = null }; return }
        val srv = srvKey(acc!!.host)
        worker.execute {
            if (gen != g) return@execute
            val ep = Ep(g, srv, seriesId!!, season, epId!!)
            cur = ep
            ep.badIntro = store.isBad(srv, epId, "intro"); ep.badCredits = store.isBad(srv, epId, "credits")
            ep.res = store.result(srv, epId)
            ep.templates = store.templates(srv, seriesId)
            publish(ep, if (ep.res.hasIntro || ep.res.hasCredits) "Tiempos de este capítulo ya conocidos" else "")
            // Lo que saben los demás equipos de este servidor (una vez cada 10 min por serie)
            val key = "$srv|$seriesId"
            val now = System.currentTimeMillis()
            val cloud = if (now - (fetchedAt[key] ?: 0L) > 600_000L) SkipCloud.fetch(srv, seriesId)?.also { fetchedAt[key] = now } else null
            if (gen != g) return@execute
            if (cloud != null) {
                cloud.first.forEach { (id, r) -> store.saveResult(srv, id, r, merge = true) }
                if (cloud.second.isNotEmpty()) store.saveTemplates(srv, seriesId, cloud.second)
                ep.res = store.result(srv, epId)
                ep.templates = store.templates(srv, seriesId)
            }
            run {
                publish(ep, when {
                    ep.res.hasIntro || ep.res.hasCredits -> "Tiempos de este capítulo ya conocidos"
                    ep.templates.isNotEmpty() -> "Esta serie ya se conoce: la intro se reconoce al sonar"
                    else -> "Aprendiendo esta serie: se necesitan 2 capítulos vistos"
                })
            }
        }
    }

    /** Cada medio segundo: posición y duración. */
    fun tick(durationMs: Long) { if (durationMs > 0) durMs = durationMs }

    /** Se cambió de capítulo o se cerró: terminar de analizar lo escuchado. */
    fun end() {
        val g = gen
        val d = durMs     // la duración de ESTE capítulo (begin() la borra para el siguiente)
        listening = false
        worker.execute {
            val ep = cur ?: return@execute
            if (ep.gen != g) return@execute
            runCatching { finishAnalysis(ep, d) }.onFailure { Log.w("JOX3", "Saltos: ${it.message}") }
            if (cur === ep) cur = null
        }
    }

    /** "Esto no era la intro / no eran los créditos": no se vuelve a mostrar en este capítulo. */
    fun reportWrong(kind: String) {
        val g = gen
        worker.execute {
            val ep = cur ?: return@execute
            if (ep.gen != g) return@execute
            store.markBad(ep.srv, ep.id, kind)
            if (kind == "intro") { ep.badIntro = true; ep.res = ep.res.copy(introStart = -2, introEnd = -2) }
            else { ep.badCredits = true; ep.res = ep.res.copy(creditsStart = -2) }
            store.saveResult(ep.srv, ep.id, ep.res, merge = false)
            publish(ep, "Gracias: no se volverá a mostrar en este capítulo")
            SkipCloud.push(ep.srv, ep.series, ep.season, bad = listOf(ep.id to kind))
        }
    }

    // ── Audio (lo llama SkipTap desde el hilo de audio: tiene que ser rápido) ──

    fun onAudio(@Suppress("UNUSED_PARAMETER") rate: Int, startMs: Double, samples: FloatArray, n: Int) {
        if (!listening) return
        if (!armed) { if (startMs < 0) return; armed = true }
        val g = gen
        worker.execute { feed(g, startMs, samples, n) }
    }

    fun onAudioReset() { /* el próximo pedazo trae su tiempo (salto) */ }

    private fun feed(g: Int, startMs: Double, s: FloatArray, n: Int) {
        val ep = cur ?: return
        if (ep.gen != g) return
        ep.heard = true
        if (startMs >= 0) ep.fps.forEach { it.start(startMs) }
        for (i in 0 until n) { val v = s[i]; for (f in ep.fps) f.feed(v) }
        // Revisar cada ~1 s de audio
        val lf = ep.latest[0]
        if (kotlin.math.abs(lf - ep.lastCheckFrame) >= 11) {
            ep.lastCheckFrame = lf
            runCatching { check(ep) }.onFailure { Log.w("JOX3", "Saltos: ${it.message}") }
        }
    }

    // ── Análisis (hilo worker) ──

    private fun introWindowMs(d: Long = durMs): Long = if (d > 0) minOf(INTRO_WIN_MS, (d * 0.4).toLong()) else INTRO_WIN_MS
    private fun creditsWindowMs(d: Long = durMs): Long = if (d > 0) minOf(CREDITS_WIN_MS, (d * 0.3).toLong()) else CREDITS_WIN_MS

    private fun check(ep: Ep) {
        val nowMs = AudioPrint.msOf(ep.latest[0])
        // 1) Ubicar una intro / créditos conocidos en lo que va sonando
        if (!ep.res.hasIntro && ep.res.introStart != -2L && !ep.badIntro && nowMs < introWindowMs() + 240_000) {
            locate(ep, "intro")?.let { (start, end) ->
                if (end > nowMs && end - start in MIN_LEN_MS..MAX_INTRO_MS) {
                    ep.res = ep.res.copy(introStart = start, introEnd = end, dur = durMs)
                    saveAndShare(ep, listOf(ep.id to ep.res))
                    publish(ep, "Intro reconocida")
                }
            }
        }
        val d = durMs
        if (d > 0 && !ep.res.hasCredits && ep.res.creditsStart != -2L && !ep.badCredits && nowMs >= d - creditsWindowMs()) {
            locate(ep, "credits")?.let { (start, _) ->
                if (start > d - creditsWindowMs() - 30_000 && start < d - 5_000) {
                    ep.res = ep.res.copy(creditsStart = start, dur = d)
                    saveAndShare(ep, listOf(ep.id to ep.res))
                    publish(ep, "Créditos reconocidos")
                }
            }
        }
        // 2) Ya se escuchó toda la ventana de la intro: comparar con otros capítulos
        if (!ep.triedIntro && !ep.res.hasIntro && !ep.badIntro && nowMs >= introWindowMs()) {
            ep.triedIntro = true
            analyzeIntro(ep)
        }
    }

    /** Busca los últimos ~4 s en las plantillas; devuelve (inicio, fin) en ms del video. */
    private fun locate(ep: Ep, kind: String): Pair<Long, Long>? {
        val tpls = ep.templates.filter { it.kind == kind }.sortedBy { if (it.season == ep.season) 0 else 1 }
        if (tpls.isEmpty()) return null
        var best: Triple<Double, Long, Long>? = null
        for (t in tpls) for (p in 0 until 4) {
            val lf = ep.latest[p]
            if (lf < SkipMatch.K) continue
            val q = ep.phases[p].slice(lf - SkipMatch.K + 1, lf + 1)
            val r = SkipMatch.locate(t.fp, q) ?: continue
            if (best == null || r.second < best.first) {
                val startFrame = (lf - SkipMatch.K + 1 - r.first) + p / 4.0
                val startMs = (startFrame * AudioPrint.HOP_MS).toLong()
                best = Triple(r.second, startMs, startMs + (t.fp.size * AudioPrint.HOP_MS).toLong())
            }
        }
        return best?.let { it.second to it.third }
    }

    private fun analyzeIntro(ep: Ep, d: Long = durMs) {
        val win = AudioPrint.frames(introWindowMs(d))
        if (ep.phases[0].coverage(0, win) < 0.5f) return
        val mine = Array(4) { ep.phases[it].slice(0, win) }
        val others = candidates(ep, "intro")
        val found = bestPair(mine, others, AudioPrint.frames(MIN_LEN_MS), AudioPrint.frames(MAX_INTRO_MS))
        if (found == null) {
            sharePrint(ep, "intro", 0, mine[0], d)
            publish(ep, "Aprendiendo esta serie: falta otro capítulo para comparar")
            return
        }
        val (seg, p, other) = found
        val myStart = ((seg.aStart + p / 4.0) * AudioPrint.HOP_MS).toLong()
        val myEnd = ((seg.aEnd + p / 4.0) * AudioPrint.HOP_MS).toLong()
        val oStart = AudioPrint.msOf(other.first + seg.bStart); val oEnd = AudioPrint.msOf(other.first + seg.bEnd)
        ep.res = ep.res.copy(introStart = myStart, introEnd = myEnd, dur = d)
        val otherRes = store.result(ep.srv, other.ep).copy(introStart = oStart, introEnd = oEnd)
        val tpl = Tpl("intro", ep.season, other.fp.copyOfRange(seg.bStart, seg.bEnd))
        store.saveTemplates(ep.srv, ep.series, listOf(tpl))
        ep.templates = store.templates(ep.srv, ep.series)
        saveAndShare(ep, listOf(ep.id to ep.res, other.ep to otherRes), listOf(tpl))
        publish(ep, "Intro aprendida: desde el próximo capítulo aparece «Saltar intro»")
    }

    private fun finishAnalysis(ep: Ep, d: Long) {
        if (!ep.heard || d <= 0) return
        val nowMs = AudioPrint.msOf(ep.latest[0])
        if (!ep.triedIntro && !ep.res.hasIntro && !ep.badIntro && nowMs >= introWindowMs(d)) { ep.triedIntro = true; analyzeIntro(ep, d) }
        if (ep.triedCredits || ep.res.hasCredits || ep.badCredits || nowMs < d - 20_000) return
        ep.triedCredits = true
        val from = AudioPrint.frameOf(d - creditsWindowMs(d))
        val to = AudioPrint.frameOf(d)
        if (ep.phases[0].coverage(from, to) < 0.5f) return
        val mine = Array(4) { ep.phases[it].slice(from, to) }
        val others = candidates(ep, "credits")
        val found = bestPair(mine, others, AudioPrint.frames(MIN_LEN_MS), AudioPrint.frames(MAX_CREDITS_MS), latest = true)
            ?.takeIf { (seg, _, _) -> AudioPrint.msOf(from + seg.aEnd) >= d - 150_000 }
        if (found == null) { sharePrint(ep, "credits", from, mine[0], d); return }
        val (seg, p, other) = found
        val myStart = ((from + seg.aStart + p / 4.0) * AudioPrint.HOP_MS).toLong()
        val oStart = AudioPrint.msOf(other.first + seg.bStart)
        ep.res = ep.res.copy(creditsStart = myStart, dur = d)
        val otherRes = store.result(ep.srv, other.ep).copy(creditsStart = oStart, dur = other.dur)
        val tpl = Tpl("credits", ep.season, other.fp.copyOfRange(seg.bStart, seg.bEnd))
        store.saveTemplates(ep.srv, ep.series, listOf(tpl))
        saveAndShare(ep, listOf(ep.id to ep.res, other.ep to otherRes), listOf(tpl))
    }

    /** Huellas de otros capítulos de la temporada (este equipo + el servidor de la app). */
    private fun candidates(ep: Ep, kind: String): List<PrintRec> {
        val local = store.prints(ep.srv, ep.series, ep.season, kind)
        val cloud = SkipCloud.prints(ep.srv, ep.series, ep.season, kind).orEmpty()
        return (local + cloud).filter { it.ep != ep.id && it.fp.size > SkipMatch.W }.distinctBy { it.ep }.take(5)
    }

    /** El mejor tramo común con alguno de los capítulos (se prueban los 4 desfases de la grilla). */
    private fun bestPair(mine: Array<IntArray>, others: List<PrintRec>, minL: Int, maxL: Int, latest: Boolean = false): Triple<SkipMatch.Segment, Int, PrintRec>? {
        // Comparar cuesta (~n×m): se prueba la grilla normal y la desfasada medio cuadro (cubre el peor
        // caso), y al primer capítulo que coincide con un tramo claro (≥ 20 s) se para.
        var best: Triple<SkipMatch.Segment, Int, PrintRec>? = null
        for (o in others.take(3)) {
            for (p in intArrayOf(0, 2)) {
                val s = SkipMatch.common(mine[p], o.fp, minL, maxL, latest) ?: continue
                val prev = best
                if (prev == null || s.length > prev.first.length) best = Triple(s, p, o)
                break
            }
            if ((best?.first?.length ?: 0) >= AudioPrint.frames(20_000)) break
        }
        return best
    }

    private fun sharePrint(ep: Ep, kind: String, first: Int, fp: IntArray, d: Long) {
        val rec = PrintRec(ep.id, kind, ep.season, first, fp, d)
        store.savePrint(ep.srv, ep.series, rec)
        SkipCloud.push(ep.srv, ep.series, ep.season, prints = listOf(rec))
    }

    private fun saveAndShare(ep: Ep, results: List<Pair<String, EpSkip>>, tpls: List<Tpl> = emptyList()) {
        results.forEach { (id, r) -> store.saveResult(ep.srv, id, r, merge = true) }
        SkipCloud.push(ep.srv, ep.series, ep.season, results = results, tpls = tpls)
    }

    private fun publish(ep: Ep, note: String) {
        val r = ep.res
        val s = SkipState(
            epId = ep.id,
            intro = if (r.hasIntro && !ep.badIntro) r.introStart..r.introEnd else null,
            creditsAt = if (r.hasCredits && !ep.badCredits) r.creditsStart else -1,
            note = note,
        )
        val g = ep.gen
        main.post { if (gen == g) state = s }
    }

    companion object {
        const val INTRO_WIN_MS = 600_000L      // la intro se busca en los primeros 10 min
        const val CREDITS_WIN_MS = 360_000L    // los créditos, en los últimos 6 min
        const val MIN_LEN_MS = 15_000L         // menos de 15 s no es una intro (logos, cortinillas)
        const val MAX_INTRO_MS = 200_000L
        const val MAX_CREDITS_MS = 360_000L

        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("jox3_skip", Context.MODE_PRIVATE)
        /** Ajustes (también se cambian desde el reproductor). */
        fun autoSkip(ctx: Context) = prefs(ctx).getBoolean("auto", false)
        fun setAutoSkip(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto", v).apply() }
        fun detect(ctx: Context) = prefs(ctx).getBoolean("detect", true)
        fun setDetect(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("detect", v).apply() }

        /** Huella del servidor IPTV (lo que agrupa a quienes comparten tiempos). Nunca el usuario ni la clave. */
        fun srvKey(host: String): String {
            val h = host.trim().lowercase().removeSuffix("/").removePrefix("http://").removePrefix("https://")
            val d = MessageDigest.getInstance("SHA-256").digest("jox3skip|$h".toByteArray())
            return d.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}

/** Plantilla: la huella de la intro o de los créditos de una temporada. */
class Tpl(val kind: String, val season: Int, val fp: IntArray) {
    val id: String get() = MessageDigest.getInstance("SHA-1").digest(Prints.encode(fp).toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}

/** Huella de la ventana de intro o de créditos de un capítulo (para compararla con otro). */
class PrintRec(val ep: String, val kind: String, val season: Int, val first: Int, val fp: IntArray, val dur: Long)

/** Lo guardado en este equipo (para no depender de internet y responder al instante). */
internal class SkipStore(ctx: Context) {
    val prefs = ctx.getSharedPreferences("jox3_skip", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "skip")

    fun result(srv: String, ep: String): EpSkip {
        val s = prefs.getString("r|$srv|$ep", null) ?: return EpSkip()
        val p = s.split(",").map { it.toLongOrNull() ?: -1 }
        return EpSkip(p.getOrElse(0) { -1 }, p.getOrElse(1) { -1 }, p.getOrElse(2) { -1 }, p.getOrElse(3) { 0 })
    }

    fun saveResult(srv: String, ep: String, r: EpSkip, merge: Boolean) {
        val old = result(srv, ep)
        val n = if (merge) EpSkip(
            if (r.introStart != -1L) r.introStart else old.introStart,
            if (r.introEnd != -1L) r.introEnd else old.introEnd,
            if (r.creditsStart != -1L) r.creditsStart else old.creditsStart,
            if (r.dur > 0) r.dur else old.dur,
        ) else r
        // Lo que se reportó mal no lo pisa lo que llegue de otro lado
        val keep = n.copy(
            introStart = if (old.introStart == -2L && merge) -2 else n.introStart,
            introEnd = if (old.introEnd == -2L && merge) -2 else n.introEnd,
            creditsStart = if (old.creditsStart == -2L && merge) -2 else n.creditsStart,
        )
        prefs.edit().putString("r|$srv|$ep", "${keep.introStart},${keep.introEnd},${keep.creditsStart},${keep.dur}").apply()
    }

    fun isBad(srv: String, ep: String, kind: String) = prefs.getBoolean("b|$srv|$ep|$kind", false)
    fun markBad(srv: String, ep: String, kind: String) { prefs.edit().putBoolean("b|$srv|$ep|$kind", true).apply() }

    private fun tplFile(srv: String, series: String) = File(printDir(srv, series), "plantillas.json")

    /** Plantillas de la serie (en un archivo: pueden pesar decenas de KB). */
    fun templates(srv: String, series: String): List<Tpl> = runCatching {
        val f = tplFile(srv, series)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Tpl(it.getString("k"), it.optInt("s"), Prints.decode(it.getString("fp"))) }
            .filter { it.fp.size >= SkipMatch.K }
    }.getOrDefault(emptyList())

    fun saveTemplates(srv: String, series: String, add: List<Tpl>) {
        val all = (add + templates(srv, series)).distinctBy { it.kind + it.season + it.id }
            .groupBy { it.kind + "|" + it.season }.values.flatMap { it.take(3) }
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("k", it.kind).put("s", it.season).put("fp", Prints.encode(it.fp))) }
        runCatching { tplFile(srv, series).apply { parentFile?.mkdirs() }.writeText(a.toString()) }
    }

    private fun printDir(srv: String, series: String) = File(dir, "$srv/${series.filter { it.isLetterOrDigit() }}")

    fun prints(srv: String, series: String, season: Int, kind: String): List<PrintRec> = runCatching {
        printDir(srv, series).listFiles { f -> f.name.startsWith("${kind}_${season}_") }.orEmpty()
            .sortedByDescending { it.lastModified() }.take(5).mapNotNull { f ->
                val o = JSONObject(f.readText())
                PrintRec(o.getString("ep"), kind, season, o.optInt("first"), Prints.decode(o.getString("fp")), o.optLong("dur"))
            }
    }.getOrDefault(emptyList())

    fun savePrint(srv: String, series: String, r: PrintRec) {
        runCatching {
            val d = printDir(srv, series).apply { mkdirs() }
            File(d, "${r.kind}_${r.season}_${r.ep.filter { it.isLetterOrDigit() }}.json").writeText(
                JSONObject().put("ep", r.ep).put("first", r.first).put("dur", r.dur).put("fp", Prints.encode(r.fp)).toString())
            // No guardar más de 6 por temporada y tipo
            d.listFiles { f -> f.name.startsWith("${r.kind}_${r.season}_") }.orEmpty()
                .sortedByDescending { it.lastModified() }.drop(6).forEach { it.delete() }
        }
    }
}

/** Servidor de la app (Cloudflare): tiempos y huellas compartidos por servidor IPTV. */
internal object SkipCloud {
    private val JSON = "application/json".toMediaType()
    private fun client() = NetClient.api()

    fun fetch(srv: String, series: String): Pair<Map<String, EpSkip>, List<Tpl>>? = runCatching {
        val req = Request.Builder().url("${CloudSync.SERVER}/v1/skip?s=$srv&series=${enc(series)}").build()
        client().newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body?.string().orEmpty())
            val eps = HashMap<String, EpSkip>()
            o.optJSONArray("eps")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    eps[e.getString("ep")] = EpSkip(e.optLong("is", -1), e.optLong("ie", -1), e.optLong("cs", -1), e.optLong("d", 0))
                }
            }
            val tpl = ArrayList<Tpl>()
            o.optJSONArray("tpl")?.let { a ->
                for (i in 0 until a.length()) {
                    val t = a.getJSONObject(i)
                    tpl += Tpl(t.getString("kind"), t.optInt("season"), Prints.decode(t.getString("fp")))
                }
            }
            eps to tpl
        }
    }.getOrNull()

    fun prints(srv: String, series: String, season: Int, kind: String): List<PrintRec>? = runCatching {
        val req = Request.Builder().url("${CloudSync.SERVER}/v1/skip/prints?s=$srv&series=${enc(series)}&season=$season&kind=$kind").build()
        client().newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("prints") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                PrintRec(it.getString("ep"), kind, season, it.optInt("first"), Prints.decode(it.getString("fp")), it.optLong("d"))
            }
        }
    }.getOrNull()

    fun push(
        srv: String, series: String, season: Int,
        results: List<Pair<String, EpSkip>> = emptyList(), tpls: List<Tpl> = emptyList(),
        prints: List<PrintRec> = emptyList(), bad: List<Pair<String, String>> = emptyList(),
    ) {
        runCatching {
            val body = JSONObject().put("s", srv).put("series", series).put("season", season)
            body.put("eps", JSONArray().apply {
                results.forEach { (id, r) ->
                    put(JSONObject().put("ep", id).put("is", r.introStart).put("ie", r.introEnd).put("cs", r.creditsStart).put("d", r.dur))
                }
            })
            body.put("tpl", JSONArray().apply { tpls.forEach { put(JSONObject().put("kind", it.kind).put("season", it.season).put("fp", Prints.encode(it.fp))) } })
            body.put("prints", JSONArray().apply {
                prints.forEach { put(JSONObject().put("ep", it.ep).put("kind", it.kind).put("first", it.first).put("d", it.dur).put("fp", Prints.encode(it.fp))) }
            })
            body.put("bad", JSONArray().apply { bad.forEach { (ep, k) -> put(JSONObject().put("ep", ep).put("kind", k)) } })
            val req = Request.Builder().url("${CloudSync.SERVER}/v1/skip").post(body.toString().toRequestBody(JSON)).build()
            client().newCall(req).execute().use { }
        }.onFailure { Log.w("JOX3", "Saltos: no se pudo compartir: ${it.message}") }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
