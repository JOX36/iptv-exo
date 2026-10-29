package com.jox3.iptvexo.tv.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Descargas de películas y episodios para verlos sin internet.
 *
 * - Se bajan de a una (las cuentas IPTV suelen permitir pocas conexiones a la vez).
 * - Se guardan en la memoria del equipo, en la carpeta pública **Películas/JOX3 TV**
 *   (las series en una subcarpeta con su nombre): se ven en la galería y en el explorador.
 * - Si se corta la conexión, siguen desde donde iban. Se pueden pausar y reanudar.
 * - Las baja un servicio en primer plano: siguen aunque se salga de la app.
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
        /** Nombre para mostrar: "Película" o "Serie · T1E2 - Título". */
        val label get() = if (kind == "episode") "$series · T${season}E$num" + (if (title.isNotBlank()) " - $title" else "") else title
    }

    private const val PREFS = "tv_downloads"
    internal const val NOTIF_CHANNEL = "descargas"
    const val FOLDER = "JOX3 TV"
    /** Sube cuando cambia la lista: las pantallas se actualizan. */
    val version = mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Dl> = synchronized(this) {
        runCatching {
            val arr = JSONArray(prefs(ctx).getString("list", "[]"))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(ctx: Context, list: List<Dl>) = synchronized(this) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).post { version.intValue++ }
    }

    internal fun update(ctx: Context, id: String, f: (Dl) -> Dl) = synchronized(this) {
        saveAll(ctx, all(ctx).map { if (it.id == id) f(it) else it })
    }

    fun byId(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    private fun key(acc: String, kind: String, streamId: String) = "d" + acc.take(10) + kind.take(1) + streamId

    /** Descarga de este título en esta cuenta (si existe). */
    fun find(ctx: Context, acc: Account, kind: String, streamId: String): Dl? = byId(ctx, key(acc.key, kind, streamId))

    // ── Agregar ──
    fun movie(ctx: Context, acc: Account, id: String, name: String, poster: String, ext: String): String =
        enqueue(ctx, listOf(Dl(key(acc.key, "movie", id), acc.key, "movie", id, ext.ifBlank { "mp4" }, name, image = poster)))

    fun episodes(ctx: Context, acc: Account, seriesId: String, seriesName: String, cover: String, eps: List<Episode>): String =
        enqueue(ctx, eps.map { e ->
            Dl(key(acc.key, "episode", e.id), acc.key, "episode", e.id, e.ext.ifBlank { "mkv" }, e.title.takeIf { !it.contains(seriesName, true) }.orEmpty(),
                seriesName, seriesId, e.season, e.num, e.image.ifBlank { cover })
        })

    /** Pone en cola; lo que ya está descargado o en cola no se repite. Devuelve un mensaje para mostrar. */
    private fun enqueue(ctx: Context, items: List<Dl>): String {
        if (items.isEmpty()) return "No hay nada para descargar"
        val hls = items.filter { it.ext.equals("m3u8", true) }
        val ok = items - hls.toSet()
        val current = all(ctx).associateBy { it.id }
        var added = 0
        val list = current.toMutableMap()
        ok.forEach { d ->
            val old = current[d.id]
            when {
                old == null -> { list[d.id] = d; added++ }
                old.status == Status.FAILED || old.status == Status.PAUSED -> { list[d.id] = old.copy(status = Status.QUEUED, error = ""); added++ }
            }
        }
        saveAll(ctx, list.values.sortedBy { it.ts })
        if (added > 0) start(ctx)
        return when {
            ok.isEmpty() -> "Este título no se puede descargar (el servidor lo da solo en transmisión)"
            added == 0 -> if (ok.size == 1) "Ya está en Descargas" else "Esos episodios ya están en Descargas"
            ok.size == 1 -> "⬇ Descargando «${ok[0].label}». Lo ves en Ajustes → Descargas"
            else -> "⬇ $added episodios en cola. Se bajan de a uno: los ves en Ajustes → Descargas"
        }
    }

    fun pause(ctx: Context, id: String) {
        val d = byId(ctx, id) ?: return
        if (d.status == Status.DOWNLOADING) DownloadService.stopFlags[id] = true
        if (d.status == Status.QUEUED || d.status == Status.DOWNLOADING) update(ctx, id) { it.copy(status = Status.PAUSED) }
    }

    fun resume(ctx: Context, id: String) {
        update(ctx, id) { if (it.status == Status.PAUSED || it.status == Status.FAILED) it.copy(status = Status.QUEUED, error = "") else it }
        start(ctx)
    }

    fun delete(ctx: Context, id: String) {
        val d = byId(ctx, id) ?: return
        DownloadService.stopFlags[id] = true
        // Se espera un momento a que el servicio suelte el archivo
        Thread {
            if (DownloadService.currentId == id) Thread.sleep(800)
            Storage.delete(ctx, d.location)
            synchronized(this) { saveAll(ctx, all(ctx).filter { it.id != id }) }
        }.start()
    }

    /** Al abrir la app: lo que quedó "descargando" (se cerró o se apagó) vuelve a la cola y sigue. */
    fun resumePending(ctx: Context) {
        val list = all(ctx)
        if (list.none { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }) return
        if (DownloadService.currentId == null)
            saveAll(ctx, list.map { if (it.status == Status.DOWNLOADING) it.copy(status = Status.QUEUED) else it })
        start(ctx)
    }

    internal fun start(ctx: Context) {
        val i = Intent(ctx, DownloadService::class.java)
        runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
            .onFailure { Log.w("JOX3", "Descargas: no se pudo iniciar el servicio: ${it.message}") }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(NOTIF_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(NOTIF_CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
    }

    /** Lo que se le pasa al reproductor (content:// o file://). */
    fun playUri(d: Dl): String = if (d.location.startsWith("content:")) d.location else Uri.fromFile(File(d.location)).toString()

    /** Dónde quedan, para mostrar. */
    fun whereLabel(ctx: Context): String =
        if (Storage.publicFolder(ctx)) "Memoria del equipo → Películas (Movies) → $FOLDER"
        else "Memoria del equipo → Android/data/${ctx.packageName}/files/Movies/$FOLDER"

    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes }.getOrDefault(0L)

    private fun toJson(d: Dl) = JSONObject().put("id", d.id).put("acc", d.acc).put("kind", d.kind).put("sid", d.streamId)
        .put("ext", d.ext).put("title", d.title).put("series", d.series).put("seriesId", d.seriesId).put("season", d.season)
        .put("num", d.num).put("image", d.image).put("status", d.status.name).put("bytes", d.bytes).put("total", d.total)
        .put("loc", d.location).put("error", d.error).put("ts", d.ts)

    private fun fromJson(o: JSONObject) = Dl(o.optString("id"), o.optString("acc"), o.optString("kind"), o.optString("sid"),
        o.optString("ext"), o.optString("title"), o.optString("series"), o.optString("seriesId"), o.optInt("season"),
        o.optInt("num"), o.optString("image"), runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED),
        o.optLong("bytes"), o.optLong("total"), o.optString("loc"), o.optString("error"), o.optLong("ts"))

    // ── Archivos ──
    internal object Storage {
        /** ¿Va a la carpeta pública Películas? (Android 10+: siempre; antes, si se dio el permiso). */
        fun publicFolder(ctx: Context) = Build.VERSION.SDK_INT >= 29 ||
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._()'-]+"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "video" }

        fun fileName(d: Dl) = safe(
            if (d.kind == "episode") "${d.series} - T%02dE%02d".format(java.util.Locale.US, d.season, d.num) + (if (d.title.isNotBlank()) " - ${d.title}" else "")
            else d.title
        ) + "." + d.ext.lowercase(java.util.Locale.US)

        private fun subFolder(d: Dl) = if (d.kind == "episode") "$FOLDER/${safe(d.series)}" else FOLDER

        private fun mime(ext: String) = when (ext.lowercase(java.util.Locale.US)) {
            "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"; "ts" -> "video/mp2t"; "webm" -> "video/webm"
            "mov" -> "video/quicktime"; "flv" -> "video/x-flv"; "wmv" -> "video/x-ms-wmv"; "m4v" -> "video/x-m4v"
            else -> "video/mp4"
        }

        /** Crea el archivo de destino y devuelve su ubicación (content:// en Android 10+, ruta antes). */
        fun create(ctx: Context, d: Dl): String {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(d))
                    put(MediaStore.MediaColumns.MIME_TYPE, mime(d.ext))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + subFolder(d))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    ?: throw RuntimeException("No se pudo crear el archivo")
                return uri.toString()
            }
            val base = if (publicFolder(ctx)) @Suppress("DEPRECATION") Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                else ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
            val dir = File(base, subFolder(d)).apply { mkdirs() }
            var f = File(dir, fileName(d)); var n = 2
            while (f.exists()) { f = File(dir, fileName(d).substringBeforeLast('.') + " ($n)." + d.ext); n++ }
            f.createNewFile()
            return f.absolutePath
        }

        /** Tamaño actual (para seguir desde donde iba). -1 si el archivo ya no existe. */
        fun size(ctx: Context, loc: String): Long = runCatching {
            if (loc.startsWith("content:")) ctx.contentResolver.openFileDescriptor(Uri.parse(loc), "r")?.use { it.statSize } ?: -1L
            else File(loc).let { if (it.exists()) it.length() else -1L }
        }.getOrDefault(-1L)

        /** Para escribir al final (o desde cero si [truncate]). */
        fun open(ctx: Context, loc: String, truncate: Boolean): OutputStream {
            if (!loc.startsWith("content:")) return FileOutputStream(File(loc), !truncate)
            val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(loc), if (truncate) "rwt" else "rw")
                ?: throw RuntimeException("No se pudo abrir el archivo")
            val fos = FileOutputStream(pfd.fileDescriptor)
            if (!truncate) fos.channel.position(pfd.statSize)
            return object : OutputStream() {
                override fun write(b: Int) = fos.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = fos.write(b, off, len)
                override fun flush() = fos.flush()
                override fun close() { runCatching { fos.close() }; runCatching { pfd.close() } }
            }
        }

        /** Terminada: visible en la galería y en otras apps. */
        fun finish(ctx: Context, loc: String) {
            runCatching {
                if (loc.startsWith("content:")) ctx.contentResolver.update(Uri.parse(loc), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                else android.media.MediaScannerConnection.scanFile(ctx, arrayOf(loc), null, null)
            }
        }

        fun delete(ctx: Context, loc: String) {
            if (loc.isBlank()) return
            runCatching { if (loc.startsWith("content:")) ctx.contentResolver.delete(Uri.parse(loc), null, null) else File(loc).delete() }
        }
    }
}

/** Servicio que baja la cola de a un título, con la notificación de avance. */
class DownloadService : Service() {
    companion object {
        val stopFlags = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        @Volatile var currentId: String? = null
        @Volatile private var worker: Thread? = null
        private const val NOTIF = 78
        private const val NOTIF_DONE = 79
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Downloads.ensureChannel(this)
        startForeground(NOTIF, notification("Preparando descarga…", -1))
        synchronized(DownloadService) {
            if (worker?.isAlive != true) worker = Thread { runQueue() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        var done = 0
        while (true) {
            val next = Downloads.all(this).firstOrNull { it.status == Downloads.Status.QUEUED } ?: break
            currentId = next.id
            if (download(next)) done++
            currentId = null
        }
        if (done > 0) notifyDone(done)
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
        stopSelf()
    }

    private fun notification(text: String, percent: Int): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, Downloads.NOTIF_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("⬇ JOX3 TV está descargando").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .apply { open?.let { setContentIntent(it) } }.build()
    }

    private fun notify(text: String, percent: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIF, notification(text, percent))
    }

    private fun notifyDone(n: Int) {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, Downloads.NOTIF_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIF_DONE,
                b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("✓ Descargas listas")
                    .setContentText(if (n == 1) "1 título listo para ver sin internet" else "$n títulos listos para ver sin internet")
                    .setAutoCancel(true).apply { open?.let { setContentIntent(it) } }.build())
        }
    }

    /** Baja un título. true = terminó bien. */
    private fun download(d0: Downloads.Dl): Boolean {
        val id = d0.id
        stopFlags.remove(id)
        val acc = AccountStore(this).all().firstOrNull { it.key == d0.acc }
        if (acc == null) { fail(id, "Ese servidor ya no está en el equipo"); return false }
        var d = d0
        // Archivo de destino (se crea la primera vez; si lo borraron, se vuelve a crear)
        if (d.location.isBlank() || Downloads.Storage.size(this, d.location) < 0) {
            val loc = runCatching { Downloads.Storage.create(this, d) }.getOrElse { fail(id, "No se pudo crear el archivo: ${it.message}"); return false }
            Downloads.update(this, id) { it.copy(location = loc, bytes = 0) }
            d = d.copy(location = loc, bytes = 0)
        }
        Downloads.update(this, id) { it.copy(status = Downloads.Status.DOWNLOADING, error = "") }
        val host = acc.host.trimEnd('/')
        val url = if (d.kind == "episode") "$host/series/${acc.user}/${acc.pass}/${d.streamId}.${d.ext}"
            else "$host/movie/${acc.user}/${acc.pass}/${d.streamId}.${d.ext}"
        val client = com.jox3.iptvexo.NetClient.player().newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        var failures = 0
        var lastError = ""
        var total = d.total
        while (stopFlags[id] != true) {
            var have = Downloads.Storage.size(this, d.location).coerceAtLeast(0)
            try {
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", com.jox3.iptvexo.NetClient.PLAYER_USER_AGENT)
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
                    if (len > 0 && len > Downloads.freeBytes() + 50_000_000L)
                        throw NoSpace("No hay espacio: hacen falta ${len / 1_000_000} MB")
                    Downloads.update(this, id) { it.copy(total = total, bytes = have) }
                    Downloads.Storage.open(this, d.location, truncate = !partial).use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var last = 0L
                        while (stopFlags[id] != true) {
                            val k = input.read(buf); if (k < 0) break
                            out.write(buf, 0, k); have += k
                            failures = 0
                            val now = System.currentTimeMillis()
                            if (now - last > 1500) {
                                last = now
                                val pct = if (total > 0) (have * 100 / total).toInt() else -1
                                Downloads.update(this, id) { it.copy(bytes = have) }
                                val queued = Downloads.all(this).count { it.status == Downloads.Status.QUEUED }
                                notify(d.label + (if (pct >= 0) " · $pct%" else " · ${have / 1_000_000} MB") +
                                    (if (queued > 0) " · $queued en cola" else ""), pct)
                            }
                        }
                    }
                }
                if (stopFlags[id] == true) break
                have = Downloads.Storage.size(this, d.location).coerceAtLeast(0)
                if (total <= 0 || have >= total) {
                    Downloads.Storage.finish(this, d.location)
                    Downloads.update(this, id) { it.copy(status = Downloads.Status.DONE, bytes = have, total = if (total > 0) total else have, error = "") }
                    return true
                }
                // Se cortó antes de terminar: se sigue desde donde iba
                throw RuntimeException("se cortó la conexión")
            } catch (e: NoSpace) {
                fail(id, e.message ?: "Sin espacio"); return false
            } catch (e: Exception) {
                failures++; lastError = e.message ?: "error"
                Log.w("JOX3", "Descarga $id: $lastError")
                if (failures > 6 || lastError.contains("HTTP 404")) { fail(id, "No se pudo descargar: $lastError"); return false }
                Downloads.update(this, id) { it.copy(error = "Reintentando… ($lastError)") }
                Thread.sleep(4000L * failures)
            }
        }
        // Pausada o borrada
        stopFlags.remove(id)
        Downloads.update(this, id) { if (it.status == Downloads.Status.DOWNLOADING) it.copy(status = Downloads.Status.PAUSED) else it }
        return false
    }

    private class NoSpace(msg: String) : Exception(msg)

    private fun fail(id: String, msg: String) {
        Downloads.update(this, id) { it.copy(status = Downloads.Status.FAILED, error = msg) }
    }
}
