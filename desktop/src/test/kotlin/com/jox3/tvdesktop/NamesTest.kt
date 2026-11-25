package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Names
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mismos casos que la app Android: los nombres deben salir idénticos en PC, TV y celular. */
class NamesTest {
    @Test fun quitaPrefijosDelServidor() {
        assertEquals("Noticias", Names.clean("TV | Noticias"))
        assertEquals("Telemundo", Names.clean("N | Telemundo"))
        assertEquals("EWTN", Names.clean("Rel | EWTN"))
        assertEquals("Deportes", Names.clean("CO: Deportes"))
    }

    @Test fun noTocaNombresReales() {
        assertEquals("ESPN | 1 ARG", Names.clean("ESPN | 1 ARG"))
    }

    @Test fun calidadPasaAlFinal() {
        assertEquals("Dune (4K)", Names.clean("4K | Dune", titles = true))
    }

    @Test fun titulosSoloConBarra() {
        assertEquals("DC: La Liga de la Justicia", Names.clean("DC: La Liga de la Justicia", titles = true))
    }

    @Test fun canalesEnMayusculas() {
        assertEquals("CARACOL HD", Names.channel("CO | Caracol HD"))
    }

    @Test fun categoriasConEmoji() {
        assertEquals("⚽ DEPORTES", Names.category("TV | Deportes"))
        assertEquals("🎵 MÚSICA", Names.category("Música"))
        assertEquals("🇨🇴 CANALES", Names.category("CO | Canales"))
        assertEquals("🎬 CINE", Names.category("🎬 Cine"))  // ya trae emoji: se respeta
    }

    // ── Igual que Android (NamesTest de la app): sin emojis, estrellas ni códigos ──
    @Test fun categoriasSinAdornos() {
        assertEquals("🇨🇴 COLOMBIA", Names.category("★-COLOMBIA-★"))
        assertEquals("🇧🇷 BIG BROTHER BRASIL", Names.category("🇧🇷 BIG BROTHER BRASIL"))
        assertEquals("⚽ TIGO SPORTS", Names.category("⭐-TIGO SPORTS-⭐"))
        assertEquals("ALQUILER VIP", Names.category("★-ALQUILER VIP-★"))
        assertEquals("🇵🇷 PUERTO RICO", Names.category("★-PUERTO RICO-★"))
        assertEquals("📰 NOTICIAS", Names.category("TV | Noticias"))
    }

    @Test fun canalesLimpios() {
        assertEquals("CARACOL", Names.channel("*Caracol*"))
        assertEquals("CARACOL", Names.channel("COL caracol"))
        assertEquals("CARACOL HD", Names.channel("CO: Caracol HD"))
        assertEquals("A&E HD", Names.channel("A&E HD 70-61"))
        assertEquals("A&E SD", Names.channel("A&E SD 70"))
        assertEquals("CANAL 13", Names.channel("Canal 13"))
        assertEquals("USA NETWORK", Names.channel("USA Network"))
        assertEquals("ESPN 2", Names.channel("⚽ ESPN 2 ⚽"))
    }

    @Test fun titulosSinAdornos() {
        assertEquals("Dune (2021)", Names.clean("★ Dune (2021) ★", titles = true))
        assertEquals("M*A*S*H", Names.clean("M*A*S*H", titles = true))
        assertEquals("¡Qué buena onda!", Names.clean("¡Qué buena onda!", titles = true))
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
}
