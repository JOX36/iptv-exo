package com.jox3.iptvexo.tv.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Lo mejor de hoy" (Gemini elige lo más atractivo de la guía del día) y
 * "Avísame cuando juegue…" (la app vigila la guía y avisa cuando empieza).
 * Lo que sale en la guía se busca en el servidor actual para poder ponerlo con un OK.
 */
object Today {
    /** Un programa destacado o avisado, con el canal del servidor donde verlo. */
    data class Pick(
        val title: String, val guide: String, val start: Long, val stop: Long,
        val why: String, val channel: Channel,
    ) {
        val liveNow get() = System.currentTimeMillis() / 1000 in start until stop
        fun whenText(): String {
            val now = System.currentTimeMillis() / 1000
            return when {
                now in start until stop -> "EN VIVO"
                start - now < 3600 -> "En ${((start - now) / 60).coerceAtLeast(1)} min"
                else -> SimpleDateFormat("h:mm a", Locale("es")).format(Date(start * 1000))
            }
        }
    }

    // ── Buscar en el servidor el canal de una guía ──

    /** El canal del servidor actual que corresponde a [chan] de la guía (null si no lo tiene). */
    suspend fun channelFor(ctx: Context, repo: TvRepo, chan: ExternalEpg.Chan): Channel? {
        val idx = repo.peekIndex() ?: runCatching { repo.searchIndex() }.getOrNull() ?: return null
        val me = repo.api.account
        val hits = ServerHub.search(ExternalEpg.displayName(chan), me, idx, listOf(me), null, includeOthers = false).channels
        val hit = withContext(Dispatchers.Default) {
            hits.take(8).firstOrNull { h -> ExternalEpg.match(ctx, h.name, null)?.key == chan.key }
        } ?: return null
        val c = hit.copies.firstOrNull { it.account.key == me.key } ?: return null
        return Channel(c.id, c.name, c.image, c.categoryId, 1)
    }

    // ── Lo mejor de hoy ──

    val picks = mutableStateOf<List<Pick>>(emptyList())
    @Volatile private var loadedFor = ""
    @Volatile private var loadedAt = 0L

    fun enabled(ctx: Context) = ctx.getSharedPreferences("jox3_ai", Context.MODE_PRIVATE).getBoolean("today", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("jox3_ai", Context.MODE_PRIVATE).edit().putBoolean("today", on).apply()

    /** Arma la fila (una vez por hora por servidor). Necesita la guía de internet descargada. */
    suspend fun load(ctx: Context, repo: TvRepo) {
        if (!enabled(ctx) || !ExternalEpg.isReady()) return
        val acc = repo.api.account.key
        if (loadedFor == acc && System.currentTimeMillis() - loadedAt < 3600_000 && picks.value.isNotEmpty()) return
        val now = System.currentTimeMillis() / 1000
        // Programas de hoy que valen la pena mirar (ya empezados hace poco o por empezar)
        class C(val chan: ExternalEpg.Chan, val p: ExternalEpg.Prog, val line: String)
        val fmt = SimpleDateFormat("h:mm a", Locale("es"))
        val cands = withContext(Dispatchers.Default) {
            val out = ArrayList<C>()
            for (c in ExternalEpg.channels()) for (p in c.progs) {
                if (p.stop <= now + 15 * 60 || p.start > now + 14 * 3600 || p.start < now - 2 * 3600) continue
                if (p.stop - p.start < 40 * 60) continue
                val marks = listOfNotNull("EN VIVO".takeIf { p.live }, "ESTRENO".takeIf { p.isNew }).joinToString(" ")
                out += C(c, p, "${fmt.format(Date(p.start * 1000))}|${ExternalEpg.displayName(c)}|${p.title.take(80)}|${p.category.take(30)}|$marks")
            }
            // Lo más prometedor primero: en vivo, estrenos, deportes y películas
            fun weight(x: C): Int {
                val cat = (x.p.category + " " + x.p.title).lowercase()
                var w = 0
                if (x.p.live) w += 4
                if (x.p.isNew) w += 2
                if (Regex("f[uú]tbol|soccer|sport|deport|vs\\.?|boxeo|ufc|f1|nba|nfl|liga|copa").containsMatchIn(cat)) w += 3
                if (Regex("pel[ií]cula|movie|film|cine").containsMatchIn(cat)) w += 1
                return w
            }
            out.sortedWith(compareByDescending<C> { weight(it) }.thenBy { it.p.start }).distinctBy { it.p.title.lowercase() + it.p.start }.take(500)
        }
        if (cands.isEmpty()) return
        val bucket = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + "-" + (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) / 3)
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("k", bucket + "|" + repo.epgCountries())
                    .put("now", SimpleDateFormat("EEEE d 'de' MMMM, h:mm a", Locale("es")).format(Date()))
                    .put("items", JSONArray(cands.map { it.line })).toString()
                val req = okhttp3.Request.Builder().url("${CloudSync.SERVER}/v1/ai/today")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONArray("picks")
                }
            }.getOrNull()
        } ?: return
        val byLine = cands.associateBy { it.line }
        val out = ArrayList<Pick>()
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            // La respuesta puede venir de otro equipo (compartida): se busca por el texto del programa
            val c = byLine[o.optString("t")] ?: cands.getOrNull(o.optInt("i", -1))?.takeIf { it.line == o.optString("t") } ?: continue
            val ch = channelFor(ctx, repo, c.chan) ?: continue
            out += Pick(c.p.title, ExternalEpg.displayName(c.chan), c.p.start, c.p.stop, o.optString("why"), ch)
            if (out.size >= 10) break
        }
        picks.value = out
        loadedFor = acc; loadedAt = System.currentTimeMillis()
    }

    // ── Avísame cuando juegue… ──

    private const val ALERTS = "jox3_alerts"
    /** Lo que se vigila en la guía ("millonarios", "real madrid", "la voz"…). */
    val alerts = mutableStateListOf<String>()
    /** Aviso en pantalla (lo muestran la TV y el celular). */
    val banner = mutableStateOf<Pick?>(null)
    private var watching = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun loadAlerts(ctx: Context) {
        if (alerts.isNotEmpty()) return
        val a = runCatching { JSONArray(ctx.getSharedPreferences(ALERTS, Context.MODE_PRIVATE).getString("terms", "[]")) }.getOrNull() ?: return
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { alerts += it }
    }

    private fun saveAlerts(ctx: Context) =
        ctx.getSharedPreferences(ALERTS, Context.MODE_PRIVATE).edit().putString("terms", JSONArray(alerts.toList()).toString()).apply()

    fun addAlert(ctx: Context, term: String) {
        val t = term.trim().lowercase(Locale("es")).take(40)
        if (t.length < 3 || t in alerts) return
        alerts += t; saveAlerts(ctx)
    }

    fun removeAlert(ctx: Context, term: String) { alerts -= term; saveAlerts(ctx) }

    /** El servidor en uso (lo pone la pantalla principal; cambia al cambiar de servidor). */
    @Volatile var repo: TvRepo? = null

    /** Vigila la guía cada minuto mientras la app está abierta. */
    fun watch(ctx: Context) {
        loadAlerts(ctx)
        if (watching) return
        watching = true
        val app = ctx.applicationContext
        scope.launch {
            while (true) {
                delay(60_000)
                val r = repo ?: continue
                if (alerts.isEmpty() || !ExternalEpg.isReady() || banner.value != null) continue
                val now = System.currentTimeMillis() / 1000
                val prefs = app.getSharedPreferences(ALERTS, Context.MODE_PRIVATE)
                val found = withContext(Dispatchers.Default) { ExternalEpg.findPrograms(alerts.toList(), now, now + 10 * 60, 6) }
                    // Que empiece en los próximos 10 min (o haya empezado hace menos de 5)
                    .filter { (_, p) -> p.start in (now - 5 * 60)..(now + 10 * 60) }
                for ((chan, p) in found) {
                    val key = "n_" + chan.key.hashCode() + "_" + p.start
                    if (prefs.getBoolean(key, false)) continue
                    val ch = channelFor(app, r, chan) ?: continue
                    prefs.edit().putBoolean(key, true).apply()
                    banner.value = Pick(p.title, ExternalEpg.displayName(chan), p.start, p.stop, "", ch)
                    break
                }
            }
        }
    }
}
