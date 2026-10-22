package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.XtreamApi
import com.jox3.tvdesktop.player.VideoPlayer
import com.jox3.tvdesktop.ui.clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VodDataTest {
    init { TestDirs.root }

    @Test fun fichaDePelicula() {
        val i = XtreamApi.parseMovieInfo("""
            {"info":{"plot":"Una historia","genre":"Acción","releasedate":"2023-05-01","rating":"7.5",
             "backdrop_path":["http://img/b.jpg"],"movie_image":"http://img/p.jpg","cast":"A, B"},
             "movie_data":{"container_extension":"mkv"}}
        """.trimIndent())
        assertEquals("Una historia", i.plot)
        assertEquals("2023", i.year)
        assertEquals("http://img/b.jpg", i.backdrop)
        assertEquals("mkv", i.ext)
    }

    @Test fun fichaDanadaNoCierraElPrograma() {
        val i = XtreamApi.parseMovieInfo("<html>error</html>")
        assertEquals("", i.plot)
    }

    @Test fun temporadasComoObjeto() {
        val s = XtreamApi.parseSeriesInfo("""
            {"info":{"name":"Serie"},"episodes":{
              "2":[{"id":"21","episode_num":1,"title":"B1"}],
              "1":[{"id":"12","episode_num":2,"title":"A2"},{"id":"11","episode_num":1,"title":"A1"},{"id":"11","episode_num":1}]
            }}
        """.trimIndent())
        assertEquals(listOf(1, 2), s.seasons.keys.toList())               // ordenadas
        assertEquals(listOf("11", "12"), s.seasons[1]!!.map { it.id })     // ordenados y sin repetidos
    }

    @Test fun temporadasComoArreglo() {
        val s = XtreamApi.parseSeriesInfo("""{"episodes":[[{"id":"1","episode_num":1}],[{"id":"2","episode_num":1}]]}""")
        assertEquals(listOf(1, 2), s.seasons.keys.toList())
    }

    @Test fun continuarViendo() {
        val lib = LibraryStore(Account("http://a.tv", "ana", "1"), TestDirs.fresh("cont"))
        val peli = SavedItem("movie", "m1", "Peli", "")
        lib.saveProgress(peli, 30 * 60_000, 100 * 60_000)
        lib.saveProgress(SavedItem("movie", "m2", "Terminada", ""), 99 * 60_000, 100 * 60_000)
        assertEquals(listOf("m1"), lib.continueWatching("movie").map { it.item.id }) // la terminada no sale
        assertEquals(70, lib.positionOf("m1")!!.remainingMin.toInt())
        lib.clearProgress("m1")
        assertTrue(lib.continueWatching().isEmpty())
    }

    @Test fun ultimoEpisodioDeLaSerie() {
        val dir = TestDirs.fresh("ep")
        val acc = Account("http://a.tv", "ana", "1")
        val lib = LibraryStore(acc, dir)
        lib.saveProgress(SavedItem("episode", "e1", "S · T1 E1", "", seriesId = "s9"), 10_000, 60_000)
        Thread.sleep(5)
        lib.saveProgress(SavedItem("episode", "e2", "S · T1 E2", "", seriesId = "s9"), 20_000, 60_000)
        assertEquals("e2", lib.lastEpisodeOf("s9")?.item?.id)
        assertNull(lib.lastEpisodeOf("otra"))
        Thread.sleep(400)
        assertEquals("e2", LibraryStore(acc, dir).lastEpisodeOf("s9")?.item?.id) // se recuerda al reabrir
    }

    @Test fun calidadDelVideo() {
        assertEquals("4K", VideoPlayer.qualityLabel(3840, 2160))
        assertEquals("Full HD", VideoPlayer.qualityLabel(1920, 1080))
        assertEquals("HD", VideoPlayer.qualityLabel(1280, 720))
        assertEquals("SD", VideoPlayer.qualityLabel(720, 576))
    }

    @Test fun relojDeTiempo() {
        assertEquals("02:05", clock(125_000))
        assertEquals("1:00:01", clock(3_601_000))
    }
}
