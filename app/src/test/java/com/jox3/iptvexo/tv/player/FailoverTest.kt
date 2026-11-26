package com.jox3.iptvexo.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FailoverTest {
    private fun same(a: String, b: String) = assertEquals("$a ≡ $b", Failover.identity(a), Failover.identity(b))
    private fun diff(a: String, b: String) = assertNotEquals("$a ≠ $b", Failover.identity(a), Failover.identity(b))

    @Test fun mismoCanal() {
        same("CNN EN ESPAÑOL FHD", "CNN en Español HD")
        same("CO | CNN EN ESPAÑOL", "CNN EN ESPAÑOL [1080p]")
        same("ESPN 2 HD", "ESPN 2 FHD")
        same("Caracol TV HD", "CARACOL TV")
        same("WIN SPORTS+ FHD", "Win Sports + HD")
    }

    @Test fun canalesDistintos() {
        diff("CNN International", "CNN en Español")
        diff("ESPN 2", "ESPN 3")
        diff("ESPN", "ESPN 2")
        diff("Win Sports", "Win Sports+")
        diff("FOX Sports", "FOX Sports 2")
    }
}
