package com.jox3.iptvexo.tv.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import com.jox3.iptvexo.tv.PlayerLauncher
import com.jox3.iptvexo.tv.data.CloudSync
import com.jox3.iptvexo.tv.data.Profiles
import com.jox3.iptvexo.tv.data.TvRepo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * "Ver juntos": dos o más equipos ven la misma película o episodio al mismo tiempo.
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

    var phase by mutableStateOf(Phase.Off); private set
    var code by mutableStateOf(""); private set
    var host by mutableStateOf(false); private set
    var peers by mutableStateOf<List<Peer>>(emptyList()); private set
    /** Una línea de estado para mostrar ("Abriendo…", "No encontré…"). */
    var message by mutableStateOf(""); private set
    val reactions = mutableStateListOf<Reaction>()
    var media by mutableStateOf<Media?>(null); private set
    /** Código que llegó por un enlace de invitación (jox3://together/123456): el marco se une solo. */
    var pendingJoin by mutableStateOf<String?>(null)
    /** Datos de TMDB de lo que se está viendo (para la invitación: póster, sinopsis, reparto). */
    var info by mutableStateOf<com.jox3.iptvexo.tv.data.Tmdb.Info?>(null); private set
    var appContext: android.content.Context? = null

    /** Enlace de la invitación (página con el póster y botón para abrir la app). */
    fun inviteLink() = "${CloudSync.SERVER}/v/$code"

    /** Los controles del reproductor están a la vista: el aviso de la sala se muestra con ellos. */
    var controlsVisible by mutableStateOf(false)

    /** La interfaz busca y abre el título en este equipo (lo pone el marco de TV o celular). */
    var opener: (suspend (Media) -> Boolean)? = null

    private val main = Handler(Looper.getMainLooper())
    private val client: OkHttpClient by lazy {
        com.jox3.iptvexo.NetClient.api().newBuilder()
            .pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    private var ws: WebSocket? = null
    private var vod: VodPlayback? = null
    private var boundPlayer: Player? = null
    private var applyingUntil = 0L
    /** Estado recibido para aplicar cuando el video correcto esté listo: (posición, reproduciendo, cuándo llegó). */
    private var pending: Triple<Long, Boolean, Long>? = null
    private var retries = 0
    private var gen = 0

    private fun me(): Peer = Profiles.current?.let { Peer(it.name, it.emoji) } ?: Peer("Invitado", "🙂")

    // ── Sala ──

    fun create(v: VodPlayback) {
        leave()
        bind(v)
        host = true; phase = Phase.Connecting; message = "Creando la sala…"
        val myGen = ++gen
        Thread {
            val c = runCatching {
                val req = Request.Builder().url("${CloudSync.SERVER}/v1/room").post("{}".toRequestBody("application/json".toMediaType())).build()
                client.newCall(req).execute().use { r -> JSONObject(r.body?.string().orEmpty()).optString("code") }
            }.getOrNull()
            main.post {
                if (myGen != gen) return@post
                if (c.isNullOrEmpty()) { phase = Phase.Error; message = "No se pudo crear la sala. Revisa el internet" }
                else { code = c; connect() }
            }
        }.start()
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
        ws?.close(1000, "salió"); ws = null
        phase = Phase.Off; code = ""; host = false; peers = emptyList(); message = ""; media = null; info = null
        pending = null; retries = 0
        main.removeCallbacks(heartbeat)
    }

    private fun connect() {
        val myGen = gen
        val req = Request.Builder().url(CloudSync.SERVER.replace("https://", "wss://") + "/v1/room/$code/ws").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (myGen != gen) return@post
                    retries = 0
                    phase = Phase.On
                    message = if (host) "Comparte el código $code" else ""
                    send(JSONObject().put("type", "hello").put("name", me().name).put("emoji", me().emoji))
                    if (host) { sendMedia(); sendState("state") }
                    main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, 5000)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (myGen == gen) runCatching { handle(JSONObject(text)) } }
            }

            override fun onClosed(webSocket: WebSocket, c: Int, reason: String) {
                main.post { closed(myGen, c, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { closed(myGen, -1, t.message ?: "") }
            }
        })
    }

    private fun closed(myGen: Int, c: Int, reason: String) {
        if (myGen != gen || phase == Phase.Off) return
        if (c == 4004 || c == 4000) { phase = Phase.Error; message = reason.ifBlank { "La sala terminó" }; return }
        if (retries < 6) {
            retries++
            phase = Phase.Connecting; message = "Reconectando…"
            main.postDelayed({ if (myGen == gen) connect() }, 2000L * retries)
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
        main.postDelayed({ reactions.remove(r) }, 4500)
    }

    // ── Reproductor ──

    /** El marco lo llama con su reproductor: así se escuchan pausa, reanudar y adelantar. */
    fun bind(v: VodPlayback) {
        vod = v
        val p = v.player ?: return
        if (p === boundPlayer) return
        boundPlayer?.removeListener(listener)
        p.addListener(listener)
        boundPlayer = p
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (phase != Phase.On || System.currentTimeMillis() < applyingUntil) return
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) sendState("cmd")
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (phase != Phase.On || System.currentTimeMillis() < applyingUntil) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) sendState("cmd")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) tryPending()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // Quien creó la sala pasó a otro episodio o película: los demás lo siguen
            if (phase == Phase.On && host) main.postDelayed({ sendMedia() }, 300)
        }
    }

    /** Llegó (o ya estaba) el video que se esperaba: ponerlo donde va la sala. */
    private fun tryPending() {
        vod?.let { bind(it) }
        val pd = pending ?: return
        val m = media ?: return
        val p = vod?.player ?: return
        if (!matches(m) || p.playbackState != Player.STATE_READY) return
        pending = null
        val elapsed = if (pd.second) System.currentTimeMillis() - pd.third else 0L
        apply(pd.first + elapsed, pd.second)
        message = ""
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (phase == Phase.On && host) sendState("state")
            main.postDelayed(this, 5000)
        }
    }

    private fun sendState(type: String) {
        val p = vod?.player ?: return
        if (vod?.open != true) return
        send(JSONObject().put("type", type).put("playing", p.playWhenReady).put("pos", p.currentPosition)
            .put("rate", p.playbackParameters.speed))
    }

    private fun currentMedia(): Media? {
        val it = vod?.item ?: return null
        return if (it.resume.kind == "episode") {
            val m = Regex("T(\\d+)E(\\d+)").find(it.resume.name)
            Media("episode", it.fav.name, m?.groupValues?.get(1)?.toIntOrNull() ?: it.season,
                m?.groupValues?.get(2)?.toIntOrNull() ?: 0, it.image)
        } else Media("movie", it.resume.name, 0, 0, it.image)
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

    private suspend fun tmdbInfo(m: Media): com.jox3.iptvexo.tv.data.Tmdb.Info? {
        val ctx = appContext ?: return null
        return runCatching {
            if (m.kind == "movie") com.jox3.iptvexo.tv.data.Tmdb.movie(ctx, m.title)
            else com.jox3.iptvexo.tv.data.Tmdb.series(ctx, m.title)
        }.getOrNull()
    }

    /** "2013 · ⭐ 6.9 · Acción, Terror · 1 h 56 min" */
    fun metaLine(i: com.jox3.iptvexo.tv.data.Tmdb.Info): String = listOfNotNull(
        i.year.ifBlank { null },
        i.rating.takeIf { it > 0f }?.let { "⭐ " + String.format(java.util.Locale.US, "%.1f", it) },
        i.genres.ifBlank { null },
        i.runtimeMin.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
    ).joinToString(" · ")

    /** Texto de la invitación para WhatsApp. */
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
        val p = vod?.player ?: return
        applyingUntil = System.currentTimeMillis() + 1500
        if (kotlin.math.abs(p.currentPosition - pos) > 1500) p.seekTo(pos.coerceAtLeast(0))
        p.playWhenReady = playing
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "snapshot" -> {
                val md = o.optJSONObject("media")?.let(::parseMedia)
                val st = o.optJSONObject("state")
                if (!host && md != null) follow(md, st)
                else if (!host) message = "Esperando a que empiece la película…"
            }
            "media" -> if (!host) parseMedia(o.getJSONObject("media")).let { follow(it, null) }
            "cmd", "state" -> {
                val m = media ?: return
                val pos = o.optLong("pos"); val playing = o.optBoolean("playing")
                if (!matches(m)) { pending = Triple(pos, playing, System.currentTimeMillis()); return }
                val p = vod?.player ?: return
                // "cmd" (alguien pausó o adelantó): siempre. "state" (cada 5 s): solo si hay desfase
                if (o.optString("type") == "cmd" || kotlin.math.abs(p.currentPosition - pos) > 2500 || p.playWhenReady != playing) {
                    apply(pos, playing)
                    if (o.optString("type") == "cmd") o.optString("from").takeIf { it.isNotBlank() }?.let {
                        message = "$it ${if (playing) "reanudó" else "pausó"}"
                        main.postDelayed({ if (message.startsWith(it)) message = "" }, 3000)
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
        val open = opener ?: return
        scope.launch {
            val ok = try { open(m) } catch (e: Exception) { Log.w("JOX3", "Ver juntos: ${e.message}"); false }
            if (!ok) message = "No encontré «${m.title}» en este servidor. Ábrela desde otro servidor y se sincroniza sola."
            else listOf(800L, 2000L, 4000L, 8000L).forEach { d -> main.postDelayed({ tryPending() }, d) }
        }
    }

    private val scope = kotlinx.coroutines.MainScope()

    /** Busca el título en el catálogo de este servidor y lo abre en el reproductor. */
    suspend fun openHere(m: Media, repo: TvRepo, launcher: PlayerLauncher): Boolean {
        val key = TvRepo.titleKey(m.title)
        val idx = runCatching { repo.searchIndex() }.getOrNull() ?: return false
        if (m.kind == "movie") {
            val mv = idx.movies.filter { TvRepo.titleKey(it.name) == key }
                .let { l -> l.firstOrNull { it.poster.startsWith("http") } ?: l.firstOrNull() } ?: return false
            launcher.playMovie(mv.id, mv.name, mv.poster, mv.ext, mv.categoryId)
            return true
        }
        val sr = idx.series.firstOrNull { TvRepo.titleKey(it.name) == key } ?: return false
        val info = runCatching { repo.api.seriesInfo(sr.id) }.getOrNull() ?: return false
        val all = info.seasons.toSortedMap().values.flatten()
        val i = all.indexOfFirst { it.season == m.season && it.num == m.episode }.takeIf { it >= 0 } ?: return false
        launcher.playEpisode(sr.id, sr.name, info.poster.ifBlank { sr.poster }, all, i)
        return true
    }
}
