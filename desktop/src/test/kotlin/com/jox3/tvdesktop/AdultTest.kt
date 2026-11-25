package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Adult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mismos casos que la app Android: canales de adultos fuera de "Canales recientes". */
class AdultTest {
    @Test fun adultos() {
        listOf("XXX | Adultos", "ADULT 18+", "+18 HOT", "Venus HD", "Playboy TV", "Canales Eróticos", "VIP XXXX 4K", "PORNHUB")
            .forEach { assertTrue(Adult.isAdultName(it), it) }
    }
    @Test fun normales() {
        listOf("Adult Swim", "ESPN 2 HD", "Sexto Sentido", "Caracol TV", "Fox Sports 1", "Sussex News", "Discovery H&H", "Cine 180", "UFC 318")
            .forEach { assertFalse(Adult.isAdultName(it), it) }
    }
}
