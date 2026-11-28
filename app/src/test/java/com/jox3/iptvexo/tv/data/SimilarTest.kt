package com.jox3.iptvexo.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarTest {
    private val idx = TvRepo.Index(
        listOf(Channel("1", "CARACOL TV", "", "", 1)),
        listOf(Movie("10", "Rápidos y furiosos 7", "", "", "mp4", "", 0), Movie("11", "El padrino", "", "", "mp4", "", 0)),
        listOf(Series("20", "Los Simpson", "", "", "", 0), Series("21", "Malcolm el de en medio", "", "", "", 0)),
    )

    @Test fun erroresDeLetras() = runBlocking {
        assertEquals("Los Simpson", SmartSearch.similar("simsons", idx).firstOrNull())
        assertEquals("Rápidos y furiosos 7", SmartSearch.similar("rapidos furiozos", idx).firstOrNull())
        assertEquals("CARACOL TV", SmartSearch.similar("caracool", idx).firstOrNull())
        assertEquals("Malcolm el de en medio", SmartSearch.similar("malcom", idx).firstOrNull())
    }

    @Test fun nadaParecido() = runBlocking {
        assertTrue(SmartSearch.similar("zzzzqqq", idx).isEmpty())
    }
}
