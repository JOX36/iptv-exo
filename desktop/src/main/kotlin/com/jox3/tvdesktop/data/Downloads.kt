package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.Notify
import com.jox3.tvdesktop.core.SafeFile
import com.jox3.tvdesktop.core.Settings
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Descargas de películas y episodios para verlos sin internet. Misma lógica que la app Android
 * (tv/data/Downloads.kt):
 *
 * - Se bajan de a una (las cuentas IPTV suelen permitir pocas conexiones a la vez).
 * - Se guardan en **Videos\JOX3 TV** (o la carpeta que se elija); cada serie en su subcarpeta.
 * - Si se corta la conexión, siguen desde donde iban. Se pueden pausar y reanudar.
 * - En el PC bajan mientras el programa está abierto (puede estar minimizado); al volver a
 *   abrirlo, siguen solas.
 */
object Downloads {
    enum class Status { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

    data class Dl(
        val id: String, val acc: String, val kind: String, val streamId: String, val ext: String,
        val title: String, val series: String = "", val seriesId: String = "", val season: Int = 0, val num: Int = 0,
        val image: String = "", val status: Status = Status.QUEUED, val bytes: Long = 0L, val total: Long = 0L,
        val location: String = "", val error: String = "", val ts: Long = System.currentTimeMillis(),
    ) {
        val fraction get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        val percent get() = (fraction * 100).toInt()
        /** Nombre para mostrar: "Película" o "Serie · T1E2 - Título" (el mismo formato de Android). */
        val label get() = if (kind == "episode") "$series · T${season}E$num" + (if (title.isNotBlank()) " - $title" else "") else title
    }

    const val FOLDER = "JOX3 TV"
    /** Sube cuando cambia la lista: las pantallas se actualizan. */
    val version = mutableIntStateOf(0)

    @Volatile var fileOverride: File? = null
    private val file get() = fileOverride ?: File(AppDirs.data, "downloads.json")
    private val stopFlags = ConcurrentHashMap<String, Boolean>()
    @Volatile var currentId: String? = null; private set
    @Volatile private var worker: Thread? = null
    /** Cuentas guardadas (para descargar se necesita la contraseña del servidor). */
    @Volatile var accounts: () -> List<Account> = { emptyList() }

    @Synchronized
    fun all(): List<Dl> = runCatching {
        val arr = SafeFile.read(file) { JSONArray(it) } ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun saveAll(list: List<Dl>) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        try { SafeFile.write(file, arr.toString()) } catch (e: Exception) { Log.w("No se pudo guardar la lista de descargas", e) }
        SwingUtilities.invokeLater { version.intValue++ }
    }

    @Synchronized
    internal fun update(id: String, f: (Dl) -> Dl) = saveAll(all().map { if (it.id == id) f(it) else it })

    fun byId(id: String) = all().firstOrNull { it.id == id }

    private fun key(acc: String, kind: String, streamId: String) = "d" + acc.take(10) + kind.take(1) + streamId

    /** Descarga de este título en esta cuenta (si existe). */
    fun find(acc: Account, kind: String, streamId: String): Dl? = byId(key(acc.key, kind, streamId))

    // ── Dónde se guardan ──

    fun folder(): File {
        val saved = Settings.string("dl.folder")
        return if (saved.isNotBlank()) File(saved) else File(System.getProperty("user.home"), "Videos/$FOLDER")
    }

    fun setFolder(f: File) { Settings.put("dl.folder", f.absolutePath); SwingUtilities.invokeLater { version.intValue++ } }

    fun freeBytes(): Long = runCatching {
        var f: File? = folder()
        while (f != null && !f.exists()) f = f.parentFile
        (f ?: File(System.getProperty("user.home"))).usableSpace
    }.getOrDefault(0L)

    // ── Agregar ──

    fun movie(acc: Account, id: String, name: String, poster: String, ext: String): String =
        enqueue(listOf(Dl(key(acc.key, "movie", id), acc.key, "movie", id, ext.ifBlank { "mp4" }, name, image = poster)))

    fun episodes(acc: Account, seriesId: String, seriesName: String, cover: String, eps: List<Episode>): String =
        enqueue(eps.map { e ->
            Dl(key(acc.key, "episode", e.id), acc.key, "episode", e.id, e.ext.ifBlank { "mkv" },
                e.title.takeIf { !it.contains(seriesName, true) }.orEmpty(), seriesName, seriesId, e.season, e.num, e.image.ifBlank { cover })
        })

    /** Pone en cola; lo que ya está descargado o en cola no se repite. Devuelve un mensaje para mostrar. */
    @Synchronized
    internal fun enqueue(items: List<Dl>, autoStart: Boolean = true): String {
        if (items.isEmpty()) return "No hay nada para descargar"
        val hls = items.filter { it.ext.equals("m3u8", true) }
        val ok = items - hls.toSet()
        val current = all().associateBy { it.id }
        var added = 0
        val list = current.toMutableMap()
        ok.forEach { d ->
            val old = current[d.id]
            when {
                old == null -> { list[d.id] = d; added++ }
                old.status == Status.FAILED || old.status == Status.PAUSED -> { list[d.id] = old.copy(status = Status.QUEUED, error = ""); added++ }
            }
        }
        saveAll(list.values.sortedBy { it.ts })
        if (added > 0 && autoStart) start()
        return when {
            ok.isEmpty() -> "Este título no se puede descargar (el servidor lo da solo en transmisión)"
            added == 0 -> if (ok.size == 1) "Ya está en Descargas" else "Esos episodios ya están en Descargas"
            ok.size == 1 -> "⬇ Descargando «${ok[0].label}». Lo ves en Ajustes → Descargas"
            else -> "⬇ $added episodios en cola. Se bajan de a uno: los ves en Ajustes → Descargas"
        }
    }

    fun pause(id: String) {
        val d = byId(id) ?: return
        if (d.status == Status.DOWNLOADING) stopFlags[id] = true
        if (d.status == Status.QUEUED || d.status == Status.DOWNLOADING) update(id) { it.copy(status = Status.PAUSED) }
    }

    fun pauseAll() = all().filter { it.status == Status.QUEUED || it.status == Status.DOWNLOADING }.forEach { pause(it.id) }

    fun resume(id: String) {
        update(id) { if (it.status == Status.PAUSED || it.status == Status.FAILED) it.copy(status = Status.QUEUED, error = "") else it }
        start()
    }

    fun delete(id: String) {
        val d = byId(id) ?: return
        stopFlags[id] = true
        Thread {
            if (currentId == id) Thread.sleep(800)
            if (d.location.isNotBlank()) runCatching { File(d.location).delete() }
            synchronized(this) { saveAll(all().filter { it.id != id }) }
        }.apply { isDaemon = true }.start()
    }

    /** ¿Está bajando algo ahora? */
    fun busy() = currentId != null

    /** Al abrir el programa: lo que quedó "descargando" vuelve a la cola y sigue. */
    fun resumePending(accounts: () -> List<Account>) {
        this.accounts = accounts
        val list = all()
        if (list.none { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }) return
        if (currentId == null) saveAll(list.map { if (it.status == Status.DOWNLOADING) it.copy(status = Status.QUEUED) else it })
        start()
    }

    @Synchronized
    private fun start() {
        if (worker?.isAlive == true) return
        worker = Thread({ runQueue() }, "descargas").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private fun runQueue() {
        var done = 0
        while (true) {
            val next = all().firstOrNull { it.status == Status.QUEUED } ?: break
            currentId = next.id
            if (download(next)) done++
            currentId = null
        }
        if (done > 0) Notify.show("✓ Descargas listas", if (done == 1) "1 título listo para ver sin internet" else "$done títulos listos para ver sin internet")
    }

    // ── Archivos ──

    private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._()'-]+"), " ").replace(Regex("\\s+"), " ").trim().take(80).trimEnd('.').ifBlank { "video" }

    internal fun fileName(d: Dl) = safe(
        if (d.kind == "episode") "${d.series} - T%02dE%02d".format(java.util.Locale.US, d.season, d.num) + (if (d.title.isNotBlank()) " - ${d.title}" else "")
        else d.title
    ) + "." + d.ext.lowercase(java.util.Locale.US)

    private fun create(d: Dl): String {
        val dir = (if (d.kind == "episode") File(folder(), safe(d.series)) else folder()).apply { mkdirs() }
        var f = File(dir, fileName(d)); var n = 2
        while (f.exists()) { f = File(dir, fileName(d).substringBeforeLast('.') + " ($n)." + d.ext); n++ }
        f.createNewFile()
        return f.absolutePath
    }

    private class NoSpace(msg: String) : Exception(msg)

    /** Baja un título. true = terminó bien. */
    private fun download(d0: Dl): Boolean {
        val id = d0.id
        stopFlags.remove(id)
        val acc = accounts().firstOrNull { it.key == d0.acc }
        if (acc == null) { fail(id, "Ese servidor ya no está en el equipo"); return false }
        var d = d0
        if (d.location.isBlank() || !File(d.location).exists()) {
            val loc = runCatching { create(d) }.getOrElse { fail(id, "No se pudo crear el archivo: ${it.message}"); return false }
            update(id) { it.copy(location = loc, bytes = 0) }
            d = d.copy(location = loc, bytes = 0)
        }
        update(id) { it.copy(status = Status.DOWNLOADING, error = "") }
        val api = XtreamApi(acc)
        val url = if (d.kind == "episode") api.episodeUrl(d.streamId, d.ext) else api.movieUrl(d.streamId, d.ext)
        val client = Net.api.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        var failures = 0
        var total = d.total
        val f = File(d.location)
        while (stopFlags[id] != true) {
            var have = if (f.exists()) f.length() else 0L
            try {
                val req = Request.Builder().url(url).header("User-Agent", Net.PLAYER_USER_AGENT)
                    .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 416 && have > 0) { total = have; return@use }
                    if (!resp.isSuccessful) throw RuntimeException(when (resp.code) {
                        401, 403 -> "el servidor no dejó descargar (HTTP ${resp.code}); tu cuenta podría no permitir otra conexión"
                        404 -> "el servidor ya no tiene este título (HTTP 404)"
                        else -> "HTTP ${resp.code}"
                    })
                    val body = resp.body ?: throw RuntimeException("respuesta vacía")
                    val partial = resp.code == 206
                    if (!partial) have = 0   // el servidor no permite seguir: se empieza de nuevo
                    val len = body.contentLength()
                    if (len > 0) total = have + len
                    if (len > 0 && len > freeBytes() + 50_000_000L) throw NoSpace("No hay espacio: hacen falta ${len / 1_000_000} MB")
                    update(id) { it.copy(total = total, bytes = have) }
                    FileOutputStream(f, partial).use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var last = 0L
                        while (stopFlags[id] != true) {
                            val k = input.read(buf); if (k < 0) break
                            out.write(buf, 0, k); have += k
                            failures = 0
                            val now = System.currentTimeMillis()
                            if (now - last > 1500) { last = now; val h = have; update(id) { it.copy(bytes = h) } }
                        }
                    }
                }
                if (stopFlags[id] == true) break
                have = f.length()
                if (total <= 0 || have >= total) {
                    update(id) { it.copy(status = Status.DONE, bytes = have, total = if (total > 0) total else have, error = "") }
                    return true
                }
                throw RuntimeException("se cortó la conexión")
            } catch (e: NoSpace) {
                fail(id, e.message ?: "Sin espacio"); return false
            } catch (e: Exception) {
                failures++
                val msg = e.message ?: "error"
                Log.w("Descarga $id: $msg")
                if (failures > 6 || msg.contains("HTTP 404")) { fail(id, "No se pudo descargar: $msg"); return false }
                update(id) { it.copy(error = "Reintentando… ($msg)") }
                Thread.sleep(4000L * failures)
            }
        }
        stopFlags.remove(id)
        update(id) { if (it.status == Status.DOWNLOADING) it.copy(status = Status.PAUSED) else it }
        return false
    }

    private fun fail(id: String, msg: String) = update(id) { it.copy(status = Status.FAILED, error = msg) }

    private fun toJson(d: Dl) = JSONObject().put("id", d.id).put("acc", d.acc).put("kind", d.kind).put("sid", d.streamId)
        .put("ext", d.ext).put("title", d.title).put("series", d.series).put("seriesId", d.seriesId).put("season", d.season)
        .put("num", d.num).put("image", d.image).put("status", d.status.name).put("bytes", d.bytes).put("total", d.total)
        .put("loc", d.location).put("error", d.error).put("ts", d.ts)

    private fun fromJson(o: JSONObject) = Dl(o.optString("id"), o.optString("acc"), o.optString("kind"), o.optString("sid"),
        o.optString("ext"), o.optString("title"), o.optString("series"), o.optString("seriesId"), o.optInt("season"),
        o.optInt("num"), o.optString("image"), runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED),
        o.optLong("bytes"), o.optLong("total"), o.optString("loc"), o.optString("error"), o.optLong("ts"))
}
