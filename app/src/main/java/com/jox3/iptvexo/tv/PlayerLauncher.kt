package com.jox3.iptvexo.tv

import com.jox3.iptvexo.tv.data.Episode
import kotlinx.coroutines.launch
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.player.VodItem
import com.jox3.iptvexo.tv.player.VodPlayback

/** Abre películas y episodios en el reproductor de la interfaz TV (VodPlayback). */
class PlayerLauncher {

    private val scope = kotlinx.coroutines.MainScope()

    var repo: TvRepo? = null
    var vod: VodPlayback? = null

    fun playMovie(id: String, name: String, poster: String, ext: String, categoryId: String = "") {
        val r = repo ?: return
        val player = vod ?: return
        // Si ya se está viendo en el reproductor pequeño, solo se agranda (sin recargar)
        if (player.open && player.item?.id == id) { player.maximize(); return }
        val item = SavedItem("movie", id, name, poster, ext = ext, categoryId = categoryId)
        player.open(listOf(VodItem(r.api.movieUrl(id, ext), name, "Película", poster, id, item, item)), 0)
        // La sinopsis y los datos llegan después (no retrasan el inicio del video)
        scope.launch {
            val info = runCatching { r.api.movieInfo(id) }.getOrNull() ?: return@launch
            player.setExtras(id, info.plot, listOfNotNull(info.year.ifBlank { null }, info.genre.ifBlank { null },
                info.duration.ifBlank { null }, info.rating.ifBlank { null }?.let { "★ $it" }).joinToString(" · "))
            player.credits[id] = Triple(info.director, info.cast, info.genre)
        }
    }

    /** Reproduce un episodio con la serie completa en cola (siguiente episodio automático). */
    fun playEpisode(seriesId: String, seriesName: String, cover: String, all: List<Episode>, index: Int) {
        val r = repo ?: return
        val player = vod ?: return
        val ep = all.getOrNull(index) ?: return
        if (player.open && player.item?.id == ep.id) { player.maximize(); return }
        val fav = SavedItem("series", seriesId, seriesName, cover)
        player.open(all.map { e ->
            val img = e.image.ifBlank { cover }
            val resume = SavedItem("episode", e.id, episodeName(seriesName, e), img, ext = e.ext, seriesId = seriesId)
            VodItem(r.api.episodeUrl(e.id, e.ext), episodeName(seriesName, e).substringAfter(" · "), seriesName,
                img, e.id, resume, fav, season = e.season, plot = e.plot, meta = e.duration)
        }, index)
    }

    /** Ver un programa de la guía desde el inicio (o uno que ya pasó), si el canal guarda archivo. */
    fun playCatchup(ch: com.jox3.iptvexo.tv.data.Channel, p: com.jox3.iptvexo.tv.data.EpgItem) {
        val r = repo ?: return
        val player = vod ?: return
        scope.launch {
            val tz = r.serverTimeZone()
            val minutes = (((p.end - p.start) / 60_000L).toInt() + 5).coerceIn(5, 600)
            val url = r.api.timeshiftUrl(ch.id, p.start, minutes, tz)
            val item = SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId)
            player.open(listOf(VodItem(url, p.cleanTitle, "${ch.name} · desde el inicio", p.icon.ifBlank { ch.logo },
                "cu_${ch.id}_${p.start}", item, item, plot = p.description, meta = p.meta, noHistory = true)), 0)
        }
    }

    /** Ver una grabación (también mientras se sigue grabando). */
    fun playRecording(r: com.jox3.iptvexo.tv.data.Recorder.Rec) {
        val player = vod ?: return
        val f = java.io.File(r.file)
        if (!f.exists() || f.length() == 0L) return
        val item = SavedItem("live", r.chId, r.chName, r.logo)
        val fmt = java.text.SimpleDateFormat("d MMM · h:mm a", java.util.Locale("es"))
        player.open(listOf(VodItem(android.net.Uri.fromFile(f).toString(), r.title, "${r.chName} · grabado ${fmt.format(java.util.Date(r.start))}",
            r.logo, "rec_${r.id}", item, item, noHistory = true)), 0)
    }

    companion object {
        /** El reproductor de la pantalla actual (TV o celular), para abrir repeticiones desde la guía. */
        @Volatile var current: PlayerLauncher? = null

        fun episodeName(series: String, e: Episode) =
            "$series · T${e.season}E${e.num}" + if (e.title.isNotBlank() && !e.title.contains(series, true)) " - ${e.title}" else ""
    }
}
