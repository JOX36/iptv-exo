package com.jox3.iptvexo.tv

import com.jox3.iptvexo.tv.data.Episode
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.player.VodItem
import com.jox3.iptvexo.tv.player.VodPlayback

/** Abre películas y episodios en el reproductor de la interfaz TV (VodPlayback). */
class PlayerLauncher {

    var repo: TvRepo? = null
    var vod: VodPlayback? = null

    fun playMovie(id: String, name: String, poster: String, ext: String, categoryId: String = "") {
        val r = repo ?: return
        val player = vod ?: return
        val item = SavedItem("movie", id, name, poster, ext = ext, categoryId = categoryId)
        player.open(listOf(VodItem(r.api.movieUrl(id, ext), name, "Película", poster, id, item, item)), 0)
    }

    /** Reproduce un episodio con la serie completa en cola (siguiente episodio automático). */
    fun playEpisode(seriesId: String, seriesName: String, cover: String, all: List<Episode>, index: Int) {
        val r = repo ?: return
        val player = vod ?: return
        if (all.getOrNull(index) == null) return
        val fav = SavedItem("series", seriesId, seriesName, cover)
        player.open(all.map { e ->
            val img = e.image.ifBlank { cover }
            val resume = SavedItem("episode", e.id, episodeName(seriesName, e), img, ext = e.ext, seriesId = seriesId)
            VodItem(r.api.episodeUrl(e.id, e.ext), episodeName(seriesName, e).substringAfter(" · "), seriesName,
                img, e.id, resume, fav)
        }, index)
    }

    companion object {
        fun episodeName(series: String, e: Episode) =
            "$series · T${e.season}E${e.num}" + if (e.title.isNotBlank() && !e.title.contains(series, true)) " - ${e.title}" else ""
    }
}
