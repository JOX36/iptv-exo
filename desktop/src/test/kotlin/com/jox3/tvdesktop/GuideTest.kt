package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.EpgItem
import com.jox3.tvdesktop.data.EpgMerge
import com.jox3.tvdesktop.data.ExternalEpg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideTest {
    private val H = 3_600_000L

    @Test fun etiquetasEnVivoYEstreno() {
        val e = EpgItem("Nacional vs. Junior (EN VIVO)", "", 0, 1, category = "Fútbol", episode = "T2 E5")
        assertEquals("🔴 EN VIVO · Nacional vs. Junior", e.label)
        assertEquals("T2 E5 · ⚽ Fútbol", e.meta)
        assertTrue(EpgItem("ESTRENO: La serie", "", 0, 1).isNew)
    }

    @Test fun episodioXmltv() {
        assertEquals("T2 E5", ExternalEpg.episodeText("xmltv_ns", "1.4."))
        assertEquals("T1 E4", ExternalEpg.episodeText("onscreen", "S1 E4"))
    }

    @Test fun juntaYRellenaHuecos() {
        val now = 1_000_000_000_000L
        val server = listOf(EpgItem("Noticias", "", now, now + H))
        val internet = listOf(
            EpgItem("Noticias", "Las noticias del día", now, now + H, icon = "https://x/i.jpg"),
            EpgItem("Película", "", now + H, now + 3 * H),
        )
        val out = EpgMerge.merge(server, internet, now)
        assertEquals(2, out.size)
        assertEquals("Las noticias del día", out[0].description)
        assertEquals("https://x/i.jpg", out[0].icon)
        assertEquals("Película", out[1].title)
    }

    @Test fun reconoceNombreConNumeroYPais() {
        val c = ExternalEpg.Chan("CO1|ESPN.co", "ESPN.co", "CO1", listOf("ESPN"))
        c.progs.add(ExternalEpg.Prog(0, Long.MAX_VALUE / 2, "Programa", ""))
        ExternalEpg.loadForTest(listOf(c))
        assertEquals("ESPN.co", ExternalEpg.match("ESPN COLOMBIA", null)?.id)
    }
}
