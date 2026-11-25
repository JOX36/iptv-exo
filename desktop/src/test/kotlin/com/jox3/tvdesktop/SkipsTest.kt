package com.jox3.tvdesktop

import com.jox3.tvdesktop.player.EpSkip
import com.jox3.tvdesktop.player.Skips
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mismo formato que la app Android: así se usan en PC las intros y créditos que aprende la TV. */
class SkipsTest {
    @Test fun nombreDeSerieComoAndroid() {
        val n = Skips::normName
        assertEquals(n("Los Simpson (1989) [LAT] 4K"), n("THE SIMPSONS"))
        assertEquals(n("ES: Breaking Bad"), n("Breaking Bad (2008)"))
        assertEquals(n("La Casa de Papel | Castellano"), n("La casa de papel"))
    }

    @Test fun huellaDelServidorComoAndroid() {
        assertEquals("94940956786da53be812aa794f5733e4", Skips.srvKey("http://example.com:8080"))
        assertEquals(Skips.srvKey("http://example.com:8080"), Skips.srvKey("HTTP://EXAMPLE.COM:8080/"))
    }

    @Test fun leeLaRespuestaDelServidor() {
        val o = JSONObject("""{"eps":[{"ep":"101","is":5000,"ie":65000,"cs":1300000,"d":1380000},{"ep":"102","is":-2,"ie":-2,"cs":-1,"d":0}],"tpl":[],"byName":[]}""")
        val eps = Skips.parseEps(o)
        assertEquals(EpSkip(5000, 65000, 1300000, 1380000), eps["101"])
        assertEquals(true, eps["101"]!!.hasIntro)
        assertEquals(false, eps["102"]!!.hasIntro)
    }

    @Test fun loReportadoMalNoSePisa() {
        val bad = EpSkip(-2, -2, 1300000, 1380000)
        assertEquals(bad, Skips.merge(bad, EpSkip(5000, 65000, -1, 0), merge = true))
        assertEquals(EpSkip(5000, 65000, 1300000, 1380000), Skips.merge(EpSkip(), EpSkip(5000, 65000, 1300000, 1380000), merge = true))
    }
}
