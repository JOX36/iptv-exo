package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.Movie
import com.jox3.tvdesktop.data.Series
import com.jox3.tvdesktop.data.ServerHub
import com.jox3.tvdesktop.data.Tmdb
import com.jox3.tvdesktop.data.TvRepo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {
    init { TestDirs.root }

    private val a = Account("http://a.tv", "ana", "1")
    private val idx = TvRepo.Index(
        channels = listOf(Channel("1", "CO | Caracol HD", "", "c", 1), Channel("2", "ESPN", "", "c", 2)),
        movies = listOf(
            Movie("10", "Acción Total (1990) [4K]", "", "7", "mkv", "m", 0),
            Movie("11", "Acción Total LATINO", "http://img/p.jpg", "", "mp4", "m", 0),
        ),
        series = listOf(Series("20", "Los Simpson", "", "8", "s", 0)),
    )

    @Test fun buscaSinTildesYJuntaCopias() = runBlocking {
        val r = ServerHub.search("accion", a, idx, listOf(a))
        assertEquals(1, r.movies.size)               // las dos copias son el mismo título
        assertEquals(2, r.movies[0].copies.size)
        assertEquals("http://img/p.jpg", r.movies[0].image) // se muestra la que trae carátula
        assertTrue(r.channels.isEmpty())
    }

    @Test fun buscaCanalesYSeries() = runBlocking {
        assertEquals("CO | Caracol HD", ServerHub.search("caracol", a, idx, listOf(a)).channels.single().name)
        assertEquals("Los Simpson", ServerHub.search("simpson", a, idx, listOf(a)).series.single().name)
    }

    @Test fun etiquetasDeCalidadEIdioma() {
        assertEquals("4K", ServerHub.tagOf("Acción Total (1990) [4K]"))
        assertEquals("Latino", ServerHub.tagOf("Acción Total LATINO"))
        assertEquals("FHD · Subtitulada", ServerHub.tagOf("Película 1080p SUB"))
    }

    @Test fun letrasDeServidores() {
        val b = Account("http://b.tv", "bob", "2")
        assertEquals("A", ServerHub.letter(listOf(a, b), a))
        assertEquals("B", ServerHub.letter(listOf(a, b), b))
    }

    @Test fun tituloLimpioParaTmdb() {
        assertEquals("Rocky II", Tmdb.cleanTitle("Rocky II (1979) [4K]"))
        assertEquals("Titanic", Tmdb.cleanTitle("ES | Titanic 1997"))
        assertEquals("1979", Tmdb.yearOf("Rocky II (1979) [4K]"))
    }

    @Test fun mismoTituloEnDistintasCopias() {
        assertEquals(TvRepo.titleKey("Acción Total (1990) [4K]"), TvRepo.titleKey("Acción Total LATINO"))
    }

    @Test fun historialSinPedazos() {
        val lib = LibraryStore(a, dir = TestDirs.fresh("search"))
        lib.addSearch("los"); lib.addSearch("los simps"); lib.addSearch("los simpson")
        assertEquals(listOf("los simpson"), lib.searchHistory.toList())
        lib.addSearch("x") // muy corta: no se guarda
        assertEquals(1, lib.searchHistory.size)
    }
}
