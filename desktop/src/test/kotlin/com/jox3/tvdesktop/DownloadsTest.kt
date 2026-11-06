package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Downloads
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadsTest {
    @Test fun nombresComoAndroid() {
        val ep = Downloads.Dl("x", "a", "episode", "77", "mkv", "El inicio", series = "Dark: Serie", season = 1, num = 2)
        assertEquals("Dark Serie - T01E02 - El inicio.mkv", Downloads.fileName(ep))
        assertEquals("Dark: Serie · T1E2 - El inicio", ep.label)
        val mv = Downloads.Dl("y", "a", "movie", "5", "mp4", "Avatar (2009)")
        assertEquals("Avatar (2009).mp4", Downloads.fileName(mv))
    }

    @Test fun noSeRepiteYHlsNoSeDescarga() {
        Downloads.fileOverride = File(TestDirs.fresh("dl"), "downloads.json")
        val d = Downloads.Dl("d1", "a", "movie", "5", "mp4", "Avatar")
        assertTrue(Downloads.enqueue(listOf(d), autoStart = false).startsWith("⬇"))
        assertEquals("Ya está en Descargas", Downloads.enqueue(listOf(d), autoStart = false))
        assertTrue(Downloads.enqueue(listOf(d.copy(id = "d2", ext = "m3u8")), autoStart = false).contains("no se puede descargar"))
        assertEquals(1, Downloads.all().size)
        Downloads.fileOverride = null
    }
}
