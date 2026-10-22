package com.jox3.tvdesktop.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Carpeta de datos del programa: %APPDATA%\JOX3 TV (o ~/.jox3tv fuera de Windows). */
object AppDirs {
    /** Las pruebas automáticas usan una carpeta temporal en vez de la real. */
    @Volatile var override: File? = null

    val data: File
        get() {
            override?.let { return it.apply { mkdirs() } }
            val appData = System.getenv("APPDATA")
            val dir = if (!appData.isNullOrBlank()) File(appData, "JOX3 TV")
            else File(System.getProperty("user.home"), ".jox3tv")
            return dir.apply { mkdirs() }
        }
}

/**
 * Guardado seguro de archivos de datos: se escribe en un temporal y se reemplaza
 * de un solo golpe. Si se va la luz o el programa se cierra a mitad, queda el
 * archivo anterior completo, nunca uno a medias. La versión previa queda en .bak.
 */
object SafeFile {
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists()) runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Lee el archivo; si está dañado ([parse] falla), prueba con la copia .bak. */
    fun <T> read(file: File, parse: (String) -> T): T? {
        for (f in listOf(file, File(file.parentFile, file.name + ".bak"))) {
            if (!f.exists()) continue
            try { return parse(f.readText(Charsets.UTF_8)) } catch (e: Exception) {
                Log.w("No se pudo leer ${f.name}, se intenta la copia", e)
            }
        }
        return null
    }
}
