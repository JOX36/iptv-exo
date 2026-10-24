package com.jox3.tvdesktop

import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.ProfileStore
import com.jox3.tvdesktop.data.Profiles
import com.jox3.tvdesktop.data.SavedItem
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mismo formato que Android: los perfiles se comparten con el TV Box y el celular. */
class ProfilesTest {
    init { TestDirs.root }

    private val a = Account("http://a.tv", "ana", "secreta")
    private val b = Account("http://b.tv", "bob", "2")

    @Test fun pinIgualQueAndroid() {
        assertEquals("v2:963403fe89e1762a516b6b32cd279109c0f90f88bced330d0b54226cca12b0bb", Profiles.hashPin("p123", "4321"))
    }

    @Test fun principalSiempreExiste() {
        val s = ProfileStore(a, TestDirs.fresh("prof-main"))
        assertEquals(Profiles.MAIN, s.list.first().id)
        s.delete(s.list.first())               // no se puede borrar
        assertNotNull(s.byId(Profiles.MAIN))
    }

    @Test fun maximoCinco() {
        val s = ProfileStore(a, TestDirs.fresh("prof-max"))
        repeat(4) { s.add("P$it", "🦁", Profiles.COLORS[0], null) }
        assertFalse(s.canAdd)
        assertEquals(5, s.list.size)
    }

    @Test fun pinConPinYOlvido() {
        val s = ProfileStore(a, TestDirs.fresh("prof-pin"))
        val p = s.add("María", "🌸", Profiles.COLORS[7], "1234")
        assertTrue(p.hasPin)
        assertFalse(s.checkPin(p, "0000"))
        assertTrue(s.checkPin(p, "1234"))
    }

    @Test fun formatoEnLaNube() {
        val s = ProfileStore(a, TestDirs.fresh("prof-nube"))
        val p = s.add("Juan", "⚽", Profiles.COLORS[3], null)
        val c = s.pendingChanges().single { it.getString("item") == "p:${p.id}" }
        assertEquals("profile", c.getString("list"))
        val data = JSONObject(c.getString("data"))
        assertEquals("Juan", data.getString("name"))
        assertEquals(Profiles.COLORS[3], data.getLong("color"))   // color ARGB como número, igual que Android
        assertTrue(data.has("vault") && data.has("vaultN"))
    }

    @Test fun unaSolaListaParaTodosLosServidores() {
        val dir = TestDirs.fresh("prof-equipo")
        val sa = ProfileStore(a, dir)
        val p = sa.add("Sofía", "🦄", Profiles.COLORS[6], null)
        val sb = ProfileStore(b, dir)                 // otro servidor del mismo equipo
        assertNotNull(sb.byId(p.id))
    }

    @Test fun llegaDeOtroEquipoYGanaElMasNuevo() {
        val s = ProfileStore(a, TestDirs.fresh("prof-remoto"))
        val data = JSONObject().put("id", "pabc").put("name", "Abuela").put("emoji", "👑").put("color", Profiles.COLORS[1])
            .put("pin", "").put("created", 5).put("ts", 5000).put("deleted", false).put("vault", "").put("vaultN", 0)
        s.applyRemote(listOf(JSONObject().put("list", "profile").put("item", "p:pabc").put("ts", 5000).put("deleted", false).put("data", data.toString())))
        assertEquals("Abuela", s.byId("pabc")?.name)
        s.applyRemote(listOf(JSONObject().put("list", "profile").put("item", "p:pabc").put("ts", 4000).put("deleted", true)))
        assertNotNull(s.byId("pabc"))   // el borrado viejo no gana
        s.applyRemote(listOf(JSONObject().put("list", "profile").put("item", "p:pabc").put("ts", 9000).put("deleted", true)))
        assertNull(s.byId("pabc"))
    }

    @Test fun historialSeparadoPorPerfil() {
        val dir = TestDirs.fresh("prof-lib")
        val main = LibraryStore(a, Profiles.MAIN, dir)
        val kid = LibraryStore(a, "pkid", dir)
        main.toggleFavorite(SavedItem("movie", "1", "Terror", ""))
        assertTrue(main.isFavorite("movie", "1"))
        assertFalse(kid.isFavorite("movie", "1"))
        assertEquals(LibraryStore.keyHash(a.key) + ".json", LibraryStore.fileName(a.key, Profiles.MAIN)) // el principal conserva su archivo
    }

    @Test fun borrarPerfilBorraSuHistorial() {
        val s = ProfileStore(a, TestDirs.fresh("prof-borrar"))
        val p = s.add("Temporal", "🐸", Profiles.COLORS[2], null)
        val lib = File(AppDirs.data, "library/" + LibraryStore.fileName(a.key, p.id))
        lib.parentFile.mkdirs(); lib.writeText("{}")
        s.delete(p)
        assertFalse(lib.exists())
        assertNull(s.byId(p.id))
    }
}
