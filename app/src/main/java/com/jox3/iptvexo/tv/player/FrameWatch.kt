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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private const val DAILY_MAX = 60
    /** Consultas de "¿Qué estoy viendo?" por equipo al día (solo cuentan las que responden). */
    private const val WHAT_MAX = 40

    /** Día según la hora del equipo: el cupo se renueva a la medianoche local. */
    private fun localDay(): Int {
        val now = System.currentTimeMillis()
        return ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
    }

    private fun countWhat(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = localDay()
        val n = if (p.getInt("what_day", -1) == day) p.getInt("what_n", 0) else 0
        p.edit().putInt("what_day", day).putInt("what_n", n + 1).apply()
    }

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
        val day = localDay()
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

    // ── ¿Qué estoy viendo? ──

    /** Lo que respondió la IA sobre la imagen. */
    data class What(
        val title: String, val text: String, val facts: List<String>,
        /** Serie y capítulo (si se pudo saber); [sure] = sale de la guía o de la pantalla, no deducido. */
        val series: String = "", val season: Int = 0, val episode: Int = 0, val episodeTitle: String = "", val sure: Boolean = false,
        /** Si no se pudo confirmar: los capítulos posibles ("T5 C20 «Nombre»"). */
        val alternatives: List<String> = emptyList(),
        /** Se escuchó el audio (si no, solo fotos). */
        val heard: Boolean = false,
        /** Por qué se eligió ese capítulo (comparando con el resumen real). */
        val why: String = "",
    ) {
        /** "Malcolm · Temporada 4 · Capítulo 3 · «Nombre»" (vacío si no es una serie identificada). */
        val episodeLine: String get() {
            if (series.isBlank() || (season == 0 && episode == 0 && episodeTitle.isBlank())) return ""
            return buildString {
                append(series)
                if (season > 0) append(" · Temporada ").append(season)
                if (episode > 0) append(" · Capítulo ").append(episode)
                if (episodeTitle.isNotBlank()) append(" · «").append(episodeTitle).append("»")
                if (!sure) append("  (probable)")
            }
        }
    }

    /**
     * Foto de la pantalla + lo que dice la guía → la IA explica qué se está viendo.
     * Devuelve el error en palabras simples si no se pudo.
     */
    suspend fun describe(ctx: Context, channelName: String, prog: String, desc: String, episode: String = ""): Result<What> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = localDay()
        val n = if (p.getInt("what_day", -1) == day) p.getInt("what_n", 0) else 0
        if (n >= WHAT_MAX) return Result.failure(Exception("Ya se usaron las $WHAT_MAX consultas de hoy en este equipo. Se renuevan a la medianoche."))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return Result.failure(Exception("Necesita Android 7 o más nuevo."))
        val bmp = grab(768, 432) ?: return Result.failure(Exception("No pude tomar la imagen del canal. Prueba cuando se esté viendo."))
        val b64 = withContext(Dispatchers.Default) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("n", channelName.take(80)).put("prog", prog.take(120)).put("desc", desc.take(400)).put("ep", episode.take(60))
                    .put("img", b64).toString()
                val req = okhttp3.Request.Builder().url("${CloudSync.SERVER}/v1/ai/what")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
                    val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                    if (!r.isSuccessful || !o.optBoolean("ok")) throw Exception("La IA no está disponible en este momento. Prueba en un rato.")
                    val f = o.optJSONArray("facts")
                    What(o.optString("title"), o.optString("text"), (0 until (f?.length() ?: 0)).map { f!!.optString(it) }.filter { it.isNotBlank() },
                        o.optString("series"), o.optInt("season"), o.optInt("episode"), o.optString("episodeTitle"), o.optBoolean("sure"))
                }.let { w ->
                    // El nombre del capítulo se toma de TMDB (exacto), no de la memoria de la IA
                    val real = if (w.series.isNotBlank() && w.season > 0 && w.episode > 0)
                        com.jox3.iptvexo.tv.data.Tmdb.episodeName(ctx, w.series, w.season, w.episode) else ""
                    when {
                        real.isNotBlank() -> w.copy(episodeTitle = real)
                        // Sin confirmar y deducido por la IA: mejor no inventar el nombre
                        !w.sure -> w.copy(episodeTitle = "")
                        else -> w
                    }
                }
            }.recoverCatching { e -> throw if (e.message?.startsWith("La IA") == true) e else Exception("Sin conexión con la IA. Revisa el internet.") }
                .onSuccess { countWhat(ctx) }
        }
    }

    /**
     * "¿Qué estoy viendo?" con audio (para canales sin guía, como los 24/7):
     * 1) 3 fotos seguidas + los últimos 20 s de audio → la IA dice qué es y hasta 5 capítulos posibles.
     * 2) De TMDB se trae el nombre y el resumen REAL de esos capítulos.
     * 3) La IA elige cuál coincide con lo que se dijo. Si ninguno, se muestran las opciones.
     */
    suspend fun identify(ctx: Context, channelName: String, prog: String, desc: String, episode: String = ""): Result<What> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = localDay()
        val n = if (p.getInt("what_day", -1) == day) p.getInt("what_n", 0) else 0
        if (n >= WHAT_MAX) return Result.failure(Exception("Ya se usaron las $WHAT_MAX consultas de hoy en este equipo. Se renuevan a la medianoche."))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return Result.failure(Exception("Necesita Android 7 o más nuevo."))
        // Fotos: ahora, a los 1,5 s y a los 3 s (la escena cambia y da más pistas)
        val shots = ArrayList<String>()
        repeat(3) { k ->
            if (k > 0) kotlinx.coroutines.delay(1500)
            grab(512, 288)?.let { bmp ->
                shots += withContext(Dispatchers.Default) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 65, out)
                    android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                }
            }
        }
        if (shots.isEmpty()) return Result.failure(Exception("No pude tomar la imagen del canal. Prueba cuando se esté viendo."))
        val audio = withContext(Dispatchers.Default) {
            LiveAudio.lastWav(20)?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        }
        fun post(path: String, body: JSONObject): JSONObject {
            val req = okhttp3.Request.Builder().url("${CloudSync.SERVER}$path")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return com.jox3.iptvexo.NetClient.api().newCall(req).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                if (!r.isSuccessful || !o.optBoolean("ok")) throw Exception("La IA no está disponible en este momento. Prueba en un rato.")
                o
            }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val o = post("/v1/ai/what2", JSONObject().put("n", channelName.take(80)).put("prog", prog.take(120))
                    .put("desc", desc.take(400)).put("ep", episode.take(60)).put("imgs", org.json.JSONArray(shots)).put("audio", audio ?: ""))
                val f = o.optJSONArray("facts")
                val facts = (0 until (f?.length() ?: 0)).map { f!!.optString(it) }.filter { it.isNotBlank() }
                val base = What(o.optString("title"), o.optString("text"), facts, o.optString("series"), heard = o.optBoolean("heard"))
                // Otro equipo ya lo identificó en este canal hace un momento
                o.optJSONObject("picked")?.let { pk ->
                    return@runCatching base.copy(season = pk.optInt("season"), episode = pk.optInt("episode"),
                        episodeTitle = pk.optString("name"), sure = pk.optString("confidence") == "alta")
                }
                val ca = o.optJSONArray("candidates")
                val cands = (0 until (ca?.length() ?: 0)).mapNotNull { ca!!.optJSONObject(it) }
                    .map { it.optInt("season") to it.optInt("episode") }.filter { it.first > 0 && it.second > 0 }.distinct()
                if (base.series.isBlank() || cands.isEmpty()) return@runCatching base
                // Nombre y resumen reales de cada candidato (TMDB)
                val info: List<Triple<Int, Int, Pair<String, String>?>> = coroutineScope {
                    cands.map { (s, e) -> async { Triple(s, e, com.jox3.iptvexo.tv.data.Tmdb.episodeInfo(ctx, base.series, s, e)) } }
                        .map { it.await() }
                }.filter { it.third != null }
                fun label(t: Triple<Int, Int, Pair<String, String>?>) = "T${t.first} C${t.second}" + (t.third?.first?.takeIf { it.isNotBlank() }?.let { " «$it»" } ?: "")
                if (info.isEmpty()) {
                    // Sin TMDB: lo que propuso la IA, como probable
                    val (s, e) = cands.first()
                    return@runCatching base.copy(season = s, episode = e, sure = o.optBoolean("fromGuide"))
                }
                if (o.optBoolean("fromGuide")) {
                    val t = info.first()
                    return@runCatching base.copy(season = t.first, episode = t.second, episodeTitle = t.third!!.first, sure = true)
                }
                val pick = runCatching {
                    post("/v1/ai/pick", JSONObject().put("series", base.series).put("transcript", o.optString("transcript"))
                        .put("scene", base.text).put("n", channelName.take(80)).put("prog", prog.take(120))
                        .put("result", JSONObject().put("title", base.title).put("text", base.text).put("facts", org.json.JSONArray(facts)))
                        .put("cands", org.json.JSONArray(info.map { t ->
                            JSONObject().put("s", t.first).put("e", t.second).put("name", t.third!!.first).put("overview", t.third!!.second.take(600))
                        })))
                }.getOrNull()
                val i = pick?.optInt("i", -1) ?: -1
                val conf = pick?.optString("confidence").orEmpty()
                if (i in info.indices && conf != "baja") {
                    val t = info[i]
                    base.copy(season = t.first, episode = t.second, episodeTitle = t.third!!.first, sure = conf == "alta",
                        why = pick?.optString("why").orEmpty())
                } else base.copy(alternatives = info.take(3).map { label(it) })
            }.recoverCatching { e -> throw if (e.message?.startsWith("La IA") == true) e else Exception("Sin conexión con la IA. Revisa el internet.") }
                .onSuccess { countWhat(ctx) }
        }
    }
}
