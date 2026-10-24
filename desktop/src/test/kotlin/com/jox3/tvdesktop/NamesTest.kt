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
}
