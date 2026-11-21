package com.jox3.iptvexo.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultTest {
    @Test fun adultos() {
        listOf("XXX | Adultos", "ADULT 18+", "+18 HOT", "Venus HD", "Playboy TV", "Canales Eróticos", "VIP XXXX 4K", "PORNHUB")
            .forEach { assertTrue(it, Adult.isAdultName(it)) }
    }
    @Test fun normales() {
        listOf("Adult Swim", "ESPN 2 HD", "Sexto Sentido", "Caracol TV", "Fox Sports 1", "Sussex News", "Discovery H&H", "Cine 180", "UFC 318")
            .forEach { assertFalse(it, Adult.isAdultName(it)) }
    }
}
