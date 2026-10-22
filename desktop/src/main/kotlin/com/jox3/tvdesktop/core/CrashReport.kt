package com.jox3.tvdesktop.core

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Informe de cierre inesperado.
 *
 * - Al abrir se deja una marca (session.running) que se borra al cerrar bien. Si al abrir
 *   la marca sigue ahí, la vez anterior el programa se cerró de golpe (fallo de Java,
 *   del reproductor, falta de memoria o se apagó el PC sin cerrar).
 * - Si Java dejó su archivo de fallo (hs_err_pid*.log), se mueve a la carpeta de registros.
 * - [build] arma un texto con la versión, el equipo y lo último del registro (sin contraseñas)
 *   para copiarlo y enviarlo.
 */
object CrashReport {
    private val marker get() = File(AppDirs.data, "session.running")

    /** true si la sesión anterior no terminó bien. Deja la marca para esta sesión. */
    fun startSession(): Boolean {
        val crashed = marker.exists()
        if (crashed) Log.w("La sesión anterior terminó de forma inesperada (${marker.readTextOrNull()?.trim().orEmpty()})")
        collectJvmCrashFiles()
        runCatching { marker.writeText(LocalDateTime.now().toString()) }
        return crashed
    }

    /** Cierre normal: se borra la marca. */
    fun endSession() { runCatching { marker.delete() } }

    /** Busca archivos de fallo de Java donde suelen quedar y los guarda junto al registro. */
    private fun collectJvmCrashFiles() {
        val dirs = listOfNotNull(
            System.getProperty("user.dir"),
            System.getProperty("java.io.tmpdir"),
            System.getProperty("compose.application.resources.dir")?.let { File(it).parent },
        ).map(::File).distinct()
        for (d in dirs) {
            d.listFiles { f -> f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }?.forEach { f ->
                // Solo los recientes (últimos 7 días): en la carpeta temporal puede haber de otros programas Java
                if (System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000) return@forEach
                val text = f.readTextOrNull() ?: return@forEach
                if (!text.contains("jox3", ignoreCase = true) && !text.contains("JOX3 TV")) return@forEach
                val dest = File(Log.folder, f.name)
                runCatching { dest.writeText(Log.mask(text)); f.delete() }
                Log.w("Se encontró un informe de fallo de Java: ${f.name}")
            }
        }
    }

    /** Texto del informe: versión, equipo, últimas líneas del registro y el último fallo de Java. */
    fun build(version: String): String = buildString {
        appendLine("=== Informe de JOX3 TV para PC ===")
        appendLine("Fecha: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
        appendLine("Versión: $version")
        appendLine("Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("Java: ${System.getProperty("java.version")} · Memoria máx.: ${Runtime.getRuntime().maxMemory() / 1_048_576} MB")
        appendLine()
        appendLine("--- Últimas líneas del registro ---")
        val logs = listOf(File(Log.folder, "jox3.log.1"), File(Log.folder, "jox3.log")).filter { it.exists() }
        val lines = logs.flatMap { it.readTextOrNull()?.lines().orEmpty() }.takeLast(250)
        lines.forEach { appendLine(Log.mask(it)) }
        Log.folder.listFiles { f -> f.name.startsWith("hs_err_pid") }?.maxByOrNull { it.lastModified() }?.let { f ->
            appendLine()
            appendLine("--- Fallo de Java (${f.name}) ---")
            f.readTextOrNull()?.lines()?.take(60)?.forEach { appendLine(Log.mask(it)) }
        }
    }

    /** Copia el informe al portapapeles (para pegarlo en un mensaje). */
    fun copyToClipboard(version: String): Boolean = try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(build(version)), null); true
    } catch (e: Exception) { Log.w("No se pudo copiar el informe", e); false }

    private fun File.readTextOrNull(): String? = runCatching { readText(Charsets.UTF_8) }.getOrNull()
}
