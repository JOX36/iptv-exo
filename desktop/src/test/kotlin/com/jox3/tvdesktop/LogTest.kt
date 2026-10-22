package com.jox3.tvdesktop

import com.jox3.tvdesktop.core.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogTest {
    init { TestDirs.root }

    @Test fun ocultaContrasenaEnApi() {
        assertEquals("http://a.tv/player_api.php?username=ana&password=***&action=x",
            Log.mask("http://a.tv/player_api.php?username=ana&password=secreta&action=x"))
    }

    @Test fun ocultaContrasenaEnStreams() {
        assertEquals("http://a.tv/live/ana/***/12.m3u8", Log.mask("http://a.tv/live/ana/secreta/12.m3u8"))
        assertEquals("http://a.tv/series/ana/***/9.mkv", Log.mask("http://a.tv/series/ana/secreta/9.mkv"))
    }

    @Test fun elRegistroNoGuardaContrasenas() {
        Log.w("fallo en http://a.tv/movie/ana/secreta/1.mp4")
        assertFalse(java.io.File(Log.folder, "jox3.log").readText().contains("secreta"))
    }
}
