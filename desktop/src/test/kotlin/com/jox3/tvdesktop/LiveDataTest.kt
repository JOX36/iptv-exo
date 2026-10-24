package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.EpgCountries
import com.jox3.tvdesktop.data.EpgItem
import com.jox3.tvdesktop.data.ExternalEpg
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.TvRepo
import com.jox3.tvdesktop.data.XtreamApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveDataTest {
    init { TestDirs.root }

    @Test fun guiaEnBase64() {
        assertEquals("Noticias de la noche", XtreamApi.b64("Tm90aWNpYXMgZGUgbGEgbm9jaGU="))
        assertEquals("", XtreamApi.b64(""))
    }

    @Test fun horaXmltv() {
        // 22 oct 2026 18:00 en Colombia (-0500) = 23:00 UTC
        assertEquals(1792710000L, ExternalEpg.xmltvTime("20261022180000 -0500"))
        assertEquals(0L, ExternalEpg.xmltvTime("basura"))
    }

    @Test fun paisDeCategoria() {
        assertEquals("CO", EpgCountries.ofCategory("CO | NACIONALES"))
        assertEquals("MX", EpgCountries.ofCategory("MÉXICO"))
        assertEquals("", EpgCountries.ofCategory("DEPORTES"))
    }

    @Test fun emparejaCanalesComoAndroid() {
        val caracol = ExternalEpg.Chan("CO1|Caracol.co", "Caracol.co", "CO1", listOf("Caracol TV")).apply {
            progs.add(ExternalEpg.Prog(1, 2, "Noticias", ""))
        }
        val dsports = ExternalEpg.Chan("CO1|DSports.co", "DSports.co", "CO1", listOf("DSports")).apply {
            progs.add(ExternalEpg.Prog(1, 2, "Fútbol", ""))
        }
        ExternalEpg.loadForTest(listOf(caracol, dsports))
        assertEquals(caracol.key, ExternalEpg.match("CO | CARACOL HD", null)?.key)
        assertEquals(dsports.key, ExternalEpg.match("DirecTV Sports FHD", null)?.key)
        assertNull(ExternalEpg.match("Canal Desconocido", null))
    }

    @Test fun programaActualYSiguiente() {
        val list = listOf(EpgItem("A", "", 0, 1000), EpgItem("B", "", 1000, 2000), EpgItem("C", "", 2000, 3000))
        val cur = TvRepo.nowPlaying(list, now = 1500)
        assertEquals("B", cur?.title)
        assertEquals("C", TvRepo.nextAfter(list, cur)?.title)
        assertEquals(0.5f, list[1].progress(1500))
    }

    @Test fun buscarSinTildes() {
        assertEquals("accion y emocion", TvRepo.normalize("Acción y Emoción"))
    }

    @Test fun favoritosYRecientesPorCuenta() {
        val dir = TestDirs.fresh("library")
        val acc = Account("http://a.tv", "ana", "1")
        val lib = LibraryStore(acc, dir = dir)
        val ch = SavedItem("live", "10", "Canal 10", "")
        assertTrue(lib.toggleFavorite(ch))
        repeat(25) { lib.addRecentChannel(SavedItem("live", "$it", "C$it", "")) }
        assertEquals(LibraryStore.MAX_RECENT, lib.recentChannels.size)
        Thread.sleep(400) // se guarda en segundo plano
        val again = LibraryStore(acc, dir = dir)
        assertTrue(again.isFavorite("live", "10"))
        assertEquals("24", again.recentChannels.first().id)
        // Otra cuenta no ve esos favoritos
        assertFalse(LibraryStore(Account("http://b.tv", "bob", "2"), dir = dir).isFavorite("live", "10"))
        assertFalse(lib.toggleFavorite(ch))
    }
}
