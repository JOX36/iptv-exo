package com.jox3.iptvexo.tv.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Avísame cuando empiece": recordatorios de programas de la guía.
 * - Con la app abierta: aviso en pantalla para cambiar al canal con un toque (TV y celular).
 * - Con la app cerrada: notificación (en el celular sobre todo) que abre el canal.
 */
object Reminders {
    data class Reminder(
        val acc: String, val chId: String, val chName: String, val logo: String, val cat: String,
        val title: String, val start: Long, val end: Long,
    ) {
        val key get() = "$acc|$chId|$start"
        fun channel() = Channel(chId, chName, logo, cat, 1)
    }

    private const val PREFS = "tv_reminders"
    private const val CHANNEL = "recordatorios"

    /** Sube cuando cambia la lista (para repintar los ⏰ de la guía). */
    val version = mutableIntStateOf(0)
    /** Recordatorio que está empezando ahora (lo muestra el marco con la app abierta). */
    val due = mutableStateOf<Reminder?>(null)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Reminder> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
        keyCache = null
        version.intValue++
    }

    @Volatile private var keyCache: Set<String>? = null

    fun has(ctx: Context, acc: Account, ch: Channel, p: EpgItem): Boolean {
        val keys = keyCache ?: all(ctx).map { it.key }.toSet().also { keyCache = it }
        return "${acc.key}|${ch.id}|${p.start}" in keys
    }

    /** Pone o quita el aviso desde la guía y lo dice con un mensaje corto. */
    fun toggleWithToast(ctx: Context, acc: Account, ch: Channel, p: EpgItem) {
        val on = toggle(ctx, acc, ch, p)
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale("es"))
        android.widget.Toast.makeText(ctx, if (on) "⏰ Te aviso a las ${fmt.format(java.util.Date(p.start))}: ${p.cleanTitle}" else "Aviso quitado",
            android.widget.Toast.LENGTH_SHORT).show()
        // Celular con Android 13+: pedir permiso de notificaciones la primera vez
        if (on && Build.VERSION.SDK_INT >= 33 && ctx is android.app.Activity &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { ctx.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41) }
        }
    }

    /** Pone o quita el aviso. Devuelve true si quedó puesto. */
    fun toggle(ctx: Context, acc: Account, ch: Channel, p: EpgItem): Boolean {
        val now = System.currentTimeMillis()
        val list = all(ctx).filter { it.end > now }
        val r = Reminder(acc.key, ch.id, ch.name, ch.logo, ch.categoryId, p.cleanTitle, p.start, p.end)
        return if (list.any { it.key == r.key }) {
            save(ctx, list.filter { it.key != r.key }); cancel(ctx, r); false
        } else {
            save(ctx, list + r); schedule(ctx, r); true
        }
    }

    fun remove(ctx: Context, r: Reminder) { save(ctx, all(ctx).filter { it.key != r.key }); cancel(ctx, r) }

    /** El marco lo llama cada ~20 s: si algo empieza (1 min antes hasta 5 min después), se avisa. */
    fun checkDue(ctx: Context) {
        if (due.value != null) return
        val now = System.currentTimeMillis()
        val shown = prefs(ctx).getStringSet("shown", emptySet()).orEmpty()
        val r = all(ctx).firstOrNull { it.start - 60_000 <= now && now < it.start + 5 * 60_000 && it.key !in shown } ?: return
        prefs(ctx).edit().putStringSet("shown", (shown + r.key).toList().takeLast(50).toSet()).apply()
        due.value = r
    }

    // ── Alarma (con la app cerrada) ──
    private fun pi(ctx: Context, r: Reminder, flags: Int): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("key", r.key)
        return PendingIntent.getBroadcast(ctx, r.key.hashCode(), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = (r.start - 60_000).coerceAtLeast(System.currentTimeMillis() + 2_000)
        val p = pi(ctx, r, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    private fun cancel(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        pi(ctx, r, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
    }

    /** Después de reiniciar el equipo las alarmas se pierden: se vuelven a programar. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val list = all(ctx).filter { it.end > now }
        save(ctx, list)
        list.filter { it.start > now }.forEach { schedule(ctx, it) }
    }

    internal fun notify(ctx: Context, key: String) {
        val r = all(ctx).firstOrNull { it.key == key } ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordatorios de la guía", NotificationManager.IMPORTANCE_HIGH))
        }
        val acc = AccountStore(ctx).all().firstOrNull { it.key == r.acc } ?: return
        val link = com.jox3.iptvexo.tv.GoogleTv.link(acc, SavedItem("live", r.chId, r.chName, r.logo, categoryId = r.cat))
        val isTv = com.jox3.iptvexo.tv.GoogleTv.isTv(ctx)
        val target = if (isTv) com.jox3.iptvexo.tv.TvActivity::class.java else com.jox3.iptvexo.phone.PhoneActivity::class.java
        val open = PendingIntent.getActivity(ctx, r.key.hashCode(),
            Intent(ctx, target).setAction(Intent.ACTION_VIEW).setData(link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale("es"))
        val n = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx))
            .setSmallIcon(com.jox3.iptvexo.R.drawable.ic_play)
            .setContentTitle("⏰ Empieza: ${r.title}")
            .setContentText("${r.chName} · ${fmt.format(java.util.Date(r.start))} — toca para verlo")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(r.key.hashCode(), n) }
    }

    private fun toJson(r: Reminder) = JSONObject().put("acc", r.acc).put("id", r.chId).put("name", r.chName).put("logo", r.logo)
        .put("cat", r.cat).put("title", r.title).put("start", r.start).put("end", r.end)
    private fun fromJson(o: JSONObject) = Reminder(o.optString("acc"), o.optString("id"), o.optString("name"), o.optString("logo"),
        o.optString("cat"), o.optString("title"), o.optLong("start"), o.optLong("end"))
}

/** Suena la alarma del recordatorio (o el equipo se reinició). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Reminders.rescheduleAll(ctx); return }
        intent.getStringExtra("key")?.let { Reminders.notify(ctx, it) }
    }
}
