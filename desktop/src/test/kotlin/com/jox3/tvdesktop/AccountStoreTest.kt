package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.AccountStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountStoreTest {
    private fun file(name: String) = File(TestDirs.fresh(name), "accounts.json")

    @Test fun guardaYRecupera() {
        val f = file("guarda")
        AccountStore(f).use(Account("http://a.tv", "ana", "clave1"))
        val again = AccountStore(f)
        assertEquals(1, again.accounts.size)
        assertEquals("clave1", again.current()?.pass)
    }

    @Test fun laContrasenaNoQuedaVisibleEnWindows() {
        val f = file("cifra")
        AccountStore(f).use(Account("http://a.tv", "ana", "clave-super-secreta"))
        val onWindows = System.getProperty("os.name").startsWith("Windows")
        assertEquals(!onWindows, f.readText().contains("clave-super-secreta"))
    }

    @Test fun masRecientePrimeroYMaximoDiez() {
        val s = AccountStore(file("max"))
        repeat(12) { s.use(Account("http://s$it.tv", "u", "p")) }
        assertEquals(AccountStore.MAX, s.accounts.size)
        assertEquals("http://s11.tv", s.accounts.first().host)
    }

    @Test fun repetirCuentaNoDuplica() {
        val s = AccountStore(file("dup"))
        s.use(Account("http://a.tv", "ana", "1")); s.use(Account("http://b.tv", "bob", "2")); s.use(Account("http://a.tv", "ana", "3"))
        assertEquals(2, s.accounts.size); assertEquals("3", s.accounts.first().pass)
    }

    @Test fun archivoDanadoNoCierraElPrograma() {
        val f = file("danado"); f.writeText("{esto no es json")
        val s = AccountStore(f)
        assertTrue(s.accounts.isEmpty()); assertNull(s.current())
    }

    @Test fun archivoDanadoUsaLaCopia() {
        val f = file("copia")
        val s = AccountStore(f)
        s.use(Account("http://a.tv", "ana", "1"))
        s.use(Account("http://b.tv", "bob", "2")) // aquí se crea la copia .bak con "ana"
        f.writeText("dañado")
        val again = AccountStore(f)
        assertEquals("ana", again.accounts.single().user)
    }

    @Test fun cerrarSesionMantieneLaCuenta() {
        val f = file("logout")
        val s = AccountStore(f); s.use(Account("http://a.tv", "ana", "1")); s.logout()
        val again = AccountStore(f)
        assertNull(again.current()); assertFalse(again.accounts.isEmpty())
    }

    @Test fun contrasenaIlegibleSePideDeNuevo() {
        val f = file("ilegible")
        f.writeText("""{"list":[{"host":"http://a.tv","user":"ana","pass":"dpapi:AAAA"}],"current":"http://a.tv|ana"}""")
        val s = AccountStore(f)
        assertEquals("", s.accounts.single().pass)
    }
}
