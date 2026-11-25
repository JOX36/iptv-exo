package com.jox3.tvdesktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import com.jox3.tvdesktop.core.Settings
import com.jox3.tvdesktop.data.CloudSync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/** Tiempos de un capítulo (ms del video). -1 = no se sabe; -2 = no tiene (o se reportó mal). Igual que Android. */
data class EpSkip(val introStart: Long = -1, val introEnd: Long = -1, val creditsStart: Long = -1, val dur: Long = 0) {
    val hasIntro get() = introStart >= 0 && introEnd > introStart
    val hasCredits get() = creditsStart > 0
}

/** Lo que la interfaz necesita saber del capítulo que se está viendo. */
data class SkipState(val epId: String = "", val intro: LongRange? = null, val creditsAt: Long = -1, val note: String = "")

/**
 * "Saltar intro" y "Siguiente episodio" en series, con lo que aprende la app Android.
 *
 * La app Android escucha el audio de los capítulos y reconoce la intro y los créditos; los tiempos
 * de cada capítulo quedan en el servidor de la app (por servidor IPTV, identificado con una huella
 * del dominio, nunca con usuario ni contraseña). En PC se USAN esos tiempos con el mismo formato:
 * si alguien ya vio ese capítulo en el TV Box o el celular, aquí aparece el botón desde el segundo 0.
 *
 * En PC no se escucha el audio para aprender: VLC no deja leer el sonido sin quitárselo a los
 * parlantes (habría que reproducirlo aparte, con riesgo de cortes y desfase).
 * Los créditos se estiman igual que en Android cuando en otros capítulos duran siempre lo mismo.
 */
object Skips {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jox3-skip").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val file get() = File(AppDirs.data, "saltos.json")
    private var cache: JSONObject? = null           // solo en [worker]
    private val fetchedAt = HashMap<String, Long>()  // ídem

    var state by mutableStateOf(SkipState()); private set

    /** Saltar la intro sin preguntar (se cambia desde el reproductor). */
    var autoSkip: Boolean
        get() = Settings.bool("skip.auto", false)
        set(v) { Settings.put("skip.auto", v) }

    @Volatile private var gen = 0
    @Volatile private var cur: Ep? = null
    @Volatile private var durMs = 0L

    private class Ep(val gen: Int, val srv: String, val series: String, val season: Int, val id: String, val name: String) {
        @Volatile var res = EpSkip()
        @Volatile var tails: List<Long> = emptyList()
        @Volatile var predicted = -1L
        @Volatile var badIntro = false
        @Volatile var badCredits = false
    }

    /** Empieza un capítulo (o algo que no es capítulo: [epId] null → no hace nada). */
    fun begin(host: String?, epId: String?, seriesId: String?, season: Int, seriesName: String) {
        val g = ++gen
        durMs = 0
        cur = null
        setState(g, SkipState(epId.orEmpty()))
        if (host.isNullOrBlank() || epId.isNullOrBlank() || seriesId.isNullOrBlank()) return
        val ep = Ep(g, srvKey(host), seriesId, season, epId, normName(seriesName))
        cur = ep
        worker.execute {
            if (gen != g) return@execute
            load(ep)
            publish(ep, if (ep.res.hasIntro || ep.res.hasCredits) "Tiempos de este capítulo ya conocidos" else "")
            // Lo que aprendieron los demás equipos con este servidor (una vez cada 10 min por serie)
            val key = "${ep.srv}|${ep.series}"
            val now = System.currentTimeMillis()
            if (now - (fetchedAt[key] ?: 0L) > 600_000L) {
                val eps = fetch(ep.srv, ep.series, ep.name)
                if (eps != null) {
                    fetchedAt[key] = now
                    eps.forEach { (id, r) -> saveResult(ep.srv, id, r, merge = true) }
                    saveTails(ep.srv, ep.series, eps.values.filter { it.hasCredits && it.dur > it.creditsStart }.map { it.dur - it.creditsStart })
                    flush()
                    if (gen != g) return@execute
                    load(ep)
                }
            }
            publish(ep, when {
                ep.res.hasIntro || ep.res.hasCredits -> "Tiempos de este capítulo ya conocidos"
                else -> "Aún no se conocen: se aprenden al ver la serie en la app de TV o celular"
            })
            predict(ep)
        }
    }

    /** Duración del capítulo (la llama el reproductor). */
    fun tick(durationMs: Long) {
        if (durationMs <= 0 || durationMs == durMs) return
        durMs = durationMs
        val ep = cur ?: return
        worker.execute { if (ep.gen == gen) predict(ep) }
    }

    /** Se cerró o cambió el capítulo. */
    fun end() { ++gen; cur = null; durMs = 0; SwingUtilities.invokeLater { state = SkipState() } }

    /** "Esto no era la intro / no eran los créditos": no se vuelve a mostrar en este capítulo y se avisa a todos. */
    fun reportWrong(kind: String) {
        val ep = cur ?: return
        worker.execute {
            cacheObj().put("b|${ep.srv}|${ep.id}|$kind", true)
            if (kind == "intro") { ep.badIntro = true; ep.res = ep.res.copy(introStart = -2, introEnd = -2) }
            else { ep.badCredits = true; ep.predicted = -1; ep.res = ep.res.copy(creditsStart = -2) }
            saveResult(ep.srv, ep.id, ep.res, merge = false)
            flush()
            if (ep.gen == gen) publish(ep, "Gracias: no se volverá a mostrar en este capítulo")
            push(ep, bad = listOf(ep.id to kind))
        }
    }

    // ── Internos (hilo [worker]) ──

    private fun load(ep: Ep) {
        ep.badIntro = cacheObj().optBoolean("b|${ep.srv}|${ep.id}|intro")
        ep.badCredits = cacheObj().optBoolean("b|${ep.srv}|${ep.id}|credits")
        ep.res = result(ep.srv, ep.id)
        ep.tails = tails(ep.srv, ep.series)
    }

    /** Créditos estimados: si en otros capítulos duran siempre lo mismo (±4 s). Igual que Android. */
    private fun predict(ep: Ep) {
        val d = durMs
        if (d <= 0 || ep.predicted >= 0 || ep.res.hasCredits || ep.res.creditsStart == -2L || ep.badCredits || ep.tails.size < 3) return
        val t = ep.tails.sorted()
        if (t.last() - t.first() > 8_000) return
        ep.predicted = d - t[t.size / 2]
        if (ep.gen == gen) publish(ep, "Créditos estimados por los otros capítulos")
    }

    private fun publish(ep: Ep, note: String) {
        val r = ep.res
        setState(ep.gen, SkipState(
            epId = ep.id,
            intro = if (r.hasIntro && !ep.badIntro) r.introStart..r.introEnd else null,
            creditsAt = when { ep.badCredits -> -1; r.hasCredits -> r.creditsStart; else -> ep.predicted },
            note = note,
        ))
    }

    private fun setState(g: Int, s: SkipState) = SwingUtilities.invokeLater { if (gen == g) state = s }

    private fun cacheObj(): JSONObject = cache ?: (SafeFile.read(file) { JSONObject(it) } ?: JSONObject()).also { cache = it }
    private fun flush() {
        val o = cacheObj()
        // No crece sin fin: se queda con lo más nuevo (las claves se agregan en orden)
        if (o.length() > 6000) o.keys().asSequence().toList().take(o.length() - 5000).forEach { o.remove(it) }
        try { SafeFile.write(file, o.toString()) } catch (e: Exception) { Log.w("Saltos: no se pudo guardar", e) }
    }

    private fun result(srv: String, ep: String): EpSkip {
        val p = cacheObj().optString("r2|$srv|$ep").ifEmpty { return EpSkip() }.split(",").map { it.toLongOrNull() ?: -1 }
        return EpSkip(p.getOrElse(0) { -1 }, p.getOrElse(1) { -1 }, p.getOrElse(2) { -1 }, p.getOrElse(3) { 0 })
    }

    /** Mismas reglas que Android: lo que se reportó mal no lo pisa lo que llegue de otro lado. */
    internal fun merge(old: EpSkip, r: EpSkip, merge: Boolean): EpSkip {
        val n = if (merge) EpSkip(
            if (r.introStart != -1L) r.introStart else old.introStart,
            if (r.introEnd != -1L) r.introEnd else old.introEnd,
            if (r.creditsStart != -1L) r.creditsStart else old.creditsStart,
            if (r.dur > 0) r.dur else old.dur,
        ) else r
        return n.copy(
            introStart = if (old.introStart == -2L && merge) -2 else n.introStart,
            introEnd = if (old.introEnd == -2L && merge) -2 else n.introEnd,
            creditsStart = if (old.creditsStart == -2L && merge) -2 else n.creditsStart,
        )
    }

    private fun saveResult(srv: String, ep: String, r: EpSkip, merge: Boolean) {
        val k = merge(result(srv, ep), r, merge)
        val key = "r2|$srv|$ep"
        cacheObj().remove(key)
        cacheObj().put(key, "${k.introStart},${k.introEnd},${k.creditsStart},${k.dur}")
    }

    private fun tails(srv: String, series: String): List<Long> =
        cacheObj().optString("tl|$srv|$series").split(",").mapNotNull { it.toLongOrNull() }

    private fun saveTails(srv: String, series: String, add: List<Long>) {
        val ok = add.filter { it in 5_000L..400_000L }
        if (ok.isEmpty()) return
        cacheObj().put("tl|$srv|$series", (ok + tails(srv, series)).distinct().take(12).joinToString(","))
    }

    // ── Servidor de la app (mismo formato que Android: /v2/skip) ──

    private fun fetch(srv: String, series: String, name: String): Map<String, EpSkip>? = runCatching {
        val q = "s=$srv&series=${enc(series)}" + if (name.isNotBlank()) "&name=${enc(name)}" else ""
        val req = Request.Builder().url("${CloudSync.SERVER}/v2/skip?$q").build()
        CloudSync.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            parseEps(JSONObject(r.body?.string().orEmpty()))
        }
    }.onFailure { Log.w("Saltos: no se pudo consultar: ${it.message}") }.getOrNull()

    internal fun parseEps(o: JSONObject): Map<String, EpSkip> {
        val eps = HashMap<String, EpSkip>()
        val a = o.optJSONArray("eps") ?: return eps
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            val id = e.optString("ep")
            if (id.isEmpty()) continue
            eps[id] = EpSkip(e.optLong("is", -1), e.optLong("ie", -1), e.optLong("cs", -1), e.optLong("d", 0))
        }
        return eps
    }

    private fun push(ep: Ep, bad: List<Pair<String, String>>) {
        runCatching {
            val body = JSONObject().put("s", ep.srv).put("series", ep.series).put("season", ep.season).put("name", ep.name)
                .put("eps", JSONArray()).put("tpl", JSONArray()).put("prints", JSONArray())
                .put("bad", JSONArray().apply { bad.forEach { (id, k) -> put(JSONObject().put("ep", id).put("kind", k)) } })
            val req = Request.Builder().url("${CloudSync.SERVER}/v2/skip").post(body.toString().toRequestBody("application/json".toMediaType())).build()
            CloudSync.http.newCall(req).execute().use { }
        }.onFailure { Log.w("Saltos: no se pudo avisar: ${it.message}") }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * Nombre de serie comparable entre servidores (igual que Android): "Los Simpson (1989) [LAT] 4K"
     * y "THE SIMPSONS" → "simpson".
     */
    fun normName(raw: String): String {
        var x = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        x = x.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}"), " ")
        x = x.replace(Regex("^\\s*[a-z]{2,3}\\s*[:|]\\s*"), " ")
        x = x.replace(Regex("[^a-z0-9]+"), " ")
        val drop = setOf("the", "los", "las", "el", "la", "le", "les", "un", "una", "a", "4k", "uhd", "fhd", "hd", "sd", "hevc",
            "h264", "h265", "x264", "x265", "latino", "lat", "castellano", "cast", "espanol", "spanish", "sub", "subs",
            "subtitulado", "subtitulada", "dual", "vose", "multi", "temporada", "season", "serie", "series", "tv")
        return x.split(' ').filter { it.isNotBlank() && it !in drop && !Regex("(19|20)\\d\\d|s\\d{1,2}|t\\d{1,2}").matches(it) }
            .joinToString(" ") { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }.take(80)
    }

    /** Huella del servidor IPTV (igual que Android). Nunca el usuario ni la clave. */
    fun srvKey(host: String): String {
        val h = host.trim().lowercase().removeSuffix("/").removePrefix("http://").removePrefix("https://")
        val d = MessageDigest.getInstance("SHA-256").digest("jox3skip|$h".toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }
}
