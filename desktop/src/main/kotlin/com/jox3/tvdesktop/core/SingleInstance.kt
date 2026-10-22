package com.jox3.tvdesktop.core

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * Una sola copia del programa abierta a la vez (dos copias usarían doble conexión
 * de la cuenta IPTV y pelearían por los mismos archivos).
 *
 * - La copia principal bloquea instance.lock. Windows suelta ese bloqueo solo cuando
 *   el programa se cierra, aunque sea por un fallo: nunca queda "trabado".
 * - Además escucha en un puerto local (solo de este PC, anotado en instance.port):
 *   si se abre otra copia, esta le pide a la principal que se muestre al frente y se cierra.
 */
object SingleInstance {
    enum class Result { PRIMARY, SHOWN_EXISTING, ALREADY_RUNNING }

    private const val HELLO = "JOX3TV-SHOW"
    private var server: ServerSocket? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    private val lockFile get() = File(AppDirs.data, "instance.lock")
    private val portFile get() = File(AppDirs.data, "instance.port")

    fun acquire(onShowRequest: () -> Unit): Result {
        val got = try {
            val ch = RandomAccessFile(lockFile, "rw").channel
            val l = ch.tryLock()
            if (l == null) { ch.close(); false } else { channel = ch; lock = l; true }
        } catch (e: Exception) {
            Log.w("No se pudo comprobar si ya hay una copia abierta", e)
            return Result.PRIMARY // ante la duda, abrir
        }
        if (!got) {
            // Otra copia está abierta: pedirle que se muestre (puede estar terminando de arrancar).
            repeat(10) {
                if (ping()) { Log.i("Ya había una copia abierta: se mostró al frente"); return Result.SHOWN_EXISTING }
                Thread.sleep(300)
            }
            return Result.ALREADY_RUNNING
        }
        startServer(onShowRequest)
        return Result.PRIMARY
    }

    private fun ping(): Boolean = try {
        val port = portFile.readText().trim().toInt()
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 700)
            s.soTimeout = 1500
            s.getOutputStream().write("$HELLO\n".toByteArray()); s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readLine() == "OK"
        }
    } catch (_: Exception) { false }

    private fun startServer(onShowRequest: () -> Unit) {
        try {
            val ss = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            server = ss
            portFile.writeText(ss.localPort.toString())
            thread(isDaemon = true, name = "single-instance") {
                while (!ss.isClosed) {
                    try {
                        ss.accept().use { c ->
                            c.soTimeout = 1000
                            if (c.getInputStream().bufferedReader().readLine() == HELLO) {
                                c.getOutputStream().write("OK\n".toByteArray()); c.getOutputStream().flush()
                                onShowRequest()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            Log.w("No se pudo activar el aviso entre copias", e) // no impide abrir
        }
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { portFile.delete() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }
}
