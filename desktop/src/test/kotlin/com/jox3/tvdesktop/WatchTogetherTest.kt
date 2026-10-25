package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Tmdb
import com.jox3.tvdesktop.player.WatchTogether
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTogetherTest {
    @Test fun lineaDeDatosIgualQueAndroid() {
        val i = Tmdb.Info(1, "Guerra Mundial Z", "", 6.9f, 100, "2013", 116, "Acción, Terror", "", "", "", emptyList(), "", "")
        assertEquals("2013 · ⭐ 6.9 · Acción, Terror · 1 h 56 min", WatchTogether.metaLine(i))
    }

    @Test fun codigoBonito() = assertEquals("482 913", WatchTogether.pretty("482913"))
}
