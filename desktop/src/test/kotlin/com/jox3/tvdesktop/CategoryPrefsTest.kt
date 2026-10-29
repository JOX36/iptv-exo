package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryPrefsTest {
    @Test fun seGuardaPorPerfil() {
        val dir = TestDirs.fresh("cats")
        val acc = Account("http://a.tv", "ana", "1")
        val lib = LibraryStore(acc, "p1", dir = dir)
        lib.saveCatPrefs(Section.LIVE, LibraryStore.CatPrefs(setOf("5"), listOf("9", "2"), mapOf("2" to "Fútbol")))
        Thread.sleep(400) // se guarda en segundo plano
        val again = LibraryStore(acc, "p1", dir = dir).catPrefs(Section.LIVE)
        assertEquals(setOf("5"), again.hidden)
        assertEquals(listOf("9", "2"), again.order)
        assertEquals("Fútbol", again.names["2"])
        // Otro perfil no ve esa organización
        assertTrue(LibraryStore(acc, "p2", dir = dir).catPrefs(Section.LIVE).hidden.isEmpty())
    }
}
