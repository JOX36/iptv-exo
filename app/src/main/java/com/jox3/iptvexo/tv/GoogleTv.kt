package com.jox3.iptvexo.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.view.Display
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.data.SavedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Integración con la pantalla de inicio de Android TV / Google TV:
 * - "Seguir viendo" (Watch Next): las películas y episodios a medias aparecen en la fila
 *   del sistema, fuera de la app. OK ahí y sigue donde iba.
 * - Canal "JOX3 TV" con tus canales favoritos en vivo (el inicio clásico de Android TV
 *   lo muestra; el de Google TV solo muestra "Seguir viendo").
 * - Enlaces jox3://open?... que abren directo una película, un episodio o un canal.
 *
 * Todo con try/catch: hay TV Box sin el proveedor de TV, y ahí la app sigue igual.
 */
object GoogleTv {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREFS = "google_tv"

    /** Enlace pendiente de abrir (desde el inicio de la TV). Lo atiende la interfaz. */
    var pendingLink by mutableStateOf<Uri?>(null)
    /** Se pulsó el botón de búsqueda del control: el buscador abre el micrófono. */
    var voiceRequested = false

    /**
     * TV o TV Box: modo TV del sistema O función "leanback" (muchos TV Box no reportan el
     * modo TV; antes por eso no se publicaba nada en el inicio). Igual que MainActivity.
     */
    fun isTv(ctx: Context): Boolean =
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    /** Nombre de la app de inicio del equipo (Google TV, Android TV Home, otra…). */
    fun homeLauncher(ctx: Context): String = runCatching {
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val ri = ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) ?: return@runCatching "Desconocido"
        val label = ri.loadLabel(ctx.packageManager).toString()
        "$label (${ri.activityInfo.packageName})"
    }.getOrDefault("Desconocido")

    /** Qué se publicó en el inicio de la TV (o por qué no): se muestra en Ajustes. */
    var continueStatus by mutableStateOf("Aún no se ha publicado nada")
    var channelStatus by mutableStateOf("")

    private fun supported(ctx: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isTv(ctx)

    // ── Enlaces ──

    fun link(acc: Account, item: SavedItem): Uri = Uri.Builder().scheme("jox3").authority("open")
        .appendQueryParameter("kind", item.kind)
        .appendQueryParameter("id", item.id)
        .appendQueryParameter("name", item.name)
        .appendQueryParameter("image", item.image)
        .appendQueryParameter("ext", item.ext)
        .appendQueryParameter("series", item.seriesId)
        .appendQueryParameter("cat", item.categoryId)
        .appendQueryParameter("acc", acc.key)
        .build()

    /** Lee un enlace jox3://open (null si no es válido). */
    fun parse(uri: Uri?): Pair<String, SavedItem>? {
        if (uri == null || uri.scheme != "jox3" || uri.host != "open") return null
        val kind = uri.getQueryParameter("kind").orEmpty()
        val id = uri.getQueryParameter("id").orEmpty()
        if (kind !in setOf("movie", "episode", "series", "live") || id.isBlank() || id.length > 40) return null
        return uri.getQueryParameter("acc").orEmpty() to SavedItem(
            kind, id, uri.getQueryParameter("name").orEmpty().take(200), uri.getQueryParameter("image").orEmpty().take(500),
            ext = uri.getQueryParameter("ext").orEmpty().take(8), seriesId = uri.getQueryParameter("series").orEmpty().take(40),
            categoryId = uri.getQueryParameter("cat").orEmpty().take(40),
        )
    }

    // ── Seguir viendo ──

    /** Pone en "Seguir viendo" del sistema lo que está a medias en la app (y quita lo terminado). */
    @SuppressLint("RestrictedApi")
    fun syncContinue(ctx: Context, acc: Account, lib: LibraryStore) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { continueStatus = "Tu equipo tiene Android 7 o anterior: no lo admite"; return }
        if (!isTv(ctx)) { continueStatus = "Este equipo no es una TV"; return }
        val app = ctx.applicationContext
        val items = runCatching { lib.continueWatching() }.getOrDefault(emptyList()).take(10)
        scope.launch {
            runCatching {
                val cr = app.contentResolver
                // Lo que ya está publicado por esta app: internalProviderId → id de la fila
                val existing = HashMap<String, Long>()
                val cursor = cr.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)
                if (cursor == null) {
                    continueStatus = "Tu TV Box no tiene la pantalla de inicio de Android TV (usa otro inicio): no se puede mostrar"
                    return@runCatching
                }
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val p = WatchNextProgram.fromCursor(c)
                        p.internalProviderId?.let { existing[it] = p.id }
                    }
                }
                val keep = HashSet<String>()
                for (r in items) {
                    val pid = acc.key.hashCode().toString() + ":" + r.item.id
                    keep += pid
                    val title = r.item.name.substringBefore(" · ").ifBlank { r.item.name }
                    val ep = r.item.kind == "episode"
                    val b = WatchNextProgram.Builder()
                        .setType(if (ep) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                        .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                        .setLastEngagementTimeUtcMillis(r.item.ts)
                        .setTitle(title)
                        .setLastPlaybackPositionMillis(r.position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        .setDurationMillis(r.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        .setIntentUri(link(acc, r.item))
                        .setInternalProviderId(pid)
                    if (ep) b.setEpisodeTitle(r.item.name.substringAfter(" · ", ""))
                    if (r.item.image.startsWith("http")) b.setPosterArtUri(Uri.parse(r.item.image))
                    val values = b.build().toContentValues()
                    val rowId = existing[pid]
                    if (rowId != null) cr.update(TvContractCompat.buildWatchNextProgramUri(rowId), values, null, null)
                    else cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                }
                // Lo terminado o de otra cuenta sale de la fila
                existing.filterKeys { it !in keep }.values.forEach { id ->
                    cr.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
                }
                continueStatus = if (items.isEmpty()) "Seguir viendo: no hay películas ni episodios a medias"
                    else "Seguir viendo: ${items.size} ${if (items.size == 1) "título publicado" else "títulos publicados"}"
            }.onFailure { e ->
                continueStatus = "Seguir viendo: no se pudo publicar (${e.javaClass.simpleName}: ${e.message?.take(80)})"
            }
        }
    }

    // ── Canal "JOX3 TV" con los favoritos en vivo ──

    /** Canal propio en el inicio de Android TV con tus canales favoritos (y lo que dan ahora). */
    fun syncFavoritesChannel(ctx: Context, acc: Account, favorites: List<SavedItem>, nowTitle: (SavedItem) -> String) {
        if (!supported(ctx)) return
        if (favorites.none { it.kind == "live" }) channelStatus = "Canal JOX3: agrega canales a Favoritos para que aparezca"
        val app = ctx.applicationContext
        val live = favorites.filter { it.kind == "live" }.take(20)
        scope.launch {
            runCatching {
                val helper = PreviewChannelHelper(app)
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                var channelId = prefs.getLong("channel", -1L)
                if (channelId > 0 && runCatching { helper.getPreviewChannel(channelId) }.getOrNull() == null) channelId = -1L
                if (channelId <= 0) {
                    if (live.isEmpty()) return@runCatching
                    val ch = PreviewChannel.Builder()
                        .setDisplayName("JOX3 TV · Favoritos en vivo")
                        .setAppLinkIntentUri(Uri.parse("jox3://open?kind=home&id=0"))
                        .setLogo(appLogo(app))
                        .build()
                    channelId = helper.publishDefaultChannel(ch)
                    prefs.edit().putLong("channel", channelId).apply()
                }
                // Programas: se reemplazan todos (son pocos)
                val ids = ArrayList<Long>()
                app.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                    arrayOf(TvContractCompat.PreviewPrograms._ID), null, null, null)?.use { c ->
                    while (c.moveToNext()) ids += c.getLong(0)
                }
                ids.forEach { helper.deletePreviewProgram(it) }
                live.forEachIndexed { i, f ->
                    val now = runCatching { nowTitle(f) }.getOrDefault("")
                    val p = PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                        .setTitle(f.name)
                        .setDescription(if (now.isNotBlank()) "Ahora: $now" else "En vivo")
                        .setLive(true)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        .setIntentUri(link(acc, f))
                        .setInternalProviderId(f.id)
                        .setWeight(1000 - i)
                    if (f.image.startsWith("http")) p.setPosterArtUri(Uri.parse(f.image)).setLogoUri(Uri.parse(f.image))
                    helper.publishPreviewProgram(p.build())
                }
                if (live.isNotEmpty()) channelStatus = "Canal JOX3: ${live.size} canales favoritos (solo en el inicio clásico de Android TV)"
            }.onFailure { e ->
                channelStatus = "Canal JOX3: no se pudo publicar (${e.javaClass.simpleName})"
            }
        }
    }

    private fun appLogo(ctx: Context): Bitmap {
        val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)!!
        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        return bmp
    }

    // ── Ajuste automático de la frecuencia de la TV ──

    fun afrEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("afr", false)
    fun setAfr(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("afr", on).apply()

    private var afrApplied = false

    /**
     * Cambia la frecuencia de la pantalla a la del video (24, 25, 50, 60 Hz…) para que el
     * movimiento sea fluido, sin saltos. Solo si la TV tiene ese modo en la misma resolución.
     */
    fun applyFrameRate(activity: Activity, fps: Float) {
        if (fps <= 1f || !afrEnabled(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            @Suppress("DEPRECATION")
            val display: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null)
                ?: activity.windowManager.defaultDisplay
            val cur = display.mode
            val same = display.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            // Exacta (23,976 = 23,976), o el doble (25 → 50, 29,97 → 59,94), o el triple raro no
            fun matches(rate: Float, mult: Int) = abs(rate - fps * mult) < 0.05f
            val best = same.firstOrNull { matches(it.refreshRate, 1) } ?: same.firstOrNull { matches(it.refreshRate, 2) }
                ?: return
            if (best.modeId == cur.modeId) return
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = best.modeId
            activity.window.attributes = lp
            afrApplied = true
            val hz = (best.refreshRate * 1000).roundToInt() / 1000f
            Toast.makeText(activity, "TV ajustada a ${"%.3f".format(hz).trimEnd('0').trimEnd(',', '.')} Hz", Toast.LENGTH_SHORT).show()
        }
    }

    /** Vuelve la pantalla a su frecuencia normal (al cerrar el video). */
    fun restoreFrameRate(activity: Activity) {
        if (!afrApplied) return
        afrApplied = false
        runCatching {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = 0
            activity.window.attributes = lp
        }
    }
}
