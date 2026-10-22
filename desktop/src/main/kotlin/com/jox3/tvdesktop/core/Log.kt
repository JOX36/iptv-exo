package com.jox3.tvdesktop.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Registro de errores en %APPDATA%\JOX3 TV\logs\jox3.log.
 * Sirve para saber qué pasó cuando algo falla en el PC de alguien.
 * - Nunca escribe contraseñas: las oculta en URLs y textos (ver [mask]).
 * - Si pasa de 1 MB, el archivo viejo se guarda como jox3.log.1 y se empieza otro.
 * - Si no puede escribir (disco lleno, sin permisos), no hace nada: jamás cierra el programa.
 */
object Log {
    private const val MAX_BYTES = 1_000_000L
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    @Volatile private var dir: File? = null

    val folder: File get() = dir ?: File(AppDirs.data, "logs").also { it.mkdirs(); dir = it }
    private val file: File get() = File(folder, "jox3.log")

    fun i(msg: String) = write("INFO ", msg, null)
    fun w(msg: String, e: Throwable? = null) = write("AVISO", msg, e)
    fun e(msg: String, e: Throwable? = null) = write("ERROR", msg, e)

    /** Oculta contraseñas: password=..., /live/usuario/clave/..., /movie/..., /series/... */
    fun mask(text: String): String = text
        .replace(Regex("""(password=)[^&\s"']+""", RegexOption.IGNORE_CASE), "$1***")
        .replace(Regex("""(/(?:live|movie|series|timeshift)/[^/\s]+/)[^/\s]+"""), "$1***")

    private fun write(level: String, msg: String, e: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(time)).append(' ').append(level).append(' ')
            append('[').append(Thread.currentThread().name).append("] ")
            append(mask(msg))
            if (e != null) {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                append('\n').append(mask(sw.toString().trimEnd()))
            }
            append('\n')
        }
        synchronized(lock) {
            try {
                val f = file
                if (f.length() > MAX_BYTES) {
                    val old = File(folder, "jox3.log.1")
                    old.delete(); f.renameTo(old)
                }
                f.appendText(line, Charsets.UTF_8)
            } catch (_: Throwable) { /* sin registro, pero el programa sigue */ }
        }
        print(line) // al compilar a mano también sale en la consola
    }
}
