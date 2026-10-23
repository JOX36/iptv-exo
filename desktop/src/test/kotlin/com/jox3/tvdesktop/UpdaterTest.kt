package com.jox3.tvdesktop

import com.jox3.tvdesktop.core.Updater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpdaterTest {
    @Test fun comparaVersionesPorNumero() {
        assertTrue(Updater.compare("1.0.30", "1.0.9") > 0)   // 30 es mayor que 9 (no como texto)
        assertTrue(Updater.compare("1.0.9", "1.0.30") < 0)
        assertEquals(0, Updater.compare("1.0.12", "1.0.12"))
        assertTrue(Updater.compare("1.1.0", "1.0.99") > 0)
    }

    private val ok = """{"version":"1.0.40","url":"https://github.com/JOX36/iptv-exo/releases/download/desktop-v1.0.40/JOX3-TV-PC-1.0.40.msi",
        "sha256":"${"a".repeat(64)}","size":123,"notes":"- Novedad"}"""

    @Test fun leeLatestJson() {
        val i = Updater.parse(ok)
        assertEquals("1.0.40", i.version)
        assertEquals("- Novedad", i.notes)
    }

    @Test fun rechazaEnlacesDeOtroSitio() {
        assertFailsWith<IllegalArgumentException> {
            Updater.parse(ok.replace("https://github.com/JOX36/iptv-exo", "https://malo.example.com"))
        }
        assertFailsWith<IllegalArgumentException> { Updater.parse(ok.replace("a".repeat(64), "zz")) }
    }
}
