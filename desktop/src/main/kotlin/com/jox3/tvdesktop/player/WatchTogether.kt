package com.jox3.tvdesktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.data.CloudSync
import com.jox3.tvdesktop.data.Profiles
import com.jox3.tvdesktop.data.TvRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Ver juntos": dos o más equipos (PC, TV Box, celular) ven la misma película o episodio
 * al mismo tiempo. Mismo servidor y mismos mensajes que la app Android (tv/player/WatchTogether.kt).
 *
 * Cada equipo descarga el video de su propio servidor IPTV; por la sala solo pasan las
 * órdenes (reproducir, pausar, adelantar) y reacciones. Quien crea la sala manda la hora
 * cada 5 s y los demás se corrigen si se desfasan más de 2,5 s.
 */
object WatchTogether {
    enum class Phase { Off, Connecting, On, Error }
    data class Peer(val name: String, val emoji: String)
    data class Media(val kind: String, val title: String, val season: Int, val episode: Int, val image: String)
    data class Reaction(val id: Long, val text: String)

    /** Lo que se está viendo en este equipo, como lo entiende la sala. */
    class Now(val kind: String, val itemName: String, val title: String, val image: String)

    /** Lo que la sala necesita del reproductor de la ventana (lo conecta la pantalla principal). */
    interface Host {
        val player: VideoPlayer
        /** null = no hay película ni episodio abierto (o es un tráiler). */
        fun now(): Now?
        /** Busca el título en el servidor en uso y lo abre. false = no está. */
        suspend fun open(m: Media): Boolean
    }

    val REACTIONS = listOf("😂", "❤️", "😱", "👏", "🔥")

    var phase by mutableStateOf(Phase.Off); private set
    var code by mutableStateOf(""); private set
    var host by mutableStateOf(false); private set
    var peers by mutableStateOf<List<Peer>>(emptyList()); private set
    /** Una línea de estado para mostrar ("Abriendo…", "No encontré…"). */
    var message by mutableStateOf(""); private set
    val reactions = mutableStateListOf<Reaction>()
    var media by mutableStateOf<Media?>(null); private set
    /** Datos de TMDB de lo que se está viendo (para la invitación: póster, sinopsis, reparto). */
    var info by mutableStateOf<com.jox3.tvdesktop.data.Tmdb.Info?>(null); private set
    /** Los controles del reproductor están a la vista: el aviso de la sala se muestra con ellos. */
    var controlsVisible by mutableStateOf(false)

    /** Enlace de la invitación (página con el póster y botón para abrir la app en el celular). */
    fun inviteLink() = "${CloudSync.SERVER}/v/$code"

    /** Todo corre en el hilo de la ventana (como el "main" de Android). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client: OkHttpClient by lazy {
        CloudSync.http.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    private var ws: WebSocket? = null
    private var ui: Host? = null
    private var applyingUntil = 0L
    /** Estado recibido para aplicar cuando el video correcto esté listo: (posición, reproduciendo, cuándo llegó). */
    private var pending: Triple<Long, Boolean, Long>? = null
    private var retries = 0
    private var gen = 0
    private var heartbeat: Job? = null

    private fun me(): Peer = Profiles.current?.let { Peer(it.name, it.emoji) } ?: Peer("Invitado", "🙂")
    private fun main(block: () -> Unit) { scope.launch { block() } }

    // ── Sala ──

    fun create() {
        leave()
        host = true; phase = Phase.Connecting; message = "Creando la sala…"
        val myGen = ++gen
        scope.launch {
            val c = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("${CloudSync.SERVER}/v1/room")
                        .post("{}".toRequestBody("application/json".toMediaType())).build()
                    client.newCall(req).execute().use { r -> JSONObject(r.body?.string().orEmpty()).optString("code") }
                }.getOrNull()
            }
            if (myGen != gen) return@launch
            if (c.isNullOrEmpty()) { phase = Phase.Error; message = "No se pudo crear la sala. Revisa el internet" }
            else { code = c; connect() }
        }
    }

    fun join(c: String) {
        leave()
        host = false; code = c.filter { it.isDigit() }.take(6)
        phase = Phase.Connecting; message = "Entrando a la sala…"
        gen++
        connect()
    }

    fun leave() {
        gen++
        runCatching { ws?.close(1000, "salió") }; ws = null
        phase = Phase.Off; code = ""; host = false; peers = emptyList(); message = ""; media = null; info = null
        pending = null; retries = 0
        heartbeat?.cancel(); heartbeat = null
    }

    private fun connect() {
        val myGen = gen
        val req = Request.Builder().url(CloudSync.SERVER.replace("https://", "wss://") + "/v1/room/$code/ws").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = main {
                if (myGen != gen) return@main
                retries = 0
                phase = Phase.On
                message = if (host) "Comparte el código ${pretty(code)}" else ""
                send(JSONObject().put("type", "hello").put("name", me().name).put("emoji", me().emoji))
                if (host) { sendMedia(); sendState("state") }
                heartbeat?.cancel()
                heartbeat = scope.launch {
                    while (true) { delay(5000); if (phase == Phase.On && host) sendState("state") }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = main {
                if (myGen == gen) runCatching { handle(JSONObject(text)) }.onFailure { Log.w("Ver juntos: mensaje raro (${it.message})") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = main { closed(myGen, code, reason) }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = main { closed(myGen, -1, t.message ?: "") }
        })
    }

    private fun closed(myGen: Int, c: Int, reason: String) {
        if (myGen != gen || phase == Phase.Off) return
        heartbeat?.cancel()
        if (c == 4004 || c == 4000) { phase = Phase.Error; message = reason.ifBlank { "La sala terminó" }; return }
        if (retries < 6) {
            retries++
            phase = Phase.Connecting; message = "Reconectando…"
            scope.launch { delay(2000L * retries); if (myGen == gen) connect() }
        } else { phase = Phase.Error; message = "Se perdió la conexión con la sala" }
    }

    private fun send(o: JSONObject) { runCatching { ws?.send(o.toString()) } }

    fun react(emoji: String) {
        send(JSONObject().put("type", "react").put("emoji", emoji))
        addReaction("$emoji ${me().name}")
    }

    private fun addReaction(text: String) {
        val r = Reaction(System.nanoTime(), text)
        reactions.add(r)
        while (reactions.size > 6) reactions.removeAt(0)
        scope.launch { delay(4500); reactions.remove(r) }
    }

    // ── Reproductor ──

    /** La pantalla principal conecta el reproductor (y lo desconecta al salir). */
    fun attach(h: Host?) {
        ui = h
        h?.player?.onUserAction = { playing, pos -> userAction(playing, pos) }
    }

    /** Alguien tocó pausa, reanudar o adelantar en ESTE equipo: se avisa a la sala. */
    private fun userAction(playing: Boolean, pos: Long) {
        if (phase != Phase.On || System.currentTimeMillis() < applyingUntil) return
        if (ui?.now() == null) return
        send(stateJson("cmd", playing, pos))
    }

    /** Cambió lo que se ve (otra película u otro episodio). Quien creó la sala lo comparte. */
    fun mediaChanged() {
        if (phase == Phase.On && host) scope.launch { delay(300); sendMedia() }
        tryPending()
    }

    /** El reproductor empezó a sonar: si había una posición esperando, se aplica. */
    fun playerReady() = tryPending()

    private fun isPlaying(p: VideoPlayer) =
        p.state == PlayState.Playing || p.state is PlayState.Buffering || p.state == PlayState.Opening

    /** Llegó (o ya estaba) el video que se esperaba: ponerlo donde va la sala. */
    private fun tryPending() {
        val pd = pending ?: return
        val m = media ?: return
        val p = ui?.player ?: return
        if (!matches(m) || p.state != PlayState.Playing && p.state != PlayState.Paused) return
        pending = null
        val elapsed = if (pd.second) System.currentTimeMillis() - pd.third else 0L
        apply(pd.first + elapsed, pd.second)
        message = ""
    }

    private fun stateJson(type: String, playing: Boolean, pos: Long): JSONObject {
        val p = ui?.player
        return JSONObject().put("type", type).put("playing", playing).put("pos", pos).put("rate", (p?.rate ?: 1f).toDouble())
    }

    private fun sendState(type: String) {
        val p = ui?.player ?: return
        if (ui?.now() == null) return
        send(stateJson(type, isPlaying(p), p.timeMs))
    }

    private fun currentMedia(): Media? {
        val n = ui?.now() ?: return null
        return if (n.kind == "episode") {
            val m = Regex("T(\\d+)E(\\d+)").find(n.itemName)
            Media("episode", n.title, m?.groupValues?.get(1)?.toIntOrNull() ?: 0, m?.groupValues?.get(2)?.toIntOrNull() ?: 0, n.image)
        } else Media("movie", n.title, 0, 0, n.image)
    }

    private fun sendMedia() {
        val m = currentMedia() ?: return
        media = m
        val myGen = gen
        // Primero se buscan los datos en TMDB (máx. 3,5 s) para que la invitación tenga póster y sinopsis
        scope.launch {
            val i = kotlinx.coroutines.withTimeoutOrNull(3500) { tmdbInfo(m) }
            if (myGen != gen) return@launch
            info = i
            send(JSONObject().put("type", "media").put("kind", m.kind).put("title", m.title)
                .put("season", m.season).put("episode", m.episode).put("image", m.image)
                .put("poster", i?.poster.orEmpty()).put("overview", i?.overview.orEmpty())
                .put("meta", i?.let(::metaLine).orEmpty()).put("cast", i?.cast?.take(4)?.joinToString(", ") { it.name }.orEmpty()))
        }
    }

    private suspend fun tmdbInfo(m: Media): com.jox3.tvdesktop.data.Tmdb.Info? = runCatching {
        if (m.kind == "movie") com.jox3.tvdesktop.data.Tmdb.movie(m.title) else com.jox3.tvdesktop.data.Tmdb.series(m.title)
    }.getOrNull()

    /** "2013 · ⭐ 6.9 · Acción, Terror · 1 h 56 min" (igual que Android). */
    fun metaLine(i: com.jox3.tvdesktop.data.Tmdb.Info): String = listOfNotNull(
        i.year.ifBlank { null },
        i.rating.takeIf { it > 0f }?.let { "⭐ " + String.format(java.util.Locale.US, "%.1f", it) },
        i.genres.ifBlank { null },
        i.runtimeMin.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
    ).joinToString(" · ")

    /** Texto de la invitación para WhatsApp (el mismo que manda el celular). */
    fun inviteText(): String {
        val m = media
        val i = info
        val title = when {
            m == null -> "esto"
            m.kind == "episode" && m.season > 0 -> "${m.title} (T${m.season}E${m.episode})"
            else -> m.title
        }
        return buildString {
            append("🍿 ¡Veamos juntos *").append(title).append("*!\n")
            if (i != null) {
                metaLine(i).takeIf { it.isNotBlank() }?.let { append(it).append("\n") }
                if (i.overview.isNotBlank()) append("\n").append(i.overview.take(350)).append(if (i.overview.length > 350) "…" else "").append("\n")
                val cast = i.cast.take(4).joinToString(", ") { it.name }
                if (cast.isNotBlank()) append("\n🎭 ").append(cast).append("\n")
                if (i.director.isNotBlank()) append("🎬 ").append(i.director).append("\n")
            }
            append("\n👉 Toca para unirte: ").append(inviteLink())
            append("\no abre JOX3 TV → 👥 Ver juntos → código *").append(code.take(3)).append(" ").append(code.drop(3)).append("*")
        }
    }

    private fun matches(m: Media): Boolean {
        val cur = currentMedia() ?: return false
        if (cur.kind != m.kind || TvRepo.titleKey(cur.title) != TvRepo.titleKey(m.title)) return false
        return m.kind != "episode" || (cur.season == m.season && cur.episode == m.episode)
    }

    private fun apply(pos: Long, playing: Boolean) {
        val p = ui?.player ?: return
        applyingUntil = System.currentTimeMillis() + 1500
        if (kotlin.math.abs(p.timeMs - pos) > 1500) p.seekTo(pos.coerceAtLeast(0), fromUser = false)
        p.setPaused(!playing)
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "snapshot" -> {
                val md = o.optJSONObject("media")?.let(::parseMedia)
                val st = o.optJSONObject("state")
                if (!host && md != null) follow(md, st)
                else if (!host) message = "Esperando a que empiece la película…"
            }
            "media" -> if (!host) o.optJSONObject("media")?.let { follow(parseMedia(it), null) }
            "cmd", "state" -> {
                val m = media ?: return
                val pos = o.optLong("pos"); val playing = o.optBoolean("playing")
                if (!matches(m)) { pending = Triple(pos, playing, System.currentTimeMillis()); return }
                val p = ui?.player ?: return
                // "cmd" (alguien pausó o adelantó): siempre. "state" (cada 5 s): solo si hay desfase
                val isCmd = o.optString("type") == "cmd"
                if (isCmd || kotlin.math.abs(p.timeMs - pos) > 2500 || isPlaying(p) != playing) {
                    apply(pos, playing)
                    if (isCmd) o.optString("from").takeIf { it.isNotBlank() }?.let { who ->
                        val text = "$who ${if (playing) "reanudó" else "pausó"}"
                        message = text
                        scope.launch { delay(3000); if (message == text) message = "" }
                    }
                }
            }
            "peers" -> {
                val arr = o.optJSONArray("peers")
                peers = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    arr?.optJSONObject(i)?.let { Peer(it.optString("name"), it.optString("emoji")) }
                }
            }
            "react" -> addReaction(o.optString("emoji") + " " + o.optString("from"))
        }
    }

    private fun parseMedia(o: JSONObject) = Media(o.optString("kind"), o.optString("title"), o.optInt("season"),
        o.optInt("episode"), o.optString("image"))

    /** Invitado: abrir lo mismo que ve la sala y ponerlo en el mismo minuto. */
    private fun follow(m: Media, st: JSONObject?) {
        media = m
        if (st != null) pending = Triple(st.optLong("pos"), st.optBoolean("playing", true), System.currentTimeMillis())
        if (matches(m)) {
            val pd = pending ?: return
            pending = null; apply(pd.first, pd.second); return
        }
        message = "Abriendo «${m.title}»…"
        val h = ui ?: return
        scope.launch {
            val ok = try { h.open(m) } catch (e: Exception) { Log.w("Ver juntos: ${e.message}"); false }
            if (!ok) message = "No encontré «${m.title}» en este servidor. Ábrela desde otro servidor y se sincroniza sola."
            else listOf(800L, 2000L, 4000L, 8000L).forEach { d -> scope.launch { delay(d); tryPending() } }
        }
    }

    /** "482913" → "482 913" */
    fun pretty(c: String) = if (c.length == 6) c.take(3) + " " + c.drop(3) else c

    fun peopleLine(): String {
        val p = peers
        return if (p.size <= 1) "Esperando a que alguien entre…"
        else "${p.size} en la sala: " + p.joinToString(" · ") { "${it.emoji} ${it.name}" }
    }
}
