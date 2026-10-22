package com.jox3.tvdesktop.core

import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Evita el protector de pantalla y la suspensión MIENTRAS se reproduce un video.
 * Cada 30 s, si hay video sonando, se le avisa a Windows que hay actividad (lo mismo que
 * hacen VLC o el navegador con YouTube). En pausa o sin video no se avisa: Windows
 * vuelve a apagar la pantalla como siempre.
 */
object KeepAwake {
    private interface Kernel32 : Library {
        @Suppress("FunctionName")
        fun SetThreadExecutionState(flags: Int): Int
    }

    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    private val k32: Kernel32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", true)) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.onFailure { Log.w("Sin control de pantalla encendida", it) }.getOrNull()
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "keep-awake").apply { isDaemon = true } }

    fun enabled() = Settings.bool("player.keepAwake", true)

    /** [playing] dice si hay un video reproduciéndose ahora mismo. */
    fun start(playing: () -> Boolean) {
        timer.scheduleWithFixedDelay({
            try {
                if (enabled() && playing()) k32?.SetThreadExecutionState(ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
            } catch (_: Throwable) { }
        }, 5, 30, TimeUnit.SECONDS)
    }
}
