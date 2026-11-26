package com.jox3.iptvexo.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import com.jox3.iptvexo.tv.data.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Vigilante de señal: mira la imagen del canal en vivo.
 * - Todo el tiempo (gratis, sin internet): una miniatura cada 2 s para saber si está negra o quieta.
 * - Solo si lleva rato quieta: una foto a la IA (Gemini) para saber si es un letrero de error,
 *   barras de colores, etc. o contenido normal (p. ej. una emisora de radio con imagen fija).
 */
object FrameWatch {
    private const val PREFS = "jox3_player"
    private const val DAILY_MAX = 40

    private var surface: WeakReference<SurfaceView>? = null

    /** La pantalla del video en vivo (la pone la interfaz). */
    fun attach(v: View?) { (v as? SurfaceView)?.let { surface = WeakReference(it) } }

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("frame_watch", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("frame_watch", on).apply()

    /** Una foto de la imagen actual, del tamaño pedido (null si no se puede: Android < 7, sin video…). */
    suspend fun grab(w: Int, h: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val sv = surface?.get() ?: return null
        if (!sv.isAttachedToWindow || !sv.holder.surface.isValid || sv.width == 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(sv, bmp, { r -> if (cont.isActive) cont.resume(if (r == PixelCopy.SUCCESS) bmp else null) },
                        Handler(Looper.getMainLooper()))
                } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /** Brillo de cada punto (0–255) de una miniatura. */
    fun luma(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return IntArray(px.size) { i ->
            val c = px[i]
            (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
        }
    }

    /** ¿Queda cupo hoy para preguntarle a la IA? (máximo [DAILY_MAX] por equipo al día) */
    private fun takeQuota(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        val n = if (p.getInt("fw_day", -1) == day) p.getInt("fw_n", 0) else 0
        if (n >= DAILY_MAX) return false
        p.edit().putInt("fw_day", day).putInt("fw_n", n + 1).apply()
        return true
    }

    /** Resultado de la IA: true = la imagen es un error; false = normal; null = no se pudo saber. */
    suspend fun isBroken(ctx: Context, channelName: String): Pair<Boolean, String>? {
        if (!takeQuota(ctx)) return null
        val bmp = grab(384, 216) ?: return null
        val b64 = withContext(Dispatchers.Default) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("n", channelName.take(80)).put("img", b64).toString()
                val req = okhttp3.Request.Builder().url("${CloudSync.SERVER}/v1/ai/frame")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    val o = JSONObject(r.body?.string().orEmpty())
                    if (!o.optBoolean("ok")) null else o.optBoolean("bad") to o.optString("why")
                }
            }.getOrNull()
        }
    }
}
