package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.Notify
import com.jox3.tvdesktop.core.SafeFile
import com.jox3.tvdesktop.core.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Grabaciones (DVR): guarda la señal del canal tal cual llega (MPEG-TS, ".ts"), sin volver a
 * comprimir: misma calidad y se abre aquí, en VLC o en cualquier reproductor. Misma lógica que
 * la app Android (tv/data/Recorder.kt).
 *
 * - "Grabar ahora" o programadas desde la guía (empiezan y terminan solas).
 * - En el PC graban mientras el programa está abierto (aunque esté minimizado). Si se cierra,
 *   al volver a abrirlo las programadas que siguen vigentes empiezan solas.
 */
object Recorder {
    enum class Status { SCHEDULED, RECORDING, DONE, FAILED, CANCELED }

    data class Rec(
        val id: String, val acc: String, val chId: String, val chName: String, val logo: String,
        val title: String, val start: Long, val end: Long, val file: String,
        val status: Status, val bytes: Long = 0L, val error: String = "",
    ) {
        val durationMin get() = ((end - start) / 60_000L).toInt()
    }

    /** Sube cuando cambia la lista: las pantallas se actualizan. */
    val version = mutableIntStateOf(0)

    private val file get() = File(AppDirs.data, "recordings.json")
    private val active = ConcurrentHashMap<String, Thread>()
    private val stopFlags = ConcurrentHashMap<String, Boolean>()
    /** Cuentas guardadas (para grabar una programada se necesita la contraseña del servidor). */
    @Volatile var accounts: () -> List<Account> = { emptyList() }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "grabaciones").apply { isDaemon = true } }
    @Volatile private var started = false

    @Synchronized
    fun all(): List<Rec> = runCatching {
        val arr = SafeFile.read(file) { JSONArray(it) } ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun saveAll(list: List<Rec>) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        try { SafeFile.write(file, arr.toString()) } catch (e: Exception) { Log.w("No se pudo guardar la lista de grabaciones", e) }
        SwingUtilities.invokeLater { version.intValue++ }
    }

    @Synchronized
    internal fun update(id: String, f: (Rec) -> Rec) = saveAll(all().map { if (it.id == id) f(it) else it })

    fun byId(id: String) = all().firstOrNull { it.id == id }

    /** ¿Este canal se está grabando ahora? */
    fun recordingNow(accKey: String, chId: String) =
        all().firstOrNull { it.acc == accKey && it.chId == chId && it.status == Status.RECORDING }

    /** ¿Este programa ya tiene grabación (programada o en curso)? */
    fun hasFor(accKey: String, chId: String, start: Long) = all().any {
        it.acc == accKey && it.chId == chId && (it.status == Status.SCHEDULED || it.status == Status.RECORDING) &&
            kotlin.math.abs(it.start - start) < 60_000
    }

    // ── Dónde se guardan ──

    /** Carpeta elegida o, por defecto, Videos\JOX3 TV. */
    fun folder(): File {
        val saved = Settings.string("rec.folder")
        val f = if (saved.isNotBlank()) File(saved) else File(System.getProperty("user.home"), "Videos/JOX3 TV")
        return f
    }

    fun setFolder(f: File) { Settings.put("rec.folder", f.absolutePath); SwingUtilities.invokeLater { version.intValue++ } }

    fun freeBytes(): Long = runCatching { folder().let { if (it.exists()) it else it.parentFile ?: it }.usableSpace }.getOrDefault(0L)

    // ── Programar / grabar ahora ──

    /** Graba [ch] de [start] a [end]. Si ya empezó, arranca ahora. Devuelve un mensaje para mostrar. */
    fun schedule(acc: Account, ch: Channel, title: String, start: Long, end: Long): String {
        val now = System.currentTimeMillis()
        if (end <= now + 30_000) return "Ese programa ya terminó"
        if (hasFor(acc.key, ch.id, start)) return "Ya está programada"
        val dir = folder()
        runCatching { dir.mkdirs() }
        val free = freeBytes()
        if (free in 1 until 500L * 1024 * 1024) return "Poco espacio en ${dir.absolutePath}: libera espacio o elige otra carpeta"
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US)
        val clean = { s: String -> s.replace(Regex("[^\\p{L}\\p{N} ._-]+"), " ").replace(Regex("\\s+"), " ").trim() }
        val safe = clean(title.ifBlank { ch.name }).take(60)
        val f = File(dir, "${fmt.format(java.util.Date(maxOf(start, now)))} - ${clean(ch.name).take(30)} - $safe.ts")
        val r = Rec("r" + java.lang.Long.toHexString(now) + (100..999).random(), acc.key, ch.id, ch.name, ch.logo,
            title.ifBlank { ch.name }, maxOf(start, now), end + 2 * 60_000, f.absolutePath, Status.SCHEDULED)
        synchronized(this) { saveAll(all() + r) }
        val fmtH = java.text.SimpleDateFormat("h:mm a", java.util.Locale.forLanguageTag("es"))
        return if (start <= now + 30_000) { begin(r.id, acc); "⏺ Grabando «${r.title}» hasta las ${fmtH.format(java.util.Date(end))}" }
        else "⏺ Programada: «${r.title}» a las ${fmtH.format(java.util.Date(start))}. Deja el programa abierto (puede estar minimizado)."
    }

    fun stop(id: String) {
        val r = byId(id) ?: return
        if (r.status == Status.SCHEDULED) update(id) { it.copy(status = Status.CANCELED) }
        else stopFlags[id] = true
    }

    fun delete(id: String) {
        stop(id)
        val r = byId(id)
        // Si estaba grabando, se espera un momento a que suelte el archivo
        Thread {
            active[id]?.join(5_000)
            r?.let { runCatching { File(it.file).delete() } }
            synchronized(this) { saveAll(all().filter { it.id != id }) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Al abrir el programa: las que quedaron "grabando" se cerraron a medias y las programadas
     * se revisan cada 15 s para empezar a su hora.
     */
    fun start(accounts: () -> List<Account>) {
        this.accounts = accounts
        if (started) return
        started = true
        val now = System.currentTimeMillis()
        all().forEach { r ->
            when {
                r.status == Status.RECORDING && !active.containsKey(r.id) ->
                    update(r.id) { it.copy(status = if (File(it.file).length() > 100_000) Status.DONE else Status.FAILED, error = "Se cortó (se cerró el programa)") }
                r.status == Status.SCHEDULED && r.end < now -> update(r.id) { it.copy(status = Status.FAILED, error = "El programa estaba cerrado a esa hora") }
            }
        }
        timer.scheduleWithFixedDelay({ runCatching { tick() } }, 3, 15, TimeUnit.SECONDS)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        all().filter { it.status == Status.SCHEDULED && it.start - 30_000 <= now && !active.containsKey(it.id) }.forEach { r ->
            if (r.end < now) { update(r.id) { it.copy(status = Status.FAILED, error = "El programa estaba cerrado a esa hora") }; return@forEach }
            val acc = accounts().firstOrNull { it.key == r.acc }
            if (acc == null) update(r.id) { it.copy(status = Status.FAILED, error = "Ese servidor ya no está en el equipo") }
            else begin(r.id, acc)
        }
    }

    /** ¿Hay alguna grabando? (para avisar antes de cerrar). */
    fun busy() = active.isNotEmpty()

    private fun begin(id: String, acc: Account) {
        if (active.containsKey(id)) return
        val t = Thread({ try { record(id, acc) } finally { active.remove(id) } }, "grabando-$id").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
        active[id] = t
        t.start()
    }

    private fun record(id: String, acc: Account) {
        val r = byId(id) ?: return
        update(id) { it.copy(status = Status.RECORDING) }
        Notify.show("⏺ JOX3 TV está grabando", "${r.title} · ${r.chName}")
        val out = File(r.file).apply { parentFile?.mkdirs() }
        val tsUrl = XtreamApi(acc).liveTsUrl(r.chId)
        val client = Net.api.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        var total = out.length()
        var lastSave = 0L
        var failures = 0
        var error = ""
        try {
            FileOutputStream(out, true).use { fos ->
                while (System.currentTimeMillis() < r.end && stopFlags[id] != true) {
                    try {
                        val req = Request.Builder().url(tsUrl).header("User-Agent", Net.PLAYER_USER_AGENT).build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                            val body = resp.body ?: throw RuntimeException("sin señal")
                            val type = resp.header("Content-Type").orEmpty()
                            val input = BufferedInputStream(body.byteStream(), 256 * 1024)
                            input.mark(16)
                            val head = ByteArray(7); val n = input.read(head); input.reset()
                            if (type.contains("mpegurl", true) || (n == 7 && String(head) == "#EXTM3U")) {
                                total = recordHls(resp.request.url.toString(), input.readBytes().toString(Charsets.UTF_8), client, fos, r, id, total)
                                return@use
                            }
                            val buf = ByteArray(128 * 1024)
                            while (System.currentTimeMillis() < r.end && stopFlags[id] != true) {
                                val k = input.read(buf); if (k < 0) break
                                fos.write(buf, 0, k); total += k
                                failures = 0
                                if (System.currentTimeMillis() - lastSave > 15_000) {
                                    lastSave = System.currentTimeMillis()
                                    val t = total
                                    update(id) { it.copy(bytes = t) }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        failures++; error = e.message ?: "error"
                        Log.w("Grabación: $error")
                        if (failures > 30) break
                        Thread.sleep(3000)
                    }
                }
            }
        } catch (e: Exception) { error = e.message ?: "error" }
        stopFlags.remove(id)
        val ok = total > 100_000
        val t = total
        update(id) { it.copy(status = if (ok) Status.DONE else Status.FAILED, bytes = t,
            end = if (System.currentTimeMillis() < it.end) System.currentTimeMillis() else it.end,
            error = if (ok) "" else "No llegó señal del canal ($error). Tu cuenta podría no permitir otra conexión.") }
        Notify.show(if (ok) "✓ Grabación lista" else "✗ La grabación falló", r.title)
    }

    /** Servidores que solo dan HLS: se piden los segmentos nuevos de la lista una y otra vez. */
    private fun recordHls(url: String, first: String, client: OkHttpClient, out: FileOutputStream, r: Rec, id: String, start: Long): Long {
        var total = start
        var playlistUrl = url
        var text = first
        // Lista maestra: tomar la primera variante
        if (text.contains("#EXT-X-STREAM-INF")) {
            val v = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return total
            playlistUrl = java.net.URI(url).resolve(v).toString()
            text = ""
        }
        val seen = LinkedHashSet<String>()
        while (System.currentTimeMillis() < r.end && stopFlags[id] != true) {
            if (text.isEmpty()) {
                text = client.newCall(Request.Builder().url(playlistUrl).header("User-Agent", Net.PLAYER_USER_AGENT).build())
                    .execute().use { it.body?.string().orEmpty() }
            }
            val segs = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            text = ""
            for (s in segs) {
                val u = java.net.URI(playlistUrl).resolve(s).toString()
                if (!seen.add(u)) continue
                client.newCall(Request.Builder().url(u).header("User-Agent", Net.PLAYER_USER_AGENT).build())
                    .execute().use { resp -> resp.body?.byteStream()?.use { inp -> total += inp.copyTo(out, 128 * 1024) } }
                if (stopFlags[id] == true || System.currentTimeMillis() >= r.end) break
            }
            while (seen.size > 200) seen.remove(seen.first())
            val t = total
            update(id) { it.copy(bytes = t) }
            Thread.sleep(3000)
        }
        return total
    }

    private fun toJson(r: Rec) = JSONObject().put("id", r.id).put("acc", r.acc).put("ch", r.chId).put("name", r.chName)
        .put("logo", r.logo).put("title", r.title).put("start", r.start).put("end", r.end).put("file", r.file)
        .put("status", r.status.name).put("bytes", r.bytes).put("error", r.error)

    private fun fromJson(o: JSONObject) = Rec(o.optString("id"), o.optString("acc"), o.optString("ch"), o.optString("name"),
        o.optString("logo"), o.optString("title"), o.optLong("start"), o.optLong("end"), o.optString("file"),
        runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED), o.optLong("bytes"), o.optString("error"))
}
