package com.jox3.iptvexo.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Búsqueda inteligente: una frase ("pon el partido de Millonarios", "películas de acción con
 * Stallone") se le pregunta a la IA del servidor de JOX3, que dice qué buscar. La app busca eso
 * en tus servidores y en la guía de programación (para encontrar en qué canal dan el partido).
 *
 * Las búsquedas cortas ("caracol", "rambo") siguen siendo normales y al instante. Si la IA no
 * responde, se muestra la búsqueda normal: nunca queda peor que antes.
 */
object SmartSearch {
    private const val PREFS = "jox3_ai"

    data class Plan(val kind: String, val terms: List<String>, val titles: List<String>, val type: String, val say: String)

    /** Lo que muestra el buscador. [labels]: clave del canal → lo que pasa ahí ("En vivo: …", "8:00 PM · …"). */
    class Outcome(
        val results: ServerHub.Results,
        val say: String = "",
        val labels: Map<String, String> = emptyMap(),
        val thinking: Boolean = false,
        /** Si pidió un partido o evento: qué vigilar para "Avísame cuando juegue…". */
        val alertTerms: List<String> = emptyList(),
    )

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("search", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("search", on).apply()

    /** Frases de 3 palabras o más (lo que llega por voz casi siempre lo es). */
    fun wants(ctx: Context, query: String): Boolean =
        enabled(ctx) && query.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= 3

    private val plans = object : LinkedHashMap<String, Plan>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Plan>?) = size > 40
    }

    /** Pregunta a la IA qué quiere ver la persona (null si no responde). */
    suspend fun plan(query: String): Plan? {
        val key = TvRepo.normalize(query.trim())
        synchronized(plans) { plans[key] }?.let { return it }
        val p = withContext(Dispatchers.IO) {
            runCatching {
                val now = SimpleDateFormat("EEEE d 'de' MMMM, h:mm a", Locale("es")).format(Date())
                val body = JSONObject().put("q", query.trim().take(200)).put("now", now).toString()
                val req = Request.Builder().url("${CloudSync.SERVER}/v1/ai/search")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    val o = JSONObject(r.body?.string().orEmpty())
                    if (!o.optBoolean("ok")) return@runCatching null
                    fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
                    Plan(o.optString("kind", "plain"), list("terms"), list("titles"), o.optString("type", "any"), o.optString("say"))
                }
            }.getOrNull()
        } ?: return null
        synchronized(plans) { plans[key] = p }
        return p
    }

    /** Búsqueda normal (al instante). */
    suspend fun plain(query: String, current: Account, idx: TvRepo.Index, all: List<Account>, only: String?, includeOthers: Boolean) =
        ServerHub.search(query, current, idx, all, only, includeOthers)

    /**
     * Búsqueda con IA. Devuelve null si la IA no ayudó (no responde o no entendió).
     */
    suspend fun smart(
        ctx: Context, query: String, current: Account, idx: TvRepo.Index, all: List<Account>, only: String?,
        includeOthers: Boolean,
    ): Outcome? {
        val plan = plan(query) ?: return null
        if (plan.kind == "plain" || (plan.terms.isEmpty() && plan.titles.isEmpty())) return null
        return withContext(Dispatchers.Default) {
            val ch = LinkedHashMap<String, ServerHub.Hit>()
            val chScore = HashMap<String, Int>()
            val mv = LinkedHashMap<String, ServerHub.Hit>()
            val sr = LinkedHashMap<String, ServerHub.Hit>()
            val labels = HashMap<String, String>()
            val liveWanted = plan.kind == "event" || plan.kind == "channel" || plan.type == "live"

            // 1) En la guía: qué canal pasa lo que se busca (ahora o en las próximas horas)
            if (liveWanted && ExternalEpg.isReady()) {
                val nowS = System.currentTimeMillis() / 1000
                val fmt = SimpleDateFormat("h:mm a", Locale("es"))
                ExternalEpg.findPrograms(plan.terms, nowS - 3 * 3600, nowS + 12 * 3600).forEach { (chan, prog) ->
                    val label = if (prog.start <= nowS && prog.stop > nowS) "En vivo: ${prog.title}"
                        else fmt.format(Date(prog.start * 1000)) + " · " + prog.title
                    val guideName = ExternalEpg.displayName(chan)
                    ServerHub.search(guideName, current, idx, all, only, includeOthers).channels
                        .filter { h -> ExternalEpg.match(ctx, h.name, null)?.key == chan.key }
                        .take(3).forEach { h ->
                            if (ch.putIfAbsentCompat(h.key, h)) { chScore[h.key] = 100; labels[h.key] = label }
                        }
                }
            }
            // 2) En los nombres de canales y en el catálogo
            (plan.terms + plan.titles).map { it.trim() }.filter { TvRepo.normalize(it).length >= 2 }.distinct().forEach { t ->
                val r = ServerHub.search(t, current, idx, all, only, includeOthers)
                r.channels.forEach { h -> ch.putIfAbsentCompat(h.key, h); chScore[h.key] = (chScore[h.key] ?: 0) + 1 }
                r.movies.forEach { h -> mv.putIfAbsentCompat(h.key, h) }
                r.series.forEach { h -> sr.putIfAbsentCompat(h.key, h) }
            }
            var channels = ch.values.sortedByDescending { chScore[it.key] ?: 0 }
            var movies = mv.values.toList()
            var series = sr.values.toList()
            // Tipo pedido (solo si con ese filtro queda algo)
            when {
                liveWanted && channels.isNotEmpty() -> { movies = emptyList(); series = emptyList() }
                plan.type == "movie" && movies.isNotEmpty() -> { channels = emptyList(); series = emptyList() }
                plan.type == "series" && series.isNotEmpty() -> { channels = emptyList(); movies = emptyList() }
            }
            val found = ServerHub.Results(channels.take(40), movies.take(50), series.take(50))
            if (found.isEmpty) null
            else Outcome(found, plan.say.ifBlank { "Resultados para “${query.trim()}”" }, labels,
                alertTerms = if (plan.kind == "event") plan.terms.take(2) else emptyList())
        }
    }

    private fun <K, V> MutableMap<K, V>.putIfAbsentCompat(k: K, v: V): Boolean {
        if (containsKey(k)) return false
        put(k, v); return true
    }
}
