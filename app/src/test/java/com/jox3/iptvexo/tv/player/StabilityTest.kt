package com.jox3.iptvexo.tv.player

import org.junit.Assert.assertTrue
import org.junit.Test

/** El cálculo del puntaje sin Android: misma fórmula que Stability.score (sin calidad ni estado del servidor). */
class StabilityTest {
    private fun measured(starts: Int, ok: Int, stalls: Int, msTotal: Long): Double {
        val success = (ok + 1.0) / (starts + 2.0)
        val stallRate = stalls.toDouble() / maxOf(ok, 1)
        val avgSec = msTotal / 1000.0 / maxOf(ok, 1)
        val m = 100 * success - minOf(stallRate, 3.0) * 12 - minOf(avgSec, 8.0) * 2.5
        val w = starts / (starts + 3.0)
        return w * m + (1 - w) * 60
    }

    @Test fun estableGanaAInestable() {
        val buena = measured(10, 10, 0, 15_000)      // arranca siempre, rápido, no se pega
        val mala = measured(10, 4, 8, 60_000)        // falla más de la mitad y se pega
        val nueva = measured(0, 0, 0, 0)             // sin datos
        assertTrue("buena $buena > nueva $nueva", buena > nueva)
        assertTrue("nueva $nueva > mala $mala", nueva > mala)
    }
}
