package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.AccountStore
import com.jox3.tvdesktop.data.Profile
import com.jox3.tvdesktop.data.Profiles
import com.jox3.tvdesktop.data.ServerAccess
import com.jox3.tvdesktop.data.Vault
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El código JOX3 debe dar EXACTAMENTE los mismos bytes que Android (PBKDF2-HMAC-SHA256 + AES-256-GCM).
 * Los valores esperados se calcularon aparte con otra implementación (Python hashlib / cryptography).
 */
class VaultTest {
    init { TestDirs.root }

    @Test fun formaDelCodigo() {
        val c = Vault.newCode()
        assertEquals(10, c.length)
        assertTrue(c.all { it in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" })
        assertEquals("JX-7K4P9-9QM2A", Vault.pretty("7K4P99QM2A"))
        assertEquals("7K4P99QM2A", Vault.normalize("jx-7k4p9 9qm2a"))
        assertNull(Vault.normalize("JX-0O1I0-00000"))   // sin 0, O, 1 ni I
    }

    @Test fun llaveYCasilleroComoAndroid() {
        val k = Vault.deriveNow("7K4P99QM2A")
        assertEquals("5ca190b539dd36196782e52c47c24e4ad3bba3c55c2e614e7114f708cb641613", k.id)
        assertEquals("463f299b4f0ae0fdf3dd078708e88088d2470dea735b90ca37b3f69cb9eafb4a", k.key.joinToString("") { "%02x".format(it) })
    }

    @Test fun descifraLoQueCifroOtroEquipo() {
        val k = Vault.deriveNow("7K4P99QM2A")
        val blob = "AAECAwQFBgcICQoLQUeX7MoGNzlyWqusmFmjLmlcP/eRBQB5xK+gJm2OTf8LUQ4zVxS/Ce28coPH4WjcOR2tOWtzJDn3YuHZ7ljYIGKei/cNuhLURpADB/AZ19ARs6iXTGx5vE6lspXLw+IteP6JISTsKrSc1pPfKQ=="
        val o = JSONObject(Vault.decrypt(k, blob)!!)
        assertEquals("p1", o.getString("profile"))
        assertEquals("ana", o.getJSONArray("servers").getJSONObject(0).getString("user"))
    }

    @Test fun cifraYDescifra() {
        val k = Vault.deriveNow("ABCDEFGH23")
        val plain = Vault.contentJson(listOf(Account("http://a.tv", "ana", "clave")), "p9", 123)
        val enc = Vault.encrypt(k, plain)
        assertEquals(plain, Vault.decrypt(k, enc))
        assertNull(Vault.decrypt(Vault.deriveNow("ABCDEFGH24"), enc))   // con otro código no se puede leer
        val o = JSONObject(plain)
        assertEquals(1, o.getInt("v")); assertEquals(123, o.getLong("updated"))
    }

    @Test fun codigoGuardadoCifradoEnElEquipo() {
        Vault.saveLocal("casillero1", "7K4P99QM2A")
        assertEquals("7K4P99QM2A", Vault.localCode("casillero1"))
        Vault.forgetLocal("casillero1")
        assertNull(Vault.localCode("casillero1"))
    }

    @Test fun cadaPerfilVeSoloSusServidores() {
        File(TestDirs.root, "owners.json").delete()
        val casa = Account("http://casa.tv", "c", "1")
        val sofia = Account("http://sofia.tv", "s", "2")
        val all = listOf(casa, sofia)
        val pSofia = Profile("psofia", "Sofía", "🦄", Profiles.COLORS[6])
        val pMain = Profile(Profiles.MAIN, "Principal", "😎", Profiles.COLORS[4])
        assertEquals(all, ServerAccess.visible(all, pMain))          // sin dueños: todos ven todo
        ServerAccess.setOwner(sofia.key, pSofia.id)
        assertEquals(listOf(sofia), ServerAccess.visible(all, pSofia)) // Sofía ve SOLO los suyos
        assertEquals(listOf(casa), ServerAccess.visible(all, pMain))   // los demás nunca ven los de otro
        ServerAccess.release(pSofia)
        assertEquals(all, ServerAccess.visible(all, pSofia))
    }

    @Test fun servidorNuevoEnPerfilConPropios() {
        File(TestDirs.root, "owners.json").delete()
        val store = AccountStore(File(TestDirs.fresh("acc-vault"), "accounts.json"))
        val p = Profile("pjuan", "Juan", "⚽", Profiles.COLORS[3])
        val a1 = Account("http://j1.tv", "j", "1"); val a2 = Account("http://j2.tv", "j", "2")
        store.use(a1); ServerAccess.setOwner(a1.key, p.id)
        Profiles.current = p
        store.use(a2); ServerAccess.claimNew(store, a2)
        assertEquals(p.id, ServerAccess.owner(a2.key))
        Profiles.current = null
        assertNotNull(store.all().firstOrNull { it.key == a2.key })
    }
}
