package com.jox3.iptvexo.tv

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.jox3.iptvexo.MainActivity
import com.jox3.iptvexo.PlayerActivity
import com.jox3.iptvexo.tv.player.VodItem
import com.jox3.iptvexo.tv.player.VodPlayback
import com.jox3.iptvexo.PlayerHandoff
import com.jox3.iptvexo.tv.data.Channel
import com.jox3.iptvexo.tv.data.Episode
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Abre el reproductor nativo (PlayerActivity, el mismo que ya funciona en la app)
 * y recoge lo que devuelve: favoritos marcados dentro del reproductor y progreso.
 */
class PlayerLauncher(private val activity: ComponentActivity) {

    var repo: TvRepo? = null
    /** Reproductor nuevo (Compose) para películas y series; null fuera de la interfaz TV. */
    var vod: VodPlayback? = null

    /** true = películas y series en el reproductor nuevo (se puede volver al anterior en Ajustes). */
    var useNewVodPlayer: Boolean
        get() = !activity.getSharedPreferences(MainActivity.PREFS_UI, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_VOD_CLASSIC, false)
        set(v) {
            activity.getSharedPreferences(MainActivity.PREFS_UI, android.content.Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_VOD_CLASSIC, !v).apply()
        }
    private var launched: SavedItem? = null
    private var launchedChannels: List<Channel> = emptyList()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> onResult(res.data) }

    fun playChannel(channels: List<Channel>, index: Int, group: String) {
        val r = repo ?: return
        val ch = channels.getOrNull(index) ?: return
        val arr = JSONArray()
        channels.forEach { c ->
            arr.put(JSONObject().put("url", r.api.liveUrl(c.id)).put("name", c.name).put("id", c.id)
                .put("type", "live").put("logo", c.logo).put("group", group))
        }
        val item = SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId)
        r.library.addRecentChannel(item)
        launchedChannels = channels
        launch(r.api.liveUrl(ch.id), ch.name, group, "live", ch.logo, ch.id, arr, index, false, item)
    }

    fun playMovie(id: String, name: String, poster: String, ext: String, categoryId: String = "") {
        val r = repo ?: return
        val item = SavedItem("movie", id, name, poster, ext = ext, categoryId = categoryId)
        val player = vod
        if (player != null && useNewVodPlayer) {
            player.open(listOf(VodItem(r.api.movieUrl(id, ext), name, "Película", poster, id, item, item)), 0)
            return
        }
        r.library.rememberPlayback(item)
        launch(r.api.movieUrl(id, ext), name, "", "vod", poster, id, JSONArray(), -1, false, item)
    }

    /** Reproduce un episodio con la serie completa en cola (siguiente episodio automático). */
    fun playEpisode(seriesId: String, seriesName: String, cover: String, all: List<Episode>, index: Int) {
        val r = repo ?: return
        val ep = all.getOrNull(index) ?: return
        val player = vod
        if (player != null && useNewVodPlayer) {
            val fav = SavedItem("series", seriesId, seriesName, cover)
            player.open(all.map { e ->
                val img = e.image.ifBlank { cover }
                val resume = SavedItem("episode", e.id, episodeName(seriesName, e), img, ext = e.ext, seriesId = seriesId)
                VodItem(r.api.episodeUrl(e.id, e.ext), episodeName(seriesName, e).substringAfter(" · "), seriesName,
                    img, e.id, resume, fav)
            }, index)
            return
        }
        val arr = JSONArray()
        all.forEach { e ->
            arr.put(JSONObject().put("url", r.api.episodeUrl(e.id, e.ext)).put("name", episodeName(seriesName, e))
                .put("id", e.id).put("type", "vod").put("logo", e.image.ifBlank { cover }).put("group", seriesName)
                .put("_isSeries", true).put("_seriesId", seriesId).put("_seriesCover", cover))
        }
        val item = SavedItem("episode", ep.id, episodeName(seriesName, ep), ep.image.ifBlank { cover },
            ext = ep.ext, seriesId = seriesId)
        r.library.rememberPlayback(item)
        launch(r.api.episodeUrl(ep.id, ep.ext), episodeName(seriesName, ep), seriesName, "series",
            ep.image.ifBlank { cover }, ep.id, arr, index, true,
            item.copy(kind = "series", id = seriesId, name = seriesName, image = cover))
    }

    private fun launch(
        url: String, name: String, group: String, type: String, logo: String, id: String,
        list: JSONArray, index: Int, isSeries: Boolean, favTarget: SavedItem,
    ) {
        val r = repo ?: return
        launched = favTarget
        PlayerHandoff.put(list.toString())
        val i = Intent(activity, PlayerActivity::class.java)
            .putExtra("url", url).putExtra("name", name).putExtra("group", group)
            .putExtra("type", type).putExtra("logo", logo).putExtra("id", id)
            .putExtra("channel_index", index).putExtra("channels_in_handoff", true)
            .putExtra("is_series", isSeries)
            .putExtra("is_fav", r.library.isFavorite(favTarget.kind, favTarget.id))
        launcher.launch(i)
    }

    private fun onResult(data: Intent?) {
        val r = repo ?: return
        r.library.refreshProgress()
        val target = launched ?: return
        data ?: return
        val added = data.getBooleanExtra("fav_added", false)
        val removed = data.getBooleanExtra("fav_removed", false)
        if (!added && !removed) return
        // En vivo el usuario pudo cambiar de canal dentro del reproductor: el
        // favorito corresponde al canal que estaba viendo al salir.
        val id = data.getStringExtra("item_id") ?: target.id
        val item = if (target.kind == "live" && id != target.id) {
            val ch = launchedChannels.firstOrNull { it.id == id } ?: return
            SavedItem("live", ch.id, ch.name, ch.logo, categoryId = ch.categoryId)
        } else target
        r.library.setFavorite(item, added)
    }

    companion object {
        const val KEY_VOD_CLASSIC = "vod_classic"

        fun episodeName(series: String, e: Episode) =
            "$series · T${e.season}E${e.num}" + if (e.title.isNotBlank() && !e.title.contains(series, true)) " - ${e.title}" else ""
    }
}
