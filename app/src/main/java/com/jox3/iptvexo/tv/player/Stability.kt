package com.jox3.iptvexo.tv.player

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.CloudSync
import com.jox3.iptvexo.tv.data.ServerHub
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

/**
 * Puntaje de estabilidad de cada señal en vivo (servidor + canal), de 0 a 100.
 * Se arma con lo que pasa al verla en este equipo: si arranca, cuánto tarda, si se pega
 * o se cae. Con eso se elige la "Mejor opción" en el buscador y el orden del respaldo.
 *
 * Además se comparte: cada equipo sube lo que midió (sin usuario ni clave: solo la huella del
 * servidor y el número del canal) y consulta lo que midieron todos los demás con ese servidor.
 */
object Stability {
    private const val PREFS = "jox3_stability"
    private const val MAX_ENTRIES = 600

    /** Lo medido de una señal. */
    data class Stats(val starts: Int, val ok: Int, val stalls: Int, val fails: Int, val msTotal: Long, val ts: Long)

    private fun key(acc: Account, id: String) = "s" + acc.key.hashCode() + "_" + id

    private fun read(ctx: Context, acc: Account, id: String): Stats? {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(acc, id), null) ?: return null
        val p = v.split(',')
        if (p.size < 6) return null
        return Stats(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toLong(), p[5].toLong())
    }

    private fun write(ctx: Context, acc: Account, id: String, s: Stats) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = prefs.edit()
        // Que no crezca sin fin: se borran las más viejas
        if (prefs.all.size > MAX_ENTRIES) {
            prefs.all.entries.sortedBy { (it.value as? String)?.substringAfterLast(',')?.toLongOrNull() ?: 0L }
                .take(prefs.all.size - MAX_ENTRIES + 50).forEach { e.remove(it.key) }
        }
        // Lo reciente pesa más: los contadores se reducen a la mitad al pasar de 40 arranques
        val t = if (s.starts > 40) Stats(s.starts / 2, s.ok / 2, s.stalls / 2, s.fails / 2, s.msTotal / 2, s.ts) else s
        e.putString(key(acc, id), "${t.starts},${t.ok},${t.stalls},${t.fails},${t.msTotal},${t.ts}").apply()
    }

    private fun update(ctx: Context, acc: Account, id: String, f: (Stats) -> Stats) {
        val s = read(ctx, acc, id) ?: Stats(0, 0, 0, 0, 0, 0)
        val n = f(s).copy(ts = System.currentTimeMillis())
        write(ctx, acc, id, n)
        // Lo nuevo (la diferencia) se sube después, junto con lo demás
        share(acc, id, Stats(n.starts - s.starts, n.ok - s.ok, n.stalls - s.stalls, n.fails - s.fails, n.msTotal - s.msTotal, 0))
    }

    // ── Compartido entre todos los equipos ──

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<String, Stats>()          // "srv|id" → lo medido aún sin subir
    private val global = HashMap<String, Pair<Long, Stats?>>()  // "srv|id" → (cuándo se consultó, suma de todos)
    @Volatile private var flushing = false
    /** Sube cuando llegan puntajes de otros equipos (la "Mejor opción" se vuelve a calcular). */
    val version = mutableIntStateOf(0)

    private fun srvOf(acc: Account) = com.jox3.iptvexo.tv.player.skip.SkipDetector.srvKey(acc.host)

    private fun share(acc: Account, id: String, d: Stats) {
        if (!Regex("[A-Za-z0-9_.-]{1,40}").matches(id)) return
        val k = srvOf(acc) + "|" + id
        synchronized(pending) {
            val p = pending[k] ?: Stats(0, 0, 0, 0, 0, 0)
            pending[k] = Stats(p.starts + d.starts, p.ok + d.ok, p.stalls + d.stalls, p.fails + d.fails, p.msTotal + d.msTotal, 0)
        }
        if (flushing) return
        flushing = true
        scope.launch {
            delay(90_000)   // juntar varias mediciones en un solo envío
            val batch = synchronized(pending) { HashMap(pending).also { pending.clear() } }
            flushing = false
            if (batch.isEmpty()) return@launch
            runCatching {
                val items = JSONArray()
                batch.forEach { (k, s) ->
                    items.put(JSONObject().put("s", k.substringBefore('|')).put("id", k.substringAfter('|'))
                        .put("st", s.starts).put("ok", s.ok).put("f", s.fails).put("sl", s.stalls).put("ms", s.msTotal))
                }
                val req = okhttp3.Request.Builder().url("${CloudSync.SERVER}/v1/stab")
                    .post(JSONObject().put("items", items).toString().toRequestBody("application/json".toMediaType())).build()
                com.jox3.iptvexo.NetClient.api().newCall(req).execute().close()
            }
        }
    }

    /**
     * Trae lo que midieron los demás equipos para estas señales (las que no se consultaron en
     * los últimos 30 min). Después, [score] ya los tiene en cuenta.
     */
    suspend fun prefetch(signals: List<Pair<Account, String>>) {
        val now = System.currentTimeMillis()
        val want = signals.map { (a, id) -> srvOf(a) to id }.distinct().filter { (s, id) ->
            synchronized(global) { global[s + "|" + id] }?.let { now - it.first > 30 * 60_000 } ?: true
        }.take(300)
        if (want.isEmpty()) return
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val q = JSONArray()
                want.groupBy({ it.first }, { it.second }).forEach { (s, ids) -> q.put(JSONObject().put("s", s).put("ids", JSONArray(ids))) }
                val req = okhttp3.Request.Builder().url("${CloudSync.SERVER}/v1/stab/get")
                    .post(JSONObject().put("q", q).toString().toRequestBody("application/json".toMediaType())).build()
                com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONObject("r")
                }
            }.getOrNull()
        } ?: return
        synchronized(global) {
            want.forEach { (s, id) ->
                val k = "$s|$id"
                val a = res.optJSONArray(k)
                global[k] = now to a?.let { Stats(it.optInt(0), it.optInt(1), it.optInt(3), it.optInt(2), it.optLong(4), 0) }
            }
        }
        version.intValue++
    }

    /** Lo de los demás equipos, con un tope (20 arranques) para que no tape lo medido aquí. */
    private fun others(acc: Account, id: String): Stats? {
        val g = synchronized(global) { global[srvOf(acc) + "|" + id] }?.second ?: return null
        if (g.starts <= 0) return null
        val f = minOf(1.0, 20.0 / g.starts)
        return Stats((g.starts * f).toInt(), (g.ok * f).toInt(), (g.stalls * f).toInt(), (g.fails * f).toInt(), (g.msTotal * f).toLong(), 0)
    }

    /** Arrancó y tardó [ms]. */
    fun started(ctx: Context, acc: Account, id: String, ms: Long) =
        update(ctx, acc, id) { it.copy(starts = it.starts + 1, ok = it.ok + 1, msTotal = it.msTotal + ms.coerceIn(0, 30_000)) }

    /** No arrancó o se cayó (y hubo que cambiar de señal). */
    fun failed(ctx: Context, acc: Account, id: String) =
        update(ctx, acc, id) { it.copy(starts = it.starts + 1, fails = it.fails + 1) }

    /** Se pegó (volvió a cargar) mientras se veía. */
    fun stalled(ctx: Context, acc: Account, id: String) =
        update(ctx, acc, id) { it.copy(stalls = it.stalls + 1) }

    /**
     * Puntaje 0–100. Sin datos parte de 60; con datos pesa cada vez más lo medido.
     * Suman la calidad (FHD/HD) y el estado del servidor (rojo resta mucho).
     */
    fun score(ctx: Context, acc: Account, id: String, name: String): Int {
        // Lo medido en este equipo + lo de los demás (con tope). Lo propio pesa igual o más.
        val mine = read(ctx, acc, id)
        val g = others(acc, id)
        val s = if (g == null) mine else (mine ?: Stats(0, 0, 0, 0, 0, 0)).let {
            Stats(it.starts + g.starts, it.ok + g.ok, it.stalls + g.stalls, it.fails + g.fails, it.msTotal + g.msTotal, it.ts)
        }
        var base = 60.0
        if (s != null && s.starts > 0) {
            val success = (s.ok + 1.0) / (s.starts + 2.0)
            val stallRate = s.stalls.toDouble() / maxOf(s.ok, 1)
            val avgSec = s.msTotal / 1000.0 / maxOf(s.ok, 1)
            val measured = 100 * success - minOf(stallRate, 3.0) * 12 - minOf(avgSec, 8.0) * 2.5
            val w = s.starts / (s.starts + 3.0)
            base = w * measured + (1 - w) * 60
        }
        val tag = ServerHub.tagOf(name)
        base += when {
            tag.startsWith("FHD") -> 6.0
            tag.startsWith("HD") -> 4.0
            tag.startsWith("4K") -> 1.0
            tag.startsWith("SD") -> -4.0
            else -> 0.0
        }
        val st = ServerHub.status[acc.key]
        base += when {
            st == null -> 0.0
            !st.ok -> -40.0
            st.level >= 1 -> -6.0
            else -> 4.0
        }
        // Tiempo de respuesta del servidor: desempata cuando aún no hay mediciones
        // (394 ms gana a 800 ms); con historial pesa poco frente a lo medido
        if (st != null && st.ok) base -= minOf(st.pingMs / 150.0, 8.0) * (if (s == null || s.starts == 0) 1.0 else 0.4)
        return base.toInt().coerceIn(0, 100)
    }

    /** La copia más estable de un canal (para "Mejor opción"). */
    fun best(ctx: Context, copies: List<ServerHub.Copy>, current: Account? = null): ServerHub.Copy? {
        val live = copies.filter { it.kind == "live" }
        // Solo señales del MISMO canal que el que se tocó (el buscador agrupa nombres parecidos;
        // aquí se exige la misma identidad: "Caracol HD" sí, "Caracol Internacional" no)
        val ref = live.firstOrNull { it.account.key == current?.key } ?: live.firstOrNull() ?: return null
        val id = Failover.identity(ref.name)
        val same = live.filter { Failover.identity(it.name) == id }
        // A igual puntaje, el servidor actual (no gasta otra conexión)
        return same.maxWithOrNull(compareBy<ServerHub.Copy> { score(ctx, it.account, it.id, it.name) }
            .thenBy { if (it.account.key == current?.key) 1 else 0 })
    }
}
