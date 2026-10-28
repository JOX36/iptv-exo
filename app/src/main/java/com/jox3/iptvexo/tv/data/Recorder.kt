package com.jox3.iptvexo.tv.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Grabaciones (DVR): guarda la señal del canal tal cual llega (MPEG-TS, ".ts"), sin volver a
 * comprimir: misma calidad y se abre en VLC, MX Player, Kodi, la PC…
 *
 * - "Grabar ahora" o programadas desde la guía (empiezan y terminan solas).
 * - Se guardan en la memoria interna o en una USB / tarjeta (carpeta "Grabaciones" de la app).
 * - Graba un servicio en primer plano: sigue aunque se salga de la app.
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

    private const val PREFS = "tv_recordings"
    internal const val NOTIF_CHANNEL = "grabaciones"
    /** Sube cuando cambia la lista: las pantallas se actualizan. */
    val version = mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Rec> = synchronized(this) {
        runCatching {
            val arr = JSONArray(prefs(ctx).getString("list", "[]"))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(ctx: Context, list: List<Rec>) = synchronized(this) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).post { version.intValue++ }
    }

    internal fun update(ctx: Context, id: String, f: (Rec) -> Rec) = synchronized(this) {
        saveAll(ctx, all(ctx).map { if (it.id == id) f(it) else it })
    }

    fun byId(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    /** ¿Este canal se está grabando ahora? */
    fun recordingNow(ctx: Context, accKey: String, chId: String) =
        all(ctx).firstOrNull { it.acc == accKey && it.chId == chId && it.status == Status.RECORDING }

    /** ¿Este programa ya tiene grabación (programada o en curso)? */
    fun hasFor(ctx: Context, accKey: String, chId: String, start: Long) = all(ctx).any {
        it.acc == accKey && it.chId == chId && (it.status == Status.SCHEDULED || it.status == Status.RECORDING) &&
            kotlin.math.abs(it.start - start) < 60_000
    }

    // ── Dónde se guardan ──
    class Place(val dir: File, val label: String, val removable: Boolean) {
        val freeBytes: Long get() = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
    }

    /** Memoria interna y, si hay, USB o tarjetas conectadas. */
    fun places(ctx: Context): List<Place> {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        val out = ArrayList<Place>()
        dirs.forEachIndexed { i, d ->
            val removable = i > 0 || runCatching { android.os.Environment.isExternalStorageRemovable(d) }.getOrDefault(false)
            out += Place(File(d, "Grabaciones"), if (removable) "USB / tarjeta ${if (i > 1) i else ""}".trim() else "Memoria interna", removable)
        }
        if (out.isEmpty()) out += Place(File(ctx.filesDir, "Grabaciones"), "Memoria interna", false)
        return out
    }

    fun placeIndex(ctx: Context): Int {
        val saved = prefs(ctx).getInt("place", -1)
        val ps = places(ctx)
        return if (saved in ps.indices) saved else ps.indexOfFirst { it.removable }.takeIf { it >= 0 } ?: 0
    }

    fun setPlace(ctx: Context, i: Int) { prefs(ctx).edit().putInt("place", i).apply(); version.intValue++ }

    // ── Programar / grabar ahora ──
    /** Graba [ch] de [start] a [end]. Si ya empezó, arranca ahora. Devuelve un mensaje para mostrar. */
    fun schedule(ctx: Context, acc: Account, ch: Channel, title: String, start: Long, end: Long): String {
        val now = System.currentTimeMillis()
        if (end <= now + 30_000) return "Ese programa ya terminó"
        if (hasFor(ctx, acc.key, ch.id, start)) return "Ya está programada"
        val place = places(ctx)[placeIndex(ctx)]
        if (place.freeBytes in 1 until 500L * 1024 * 1024) return "Poco espacio en ${place.label}: libera espacio o elige otro lugar"
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US)
        val safe = (title.ifBlank { ch.name }).replace(Regex("[^\\p{L}\\p{N} ._-]+"), " ").replace(Regex("\\s+"), " ").trim().take(60)
        val file = File(place.dir, "${fmt.format(java.util.Date(maxOf(start, now)))} - ${ch.name.take(30).replace(Regex("[^\\p{L}\\p{N} ._-]+"), "")} - $safe.ts")
        val r = Rec("r" + java.lang.Long.toHexString(now) + (100..999).random(), acc.key, ch.id, ch.name, ch.logo,
            title.ifBlank { ch.name }, maxOf(start, now), end + 2 * 60_000, file.absolutePath, Status.SCHEDULED)
        saveAll(ctx, all(ctx) + r)
        val fmtH = java.text.SimpleDateFormat("h:mm a", java.util.Locale("es"))
        return if (start <= now + 30_000) { startService(ctx, r.id); "⏺ Grabando «${r.title}» hasta las ${fmtH.format(java.util.Date(end))}" }
        else { alarm(ctx, r); "⏺ Programada: «${r.title}» a las ${fmtH.format(java.util.Date(start))}" }
    }

    fun stop(ctx: Context, id: String) {
        val r = byId(ctx, id) ?: return
        if (r.status == Status.SCHEDULED) { update(ctx, id) { it.copy(status = Status.CANCELED) }; cancelAlarm(ctx, r) }
        else RecordService.stopFlags[id] = true
    }

    fun delete(ctx: Context, id: String) {
        stop(ctx, id)
        byId(ctx, id)?.let { runCatching { File(it.file).delete() } }
        saveAll(ctx, all(ctx).filter { it.id != id })
    }

    internal fun startService(ctx: Context, id: String) {
        val i = Intent(ctx, RecordService::class.java).putExtra("id", id)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            // Android 12+ no deja empezar en segundo plano sin alarma exacta: se avisa
            update(ctx, id) { it.copy(status = Status.FAILED, error = "El equipo no dejó empezar la grabación en segundo plano. Abre la app a esa hora.") }
        }
    }

    // ── Alarmas ──
    private fun pi(ctx: Context, r: Rec, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx, r.id.hashCode(), Intent(ctx, RecordAlarm::class.java).putExtra("id", r.id),
            flags or PendingIntent.FLAG_IMMUTABLE)

    private fun alarm(ctx: Context, r: Rec) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val p = pi(ctx, r, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = r.start - 30_000
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    private fun cancelAlarm(ctx: Context, r: Rec) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        pi(ctx, r, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
    }

    /** Al reiniciar el equipo: volver a programar las pendientes y marcar las que quedaron a medias. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        all(ctx).forEach { r ->
            when {
                r.status == Status.RECORDING && !RecordService.active.containsKey(r.id) ->
                    update(ctx, r.id) { it.copy(status = if (File(it.file).length() > 0) Status.DONE else Status.FAILED, error = "Se cortó (el equipo se apagó)") }
                r.status == Status.SCHEDULED && r.end < now -> update(ctx, r.id) { it.copy(status = Status.FAILED, error = "El equipo estaba apagado") }
                r.status == Status.SCHEDULED && r.start <= now -> startService(ctx, r.id)
                r.status == Status.SCHEDULED -> alarm(ctx, r)
            }
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(NOTIF_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(NOTIF_CHANNEL, "Grabaciones", NotificationManager.IMPORTANCE_LOW))
    }

    private fun toJson(r: Rec) = JSONObject().put("id", r.id).put("acc", r.acc).put("ch", r.chId).put("name", r.chName)
        .put("logo", r.logo).put("title", r.title).put("start", r.start).put("end", r.end).put("file", r.file)
        .put("status", r.status.name).put("bytes", r.bytes).put("error", r.error)

    private fun fromJson(o: JSONObject) = Rec(o.optString("id"), o.optString("acc"), o.optString("ch"), o.optString("name"),
        o.optString("logo"), o.optString("title"), o.optLong("start"), o.optLong("end"), o.optString("file"),
        runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED), o.optLong("bytes"), o.optString("error"))
}

/** Hora de empezar una grabación programada. */
class RecordAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Recorder.rescheduleAll(ctx); return }
        intent.getStringExtra("id")?.let { Recorder.startService(ctx, it) }
    }
}

/**
 * Servicio que graba: lee la señal del canal (.ts) y la escribe en el archivo hasta la hora de
 * fin. Si se corta, se reconecta y sigue en el mismo archivo. Si el servidor solo da HLS
 * (.m3u8), baja los segmentos uno por uno.
 */
class RecordService : Service() {
    companion object {
        val active = ConcurrentHashMap<String, Thread>()
        val stopFlags = ConcurrentHashMap<String, Boolean>()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Recorder.ensureChannel(this)
        startForeground(77, notification("Preparando grabación…"))
        val id = intent?.getStringExtra("id")
        if (id != null && !active.containsKey(id)) {
            val t = Thread { record(id); active.remove(id); if (active.isEmpty()) stopSelfSafe() }
            active[id] = t; t.start()
        } else if (active.isEmpty()) stopSelfSafe()
        return START_NOT_STICKY
    }

    private fun stopSelfSafe() {
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, Recorder.NOTIF_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(com.jox3.iptvexo.R.drawable.ic_stop).setContentTitle("⏺ JOX3 TV está grabando").setContentText(text)
            .setOngoing(true).apply { open?.let { setContentIntent(it) } }.build()
    }

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.notify(77, notification(text))
    }

    private fun record(id: String) {
        val r = Recorder.byId(this, id) ?: return
        val acc = AccountStore(this).all().firstOrNull { it.key == r.acc }
        if (acc == null) { Recorder.update(this, id) { it.copy(status = Recorder.Status.FAILED, error = "Ese servidor ya no está en el equipo") }; return }
        Recorder.update(this, id) { it.copy(status = Recorder.Status.RECORDING) }
        notify("${r.title} · ${r.chName}")
        val file = File(r.file).apply { parentFile?.mkdirs() }
        val host = acc.host.trimEnd('/')
        val tsUrl = "$host/live/${acc.user}/${acc.pass}/${r.chId}.ts"
        val client = com.jox3.iptvexo.NetClient.player().newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        var total = file.length()
        var lastSave = 0L
        var failures = 0
        var error = ""
        try {
            FileOutputStream(file, true).use { out ->
                while (System.currentTimeMillis() < r.end && stopFlags[id] != true) {
                    try {
                        val req = okhttp3.Request.Builder().url(tsUrl).header("User-Agent", com.jox3.iptvexo.NetClient.PLAYER_USER_AGENT).build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                            val body = resp.body ?: throw RuntimeException("sin señal")
                            val type = resp.header("Content-Type").orEmpty()
                            val input = java.io.BufferedInputStream(body.byteStream(), 256 * 1024)
                            input.mark(16)
                            val head = ByteArray(7); val n = input.read(head); input.reset()
                            if (type.contains("mpegurl", true) || (n == 7 && String(head) == "#EXTM3U")) {
                                total = recordHls(resp.request.url.toString(), input.readBytes().toString(Charsets.UTF_8), client, out, r, id, total)
                                return@use
                            }
                            val buf = ByteArray(128 * 1024)
                            while (System.currentTimeMillis() < r.end && stopFlags[id] != true) {
                                val k = input.read(buf); if (k < 0) break
                                out.write(buf, 0, k); total += k
                                failures = 0
                                if (System.currentTimeMillis() - lastSave > 15_000) {
                                    lastSave = System.currentTimeMillis()
                                    Recorder.update(this, id) { it.copy(bytes = total) }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        failures++; error = e.message ?: "error"
                        Log.w("JOX3", "Grabación: $error")
                        if (failures > 30) break
                        Thread.sleep(3000)
                    }
                }
            }
        } catch (e: Exception) { error = e.message ?: "error" }
        stopFlags.remove(id)
        val ok = total > 100_000
        Recorder.update(this, id) { it.copy(status = if (ok) Recorder.Status.DONE else Recorder.Status.FAILED, bytes = total,
            end = if (System.currentTimeMillis() < it.end) System.currentTimeMillis() else it.end,
            error = if (ok) "" else "No llegó señal del canal ($error). Tu cuenta podría no permitir otra conexión.") }
    }

    /** Servidores que solo dan HLS: se piden los segmentos nuevos de la lista una y otra vez. */
    private fun recordHls(url: String, first: String, client: okhttp3.OkHttpClient, out: FileOutputStream, r: Recorder.Rec, id: String, start: Long): Long {
        var total = start
        var playlistUrl = url
        var text = first
        // Lista maestra: tomar la primera variante
        if (text.contains("#EXT-X-STREAM-INF")) {
            val v = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return total
            playlistUrl = java.net.URL(java.net.URL(url), v).toString()
            text = ""
        }
        val seen = LinkedHashSet<String>()
        while (System.currentTimeMillis() < r.end && stopFlags[id] != true) {
            if (text.isEmpty()) {
                text = client.newCall(okhttp3.Request.Builder().url(playlistUrl).header("User-Agent", com.jox3.iptvexo.NetClient.PLAYER_USER_AGENT).build())
                    .execute().use { it.body?.string().orEmpty() }
            }
            val segs = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            text = ""
            for (s in segs) {
                val u = java.net.URL(java.net.URL(playlistUrl), s).toString()
                if (!seen.add(u)) continue
                client.newCall(okhttp3.Request.Builder().url(u).header("User-Agent", com.jox3.iptvexo.NetClient.PLAYER_USER_AGENT).build())
                    .execute().use { resp -> resp.body?.byteStream()?.use { inp -> total += inp.copyTo(out, 128 * 1024) } }
                if (stopFlags[id] == true || System.currentTimeMillis() >= r.end) break
            }
            while (seen.size > 200) seen.remove(seen.first())
            Recorder.update(this, id) { it.copy(bytes = total) }
            Thread.sleep(3000)
        }
        return total
    }

    override fun onDestroy() { super.onDestroy() }
}
