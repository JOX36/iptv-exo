package com.jox3.tvdesktop

import com.jox3.tvdesktop.core.AppDirs
import java.nio.file.Files

/** Las pruebas nunca tocan los datos reales del usuario: usan una carpeta temporal. */
object TestDirs {
    val root = Files.createTempDirectory("jox3-test").toFile().also { AppDirs.override = it }
    fun fresh(name: String) = java.io.File(root, name).apply { deleteRecursively(); mkdirs() }
}
