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
        /** Señal elegida si no es la del canal en este servidor (otra calidad u otro servidor). */
        val alt: com.jox3.iptvexo.tv.player.Failover.Alt? = null,
        /** "▶ B · FHD" cuando la mejor señal está en otro servidor ("" si es el actual). */
        val serverLabel: String = "",
    ) {
        /** Deja lista la señal y devuelve el canal a poner. */
        fun prepare(): Channel {
            alt?.let { com.jox3.iptvexo.tv.player.Failover.preset = channel.id to it }
            return channel
        }
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

    // Qué canales de la guía tiene el servidor (guía → canal del servidor), por servidor, 1 hora
    @Volatile private var serverMap: Triple<String, Long, Map<String, Channel>>? = null

    /** Canales de la guía que el servidor actual tiene, con su canal en el servidor. */
    suspend fun serverChannels(repo: TvRepo): Map<String, Channel> {
        val acc = repo.api.account.key
        serverMap?.let { (a, t, m) -> if (a == acc && System.currentTimeMillis() - t < 3600_000 && m.isNotEmpty()) return m }
        val idx = repo.peekIndex() ?: runCatching { repo.searchIndex() }.getOrNull() ?: return emptyMap()
        val m = withContext(Dispatchers.Default) {
            val out = HashMap<String, Channel>()
            for (c in idx.channels) {
                val g = ExternalEpg.quickMatch(c.name, c.epgId.ifBlank { null }) ?: continue
                if (g.progs.isNotEmpty()) out.putIfAbsent(g.key, c)
            }
            out
        }
        serverMap = Triple(acc, System.currentTimeMillis(), m)
        return m
    }

    /** El canal del servidor actual que corresponde a [chan] de la guía (null si no lo tiene). */
    suspend fun channelFor(ctx: Context, repo: TvRepo, chan: ExternalEpg.Chan): Channel? {
        serverChannels(repo)[chan.key]?.let { return it }
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
    /** Qué pasó la última vez (se ve en Ajustes): para saber por qué la fila no aparece. */
    val status = mutableStateOf("Todavía no se ha preparado.")

    /** Volver a preparar la fila ya (botón de Ajustes). */
    fun refresh() { loadedAt = 0L; picks.value = emptyList(); tick.intValue++ }
    /** Sube con "Volver a preparar": el Inicio la arma de nuevo. */
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile private var loadedFor = ""
    @Volatile private var loadedAt = 0L

    fun enabled(ctx: Context) = ctx.getSharedPreferences("jox3_ai", Context.MODE_PRIVATE).getBoolean("today", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("jox3_ai", Context.MODE_PRIVATE).edit().putBoolean("today", on).apply()

    /** Arma la fila (una vez por hora por servidor). Necesita la guía de internet descargada. */
    suspend fun load(ctx: Context, repo: TvRepo) {
        if (!enabled(ctx)) { status.value = "Apagada."; return }
        if (!ExternalEpg.isReady()) { status.value = "Esperando la guía de programación de internet…"; return }
        val me = repo.api.account
        val profileKey = me.key + "|" + (Profiles.current?.id ?: "")
        if (loadedFor == profileKey && System.currentTimeMillis() - loadedAt < 3600_000 && picks.value.isNotEmpty()) return
        val now = System.currentTimeMillis() / 1000
        // 1) Qué canales de la guía hay en CADA uno de tus servidores
        status.value = "Revisando qué canales de tus servidores tienen guía…"
        val accounts = listOf(me) + AccountStore(ctx).shown().filter { it.key != me.key }
        val copies = HashMap<String, MutableList<Pair<Account, Channel>>>()   // guía → señales
        serverChannels(repo).forEach { (k, c) -> copies.getOrPut(k) { ArrayList() } += me to c }
        for (acc in accounts.drop(1)) {
            val list = runCatching { com.jox3.iptvexo.tv.player.Failover.liveChannels(acc, repo) }.getOrDefault(emptyList())
            withContext(Dispatchers.Default) {
                for (c in list) {
                    val g = ExternalEpg.quickMatch(c.name, c.epgId.ifBlank { null }) ?: continue
                    if (g.progs.isNotEmpty()) copies.getOrPut(g.key) { ArrayList() } += acc to c
                }
            }
        }
        if (copies.isEmpty()) { status.value = "Ningún canal de tus servidores coincide con la guía de internet."; return }
        // 2) Programas de hoy que valen la pena (ya empezados hace poco o por empezar)
        class C(val chan: ExternalEpg.Chan, val p: ExternalEpg.Prog, val line: String)
        val fmt = SimpleDateFormat("h:mm a", Locale("es"))
        val cands = withContext(Dispatchers.Default) {
            val out = ArrayList<C>()
            for (c in ExternalEpg.channels()) if (c.key in copies) for (p in c.progs) {
                if (p.stop <= now + 15 * 60 || p.start > now + 14 * 3600 || p.start < now - 2 * 3600) continue
                if (p.stop - p.start < 40 * 60) continue
                val marks = listOfNotNull("EN VIVO".takeIf { p.live }, "ESTRENO".takeIf { p.isNew }).joinToString(" ")
                out += C(c, p, "${fmt.format(Date(p.start * 1000))}|${ExternalEpg.displayName(c)}|${p.title.take(80)}|${p.category.take(30)}|$marks")
            }
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
        if (cands.isEmpty()) { status.value = "Los canales de tus servidores con guía no tienen programas largos para hoy."; return }
        // 3) Gustos del perfil: lo que ve (canales recientes y favoritos)
        val lib = repo.library
        val taste = (lib.recentChannels.take(12).map { it.name } + lib.favorites.filter { it.kind == "live" }.take(10).map { it.name })
            .map { Names.channel(it) }.distinct().joinToString(", ").take(400)
        val kids = Profiles.current?.name?.lowercase()?.let { Regex("niñ|nin[oa]s|kid|infant|peque|chico").containsMatchIn(it) } == true
        status.value = "Preguntando a la IA (${cands.size} programas de ${accounts.size} servidores)…"
        // Se comparte entre quienes tienen los mismos servidores y los mismos gustos, por franja de 3 horas
        val bucket = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + "-" + (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) / 3) +
            "-" + accounts.joinToString("") { com.jox3.iptvexo.tv.player.skip.SkipDetector.srvKey(it.host).take(6) }.take(36) +
            "-" + (taste + kids).hashCode().toUInt().toString(36)
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("k", bucket + "|" + repo.epgCountries())
                    .put("now", SimpleDateFormat("EEEE d 'de' MMMM, h:mm a", Locale("es")).format(Date()))
                    .put("taste", taste).put("kids", kids)
                    .put("items", JSONArray(cands.map { it.line })).toString()
                val req = okhttp3.Request.Builder().url("${CloudSync.SERVER}/v1/ai/today")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { status.value = "La IA no respondió (HTTP ${r.code}). Se reintenta al volver al Inicio."; null }
                    else JSONObject(r.body?.string().orEmpty()).optJSONArray("picks")
                }
            }.onFailure { status.value = "Sin conexión con el servidor de JOX3: ${it.message?.take(60)}" }.getOrNull()
        } ?: return
        // 4) Cada programa una vez, con la señal más estable de todos tus servidores
        val byLine = cands.associateBy { it.line }
        val chosen = ArrayList<Pair<C, String>>()
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            val c = byLine[o.optString("t")] ?: cands.getOrNull(o.optInt("i", -1))?.takeIf { it.line == o.optString("t") } ?: continue
            chosen += c to o.optString("why")
        }
        runCatching {
            com.jox3.iptvexo.tv.player.Stability.prefetch(chosen.flatMap { (c, _) -> copies[c.chan.key].orEmpty().map { it.first to it.second.id } })
        }
        val all = AccountStore(ctx).all()
        val out = ArrayList<Pick>()
        for ((c, why) in chosen) {
            val sigs = copies[c.chan.key].orEmpty()
            if (sigs.isEmpty()) continue
            val best = sigs.maxWithOrNull(compareBy<Pair<Account, Channel>> {
                com.jox3.iptvexo.tv.player.Stability.score(ctx, it.first, it.second.id, it.second.name)
            }.thenBy { if (it.first.key == me.key) 1 else 0 }) ?: continue
            val mine = sigs.firstOrNull { it.first.key == me.key }?.second
            val alt = com.jox3.iptvexo.tv.player.Failover.Alt(best.first, best.second.id, best.second.name, ServerHub.tagOf(best.second.name))
            val pick = when {
                best.first.key == me.key -> Pick(c.p.title, ExternalEpg.displayName(c.chan), c.p.start, c.p.stop, why, best.second)
                // También está aquí: se abre aquí, con la señal del otro servidor
                mine != null -> Pick(c.p.title, ExternalEpg.displayName(c.chan), c.p.start, c.p.stop, why, mine, alt,
                    "▶ " + ServerHub.letter(all, best.first) + (if (alt.tag.isNotBlank()) " · " + alt.tag else ""))
                // Solo en otro servidor: se pone desde aquí sin cambiar de servidor
                else -> Pick(c.p.title, ExternalEpg.displayName(c.chan), c.p.start, c.p.stop, why,
                    com.jox3.iptvexo.tv.player.Failover.externalChannel(alt, best.second.logo, best.second.categoryId), alt,
                    "▶ " + ServerHub.letter(all, best.first) + " · " + ServerHub.name(all, best.first))
            }
            out += pick
            if (out.size >= 12) break
        }
        // Lo que ya está pasando primero
        picks.value = out.sortedWith(compareByDescending<Pick> { it.liveNow }.thenBy { it.start })
        loadedFor = profileKey; loadedAt = System.currentTimeMillis()
        status.value = if (out.isEmpty()) "La IA eligió ${res.length()} programas, pero no se encontraron en tus servidores."
            else "Lista: ${out.size} programas de ${accounts.size} servidores."
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
