package com.jox3.iptvexo.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nombres limpios: sin emojis, estrellas ni códigos que ponen los servidores. */
class NamesTest {
    @Test fun categorias() {
        assertEquals("🇨🇴 COLOMBIA", Names.category("★-COLOMBIA-★"))
        assertEquals("🇧🇷 BIG BROTHER BRASIL", Names.category("🇧🇷 BIG BROTHER BRASIL"))
        assertEquals("⚽ TIGO SPORTS", Names.category("⭐-TIGO SPORTS-⭐"))
        assertEquals("ALQUILER VIP", Names.category("★-ALQUILER VIP-★"))
        assertEquals("🇵🇷 PUERTO RICO", Names.category("★-PUERTO RICO-★"))
        assertEquals("📰 NOTICIAS", Names.category("TV | Noticias"))
    }

    @Test fun canales() {
        assertEquals("CARACOL", Names.channel("*Caracol*"))
        assertEquals("CARACOL", Names.channel("COL caracol"))
        assertEquals("CARACOL HD", Names.channel("CO: Caracol HD"))
        assertEquals("A&E HD", Names.channel("A&E HD 70-61"))
        assertEquals("A&E SD", Names.channel("A&E SD 70"))
        assertEquals("CANAL 13", Names.channel("Canal 13"))
        assertEquals("USA NETWORK", Names.channel("USA Network"))
        assertEquals("ESPN 2", Names.channel("⚽ ESPN 2 ⚽"))
    }

    @Test fun titulos() {
        assertEquals("Dune (2021)", Names.clean("★ Dune (2021) ★", titles = true))
        assertEquals("M*A*S*H", Names.clean("M*A*S*H", titles = true))
        assertEquals("¡Qué buena onda!", Names.clean("¡Qué buena onda!", titles = true))
        assertEquals("Dune (4K)", Names.clean("4K | Dune", titles = true))
    }

    @Test fun prefijoCompartido() {
        assertEquals(listOf("SPACE HD", "CINECANAL FHD", "TNT HD", "HBO"),
            Names.channelList(listOf("PREMIUM | SPACE HD", "PREMIUM | CINECANAL FHD", "PREMIUM | TNT HD", "HBO")))
        assertEquals(listOf("ESPN | 1", "ESPN | 2", "ESPN | 3"),
            Names.channelList(listOf("ESPN | 1", "ESPN | 2", "ESPN | 3")))
        assertEquals(listOf("FOX SPORTS", "PLUS | CANAL"), Names.channelList(listOf("Fox Sports", "Plus | Canal")))
        assertEquals("SPACE HD", Names.channel("PREMIUM | SPACE HD"))
    }

    @Test fun prefijoDeLaCategoria() {
        assertEquals(listOf("CNN EN ESPAÑOL", "CNN INGLES", "RT EN ESPANOL", "CNN INTERNACIONAL", "DW EN ESP", "IMAGEN"),
            Names.channelList(listOf("CNN en Español", "NOTI | CNN INGLES", "NOTI | RT EN ESPANOL", "NOTI | CNN INTERNACIONAL", "DW EN ESP", "IMAGEN"), "TV NOTICIAS"))
        assertEquals(listOf("ESPN | 2", "WIN SPORTS"), Names.channelList(listOf("ESPN | 2", "DEPORTES | WIN SPORTS"), "ESPN DEPORTES"))
    }

    @Test fun guiaVacia() {
        listOf("No Data", "NO DATA", "No Information", "Sin información", "N/A", "---").forEach { assertTrue(it, Names.isEmptyProgram(it)) }
        listOf("Rosalinda", "Noticias Caracol", "Data Science", "No Mires Arriba").forEach { assertFalse(it, Names.isEmptyProgram(it)) }
    }

    @Test fun flechasYPuntos() {
        val cat = Names.category("→ CULTURA ←")
        assertTrue(cat, cat.endsWith("CULTURA") && !cat.contains('→') && !cat.contains('←'))
        assertEquals("FUTBÓL CHILE", Names.clean("→ FUTBÓL CHILE ←").uppercase())
        assertEquals(listOf("LOVE NATURE (HD)", "TLC (HD) 1", "EL GOURMET (HD) 2"),
            Names.channelList(listOf("CUL• LOVE NATURE (HD)", "CUL• TLC (HD) »1«", "CUL• EL GOURMET (HD) »2«"), "→ CULTURA ←"))
        assertEquals("ESPN 2", Names.channel("➜ ESPN 2 ⬅"))
    }
}
