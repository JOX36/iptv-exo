package com.jox3.tvdesktop.core

import com.jox3.tvdesktop.data.Net
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Autoprueba del programa YA EMPAQUETADO (lo que recibe el usuario).
 * CI lo ejecuta con `JOX3 TV.exe --self-test resultado.txt` antes de publicar:
 * así se detecta si al instalador le falta algo (fuentes, HTTPS, cifrado,
 * fechas en español, motor gráfico) aunque el código compile bien.
 */
object SelfTest {
    fun run(out: File): Int {
        val lines = mutableListOf<String>()
        var ok = true
        fun check(name: String, block: () -> String) {
            val r = try { "OK    $name: ${block()}" } catch (e: Throwable) { ok = false; "FALLA $name: $e" }
            lines += r
        }

        check("Versión") { System.getProperty("jox3.version") ?: error("sin versión") }
        check("Recursos") {
            listOf("icon.png", "font/exo2_regular.ttf", "font/exo2_medium.ttf", "font/exo2_semibold.ttf").forEach {
                SelfTest::class.java.classLoader.getResource(it) ?: error("falta $it")
            }
            "icono y fuentes Exo 2"
        }
        check("Carpeta de datos") {
            val f = File(AppDirs.data, "selftest.tmp"); f.writeText("ok"); f.delete(); AppDirs.data.path
        }
        check("Cifrado de contraseñas") {
            val enc = Secrets.protect("prueba-123")
            require(Secrets.unprotect(enc) == "prueba-123") { "no descifra" }
            enc.substringBefore(':')
        }
        check("Fechas en español") {
            val m = SimpleDateFormat("MMMM", Locale.forLanguageTag("es"))
                .format(Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 15) }.time)
            require(m == "enero") { "mes = $m" }
            m
        }
        check("Conexión HTTPS") {
            val req = Request.Builder().url("https://www.google.com/generate_204").build()
            Net.api.newCall(req).execute().use { "HTTP ${it.code}" }
        }
        check("Motor gráfico (Skia)") {
            val bmp = androidx.compose.ui.graphics.ImageBitmap(16, 16)
            androidx.compose.ui.graphics.Canvas(bmp).drawRect(
                0f, 0f, 16f, 16f, androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color.Cyan },
            )
            "imagen ${bmp.width}x${bmp.height}"
        }

        lines += if (ok) "RESULTADO: OK" else "RESULTADO: FALLA"
        out.parentFile?.mkdirs()
        out.writeText(lines.joinToString("\n") + "\n")
        lines.forEach { Log.i("Autoprueba: $it") }
        return if (ok) 0 else 1
    }
}
