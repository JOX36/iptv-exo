package com.jox3.tvdesktop.player

import com.jox3.tvdesktop.core.Log
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import java.io.File

/**
 * Le dice a vlcj dónde está el VLC que viene DENTRO del programa (carpeta de recursos/vlc).
 * Tiene la prioridad más alta: se usa ese antes que cualquier VLC instalado en el PC,
 * así todos los usuarios tienen exactamente la misma versión probada.
 */
class BundledVlcProvider : DiscoveryDirectoryProvider {
    override fun priority(): Int = 1000
    override fun supported(): Boolean = VlcRuntime.bundledDir() != null
    override fun directories(): Array<String> = VlcRuntime.bundledDir()?.let { arrayOf(it.absolutePath) } ?: emptyArray()
}

/** Arranque único de libVLC para todo el programa. */
object VlcRuntime {
    @Volatile private var factory: MediaPlayerFactory? = null
    @Volatile var error: String? = null
        private set
    @Volatile var source: String = ""
        private set

    /** VLC incluido en el instalador (null si se compila a mano sin él). */
    fun bundledDir(): File? {
        val res = System.getProperty("compose.application.resources.dir") ?: return null
        val dir = File(res, "vlc")
        return dir.takeIf { File(it, "libvlc.dll").exists() || File(it, "libvlc.so").exists() }
    }

    /** Devuelve la fábrica de reproductores, o null si VLC no está disponible (con [error]). */
    @Synchronized
    fun factory(): MediaPlayerFactory? {
        factory?.let { return it }
        if (error != null) return null
        return try {
            val discovery = NativeDiscovery()
            val found = discovery.discover()
            source = when {
                !found -> "no encontrado"
                bundledDir() != null && discovery.discoveredPath()?.let { File(it).canonicalPath == bundledDir()!!.canonicalPath } == true -> "incluido"
                else -> "del sistema (${discovery.discoveredPath()})"
            }
            if (!found) throw IllegalStateException("No se encontró VLC")
            MediaPlayerFactory(
                "--no-video-title-show", // sin el nombre del archivo encima del video
                "--no-osd",
                "--no-snapshot-preview",
                "--no-lua",
                "--http-reconnect",      // si la conexión se corta, VLC intenta seguir
                "--quiet",
            ).also {
                factory = it
                Log.i("VLC ${version()} listo ($source)")
            }
        } catch (e: Throwable) {
            error = "No se pudo iniciar el reproductor: ${e.message ?: e.javaClass.simpleName}"
            Log.e("No se pudo iniciar VLC", e)
            null
        }
    }

    fun version(): String = try { factory?.application()?.version() ?: "?" } catch (_: Throwable) { "?" }

    @Synchronized
    fun release() {
        runCatching { factory?.release() }
        factory = null
    }
}
