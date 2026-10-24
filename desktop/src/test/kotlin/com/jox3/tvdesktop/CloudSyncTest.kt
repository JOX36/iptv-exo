package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.CloudSync
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.SavedItem
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El formato debe ser EXACTAMENTE el de Android para compartir el historial con el TV Box y el celular. */
class CloudSyncTest {
    init { TestDirs.root }

    @Test fun huellaIgualQueAndroid() {
        // host en minúsculas y sin "/" final, usuario sin espacios, contraseña tal cual
        assertEquals("97d1c2d7de1f072cad7e31a39caa6294b2b2e7cc9a39a760589f94e5b918bffb", CloudSync.accountId(Account("  HTTP://A.TV/ ", " ana ", "Clave1")))
    }

    private fun lib(name: String) = LibraryStore(Account("http://a.tv", "ana", "1"), dir = TestDirs.fresh(name))

    @Test fun cambiosConFormatoDeAndroid() {
        val l = lib("formato")
        l.toggleFavorite(SavedItem("movie", "123", "Dune", "http://img/p.jpg", ext = "mkv"))
        val c = l.pendingChanges().single()
        assertEquals("fav", c.getString("list"))
        assertEquals("movie:123", c.getString("item"))
        assertFalse(c.getBoolean("deleted"))
        val data = JSONObject(c.getString("data"))   // data viaja como texto JSON, igual que Android
        assertEquals("Dune", data.getString("name"))
        assertEquals("mkv", data.getString("ext"))
    }

    @Test fun progresoSoloCada5Segundos() {
        val l = lib("progreso")
        val ep = SavedItem("episode", "55", "Serie · T1E2", "", seriesId = "9")
        l.saveProgress(ep, 60_000, 1_000_000)
        l.saveProgress(ep, 62_000, 1_000_000) // solo 2 s más: no se vuelve a subir
        val c = l.pendingChanges().single { it.getString("list") == "resume" }
        assertEquals("episode:55", c.getString("item"))
        assertEquals(60_000, JSONObject(c.getString("data")).getLong("pos"))
        l.saveProgress(ep, 70_000, 1_000_000)
        assertEquals(70_000, JSONObject(l.pendingChanges().single { it.getString("list") == "resume" }.getString("data")).getLong("pos"))
    }

    @Test fun llegaDeOtroEquipo() {
        val l = lib("remoto")
        val data = JSONObject().put("kind", "movie").put("id", "7").put("name", "Titanic").put("image", "")
            .put("ext", "mp4").put("seriesId", "").put("categoryId", "").put("ts", 5000).put("pos", 600_000).put("dur", 7_000_000)
        l.applyRemote(listOf(
            JSONObject().put("list", "resume").put("item", "movie:7").put("ts", 5000).put("deleted", false).put("data", data.toString()),
            JSONObject().put("list", "fav").put("item", "live:9").put("ts", 5000).put("deleted", false)
                .put("data", JSONObject().put("kind", "live").put("id", "9").put("name", "co | caracol").toString()),
        ))
        assertEquals(600_000, l.positionOf("7")?.position)
        assertTrue(l.isFavorite("live", "9"))
        assertEquals("CARACOL", l.favorites.first().name) // nombre limpio, como en Android
    }

    @Test fun ganaLoMasNuevo() {
        val l = lib("gana")
        l.toggleFavorite(SavedItem("movie", "1", "A", ""))  // cambio local de ahora mismo
        l.applyRemote(listOf(JSONObject().put("list", "fav").put("item", "movie:1").put("ts", 1000).put("deleted", true)))
        assertTrue(l.isFavorite("movie", "1"))              // el borrado viejo no gana
        l.applyRemote(listOf(JSONObject().put("list", "fav").put("item", "movie:1")
            .put("ts", System.currentTimeMillis() + 60_000).put("deleted", true)))
        assertFalse(l.isFavorite("movie", "1"))             // el más nuevo sí
    }

    @Test fun borradoDeSeguirViendo() {
        val l = lib("borrado")
        l.saveProgress(SavedItem("movie", "3", "X", ""), 50_000, 100_000)
        l.applyRemote(listOf(JSONObject().put("list", "resume").put("item", "movie:3")
            .put("ts", System.currentTimeMillis() + 60_000).put("deleted", true)))
        assertNull(l.positionOf("3"))
    }

    @Test fun pendientesSeLimpianAlEnviar() {
        val l = lib("enviado")
        l.toggleFavorite(SavedItem("movie", "1", "A", ""))
        val sent = l.pendingChanges()
        l.clearSent(sent)
        assertTrue(l.pendingChanges().isEmpty())
    }
}
