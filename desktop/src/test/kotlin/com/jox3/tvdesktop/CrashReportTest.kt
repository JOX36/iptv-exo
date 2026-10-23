package com.jox3.tvdesktop

import com.jox3.tvdesktop.core.CrashReport
import com.jox3.tvdesktop.core.Log
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportTest {
    init { TestDirs.root }

    @Test fun detectaCierreInesperado() {
        CrashReport.endSession()
        assertFalse(CrashReport.startSession())  // primera vez: todo normal
        assertTrue(CrashReport.startSession())   // sin cerrar bien: se detecta
        CrashReport.endSession()
        assertFalse(CrashReport.startSession())  // cerró bien: normal
        CrashReport.endSession()
    }

    @Test fun informeSinContrasenas() {
        Log.w("probando http://a.tv/live/ana/secreta/1.ts")
        val r = CrashReport.build("1.0.99")
        assertTrue(r.contains("1.0.99"))
        assertFalse(r.contains("secreta"))
    }
}
