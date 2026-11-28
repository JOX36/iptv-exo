package com.jox3.iptvexo.phone.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.GoogleTv
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.ExternalEpg
import com.jox3.iptvexo.tv.data.Names
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Widget del celular "Ahora en JOX3" (4×2): lo que estabas viendo para seguir con un toque y
 * tus canales favoritos con lo que dan ahora.
 *
 * La app deja una "foto" de esos datos ([publish]) cada vez que cambian; el widget solo la lee:
 * no descarga listas ni reproduce nada (no gasta batería ni datos). Lo que dan en cada canal se
 * guarda para las próximas horas, así el widget lo muestra al día aunque la app esté cerrada.
 */
object HomeWidget {
    private const val PREFS = "jox3_widget"

    private fun dir(ctx: Context) = File(ctx.filesDir, "widget").apply { mkdirs() }
    private fun imgFile(ctx: Context, url: String) = File(dir(ctx), url.hashCode().toUInt().toString(36) + ".png")

    /** Guarda lo que muestra el widget (perfil y servidor actuales) y lo actualiza. */
    suspend fun publish(ctx: Context, repo: TvRepo, profileName: String) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (!hasWidgets(app)) return@withContext
        val acc = repo.api.account
        val lib = repo.library
        val o = JSONObject().put("profile", profileName).put("ts", System.currentTimeMillis())
        val now0 = System.currentTimeMillis()
        // Lo que dan en un canal desde ahora hasta 8 horas (guía de internet o del servidor)
        fun progsOf(c: SavedItem): JSONArray {
            val ch = Channel(c.id, c.name, c.image, c.categoryId, 1)
            val list = (ExternalEpg.match(app, c.name, null)?.progs?.map { Triple(it.start * 1000, it.stop * 1000, it.title) }
                ?: repo.cachedEpg(ch)?.map { Triple(it.start, it.end, it.title) }.orEmpty())
                .filter { it.second > now0 && it.first < now0 + 8 * 3600_000 }.take(12)
            return JSONArray(list.map { (a, b, t) -> JSONArray().put(a).put(b).put(t) })
        }
        // Seguir viendo: lo más reciente a medias (película o capítulo)
        val resume = lib.continueWatching().firstOrNull()
        // Sin nada a medias: el último canal que se vio
        val lastLive = if (resume == null) lib.recentChannels.firstOrNull() else null
        lastLive?.let { c ->
            o.put("resume", JSONObject().put("label", "live").put("title", Names.channel(c.name))
                .put("image", c.image).put("link", GoogleTv.link(acc, c).toString()).put("progs", progsOf(c)))
            saveImage(app, c.image, 96, 96, 18f)
        }
        resume?.let { r ->
            val isEp = r.item.kind == "episode"
            o.put("resume", JSONObject()
                .put("title", r.item.name.substringBefore(" · ").ifBlank { r.item.name })
                .put("sub", (if (isEp) r.item.name.substringAfter(" · ", "") + " · " else "") + "quedan ${r.remainingMin} min")
                .put("fraction", (r.fraction * 1000).toInt())
                .put("image", r.item.image)
                .put("link", GoogleTv.link(acc, r.item).toString()))
            saveImage(app, r.item.image, 120, 162, 10f)
        }
        // Canales: favoritos en vivo (y si faltan, los últimos vistos)
        val chans = (lib.favorites.filter { it.kind == "live" } + lib.recentChannels)
            .distinctBy { it.id }.filter { it.id != lastLive?.id }.take(4)
        val arr = JSONArray()
        for (c in chans) {
            arr.put(JSONObject().put("name", Names.channel(c.name)).put("logo", c.image)
                .put("link", GoogleTv.link(acc, c).toString()).put("progs", progsOf(c)))
            saveImage(app, c.image, 96, 96, 18f)
        }
        o.put("channels", arr)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("data", o.toString()).apply()
        updateAll(app)
    }

    /** Hay algún widget puesto en la pantalla de inicio. */
    fun hasWidgets(ctx: Context) = runCatching {
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, HomeWidgetProvider::class.java)).isNotEmpty()
    }.getOrDefault(false)

    fun updateAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        val ids = m.getAppWidgetIds(ComponentName(ctx, HomeWidgetProvider::class.java))
        if (ids.isNotEmpty()) ids.forEach { m.updateAppWidget(it, build(ctx)) }
    }

    /** Imagen pequeña con esquinas redondeadas, guardada en el equipo (una vez). */
    private fun saveImage(ctx: Context, url: String, w: Int, h: Int, radius: Float) {
        if (!url.startsWith("http")) return
        val f = imgFile(ctx, url)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 24 * 3600_000L) return
        runCatching {
            val req = okhttp3.Request.Builder().url(url).build()
            val bytes = com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } ?: return
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            // Póster: llena y recorta. Logo: entero, centrado
            val fill = h > w
            val s = if (fill) maxOf(w.toFloat() / src.width, h.toFloat() / src.height) else minOf(w.toFloat() / src.width, h.toFloat() / src.height) * 0.9f
            val dw = src.width * s; val dh = src.height * s
            if (!fill) { val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D2633.toInt(); xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }; cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg) }
            cv.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun image(ctx: Context, url: String): Bitmap? =
        if (!url.startsWith("http")) null else imgFile(ctx, url).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Toque: abrir la app con un enlace jox3://open (o solo abrirla). */
    private fun tap(ctx: Context, code: Int, link: String?): PendingIntent {
        val i = Intent(ctx, com.jox3.iptvexo.phone.PhoneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (link != null) i.setAction(Intent.ACTION_VIEW).setData(Uri.parse(link))
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Lo que dan ahora según los programas guardados ("" si no hay guía). */
    private fun nowOf(progs: JSONArray?, now: Long): String {
        if (progs == null) return ""
        for (k in 0 until progs.length()) {
            val p = progs.optJSONArray(k) ?: continue
            if (p.optLong(0) <= now && now < p.optLong(1)) return p.optString(2)
        }
        return ""
    }
    private val NOW_IDS = intArrayOf(R.id.w_now0, R.id.w_now1, R.id.w_now2, R.id.w_now3)
    private val LOGO_IDS = intArrayOf(R.id.w_logo0, R.id.w_logo1, R.id.w_logo2, R.id.w_logo3)
    private val CH_IDS = intArrayOf(R.id.w_ch0, R.id.w_ch1, R.id.w_ch2, R.id.w_ch3)

    fun build(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_home)
        val data = runCatching { JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "{}")!!) }.getOrDefault(JSONObject())
        v.setOnClickPendingIntent(R.id.w_root, tap(ctx, 0, null))
        v.setTextViewText(R.id.w_profile, data.optString("profile"))
        val r = data.optJSONObject("resume")
        val now = System.currentTimeMillis()
        if (r != null) {
            v.setViewVisibility(R.id.w_resume, View.VISIBLE)
            v.setViewVisibility(R.id.w_empty, View.GONE)
            v.setTextViewText(R.id.w_title, r.optString("title"))
            if (r.optString("label") == "live") {
                // Último canal: lo que dan ahora, sin barra de progreso
                v.setTextViewText(R.id.w_label, ctx.getString(R.string.widget_last_channel))
                v.setTextViewText(R.id.w_sub, nowOf(r.optJSONArray("progs"), now).ifBlank { "En vivo" })
                v.setViewVisibility(R.id.w_progress, View.GONE)
            } else {
                v.setTextViewText(R.id.w_label, ctx.getString(R.string.widget_continue))
                v.setTextViewText(R.id.w_sub, r.optString("sub"))
                v.setViewVisibility(R.id.w_progress, View.VISIBLE)
                v.setProgressBar(R.id.w_progress, 1000, r.optInt("fraction"), false)
            }
            image(ctx, r.optString("image"))?.let { v.setImageViewBitmap(R.id.w_poster, it) }
                ?: v.setImageViewResource(R.id.w_poster, R.drawable.widget_card)
            v.setOnClickPendingIntent(R.id.w_resume, tap(ctx, 1, r.optString("link")))
        } else {
            v.setViewVisibility(R.id.w_resume, View.GONE)
            v.setViewVisibility(R.id.w_empty, View.VISIBLE)
        }
        val chans = data.optJSONArray("channels") ?: JSONArray()
        v.setViewVisibility(R.id.w_channels, if (chans.length() > 0) View.VISIBLE else View.GONE)
        for (i in 0 until 4) {
            val c = chans.optJSONObject(i)
            if (c == null) { v.setViewVisibility(CH_IDS[i], View.INVISIBLE); continue }
            v.setViewVisibility(CH_IDS[i], View.VISIBLE)
            // Debajo del logo: lo que dan ahora (con la hora del celular) o, sin guía, el nombre del canal
            v.setTextViewText(NOW_IDS[i], nowOf(c.optJSONArray("progs"), now).ifBlank { c.optString("name") })
            image(ctx, c.optString("logo"))?.let { v.setImageViewBitmap(LOGO_IDS[i], it) }
                ?: v.setImageViewResource(LOGO_IDS[i], R.drawable.ic_launcher)
            v.setOnClickPendingIntent(CH_IDS[i], tap(ctx, 10 + i, c.optString("link")))
        }
        return v
    }
}

/** El widget en la pantalla de inicio: se dibuja con lo que dejó la app (cada 30 min o al cambiar). */
class HomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, HomeWidget.build(context)) }
    }
}
