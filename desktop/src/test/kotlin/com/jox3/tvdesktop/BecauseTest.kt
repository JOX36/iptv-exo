package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Movie
import com.jox3.tvdesktop.data.TvRepo
import kotlin.test.Test
import kotlin.test.assertEquals

class BecauseTest {
    @Test fun sagaKeyIgualQueAndroid() {
        assertEquals("harry potter", TvRepo.sagaKey("Harry Potter y la piedra filosofal (2001)"))
        assertEquals("rapidos y furiosos", TvRepo.sagaKey("Rápidos y furiosos 5"))
        assertEquals("matrix", TvRepo.sagaKey("Matrix: Reloaded"))
    }

    @Test fun generosEnInglesSeTraducen() {
        assertEquals(listOf("accion", "comedia", "ciencia"), TvRepo.genreWords("Action, Comedy / Science Fiction"))
    }

    @Test fun seQuedaLaCopiaConCaratula() {
        val a = Movie("1", "Avatar [4K]", "", "", "mp4", "1", 0)
        val b = Movie("2", "Avatar (2009)", "http://img/a.jpg", "", "mp4", "2", 0)
        assertEquals(listOf("2"), TvRepo.bestCopies(listOf(a, b)).map { it.id })
    }
}
