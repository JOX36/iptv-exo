package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.Channel
import com.jox3.tvdesktop.data.EpgItem
import com.jox3.tvdesktop.data.Reminders
import com.jox3.tvdesktop.data.TvRepo
import com.jox3.tvdesktop.data.XtreamApi
import java.io.File
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchupRemindersTest {
    private val acc = Account("http://a.tv:8080", "ana", "1")
    private val H = 3_600_000L

    @Test fun urlDeRepeticionIgualQueAndroid() {
        // 2026-10-28 20:00 en Bogotá = 2026-10-29 01:00 UTC
        val start = 1_793_235_600_000L
        val url = XtreamApi(acc).timeshiftUrl("55", start, 125, TimeZone.getTimeZone("America/Bogota"))
        assertEquals("http://a.tv:8080/timeshift/ana/1/125/2026-10-28:20-00/55.ts", url)
    }

    @Test fun soloConArchivoYYaEmpezado() {
        val now = 1_000_000_000_000L
        val conArchivo = Channel("1", "Canal", "", "c", 1, archiveDays = 2)
        val sinArchivo = Channel("2", "Otro", "", "c", 2)
        val pasado = EpgItem("Partido", "", now - 2 * H, now - H)
        assertTrue(TvRepo.canCatchup(conArchivo, pasado, now))
        assertFalse(TvRepo.canCatchup(sinArchivo, pasado, now))
        assertFalse(TvRepo.canCatchup(conArchivo, EpgItem("Luego", "", now + H, now + 2 * H), now))
        assertFalse(TvRepo.canCatchup(conArchivo, EpgItem("Viejo", "", now - 3 * 86_400_000L, now - 3 * 86_400_000L + H), now))
    }

    @Test fun recordatorioAvisaUnaSolaVez() {
        Reminders.fileOverride = File(TestDirs.fresh("rem"), "reminders.json")
        val ch = Channel("9", "Win", "", "c", 9)
        val now = System.currentTimeMillis()
        val p = EpgItem("Final", "", now + 30_000, now + H)
        Reminders.toggle(acc, ch, p)
        assertTrue(Reminders.has(acc, ch, p))
        val r = Reminders.checkDue(now)
        assertNotNull(r)
        Reminders.due.value = null
        assertNull(Reminders.checkDue(now + 1000))
        Reminders.toggle(acc, ch, p)
        assertFalse(Reminders.has(acc, ch, p))
        Reminders.fileOverride = null
    }
}
