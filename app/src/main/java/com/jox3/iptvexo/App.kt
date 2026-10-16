package com.jox3.iptvexo

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de errores (traído de JOX3 Verifier).
 *
 * Si la app se cierra por un error, se guarda qué pasó para mostrarlo al abrirla
 * de nuevo (y poder arreglarlo sin adivinar). Si el error viene del decodificador
 * de audio FFmpeg, se apaga para que el reproductor siga funcionando.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val trace = sw.toString()
                val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                File(filesDir, CRASH_FILE).writeText("Versión $version · $stamp · hilo ${thread.name}\n${trace.take(8000)}")
                if (trace.contains("ffmpeg", ignoreCase = true)) PlayerSafety.disableFfmpeg(this)
            } catch (t: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"

        /** Devuelve el último error guardado (y lo borra), o "" si no hubo. */
        @JvmStatic
        fun takeLastCrash(ctx: Context): String {
            val f = File(ctx.filesDir, CRASH_FILE)
            if (!f.exists()) return ""
            return try { f.readText().also { f.delete() } } catch (e: Exception) { "" }
        }
    }
}

/**
 * Arranque seguro del reproductor (traído de JOX3 Verifier).
 *
 * Algunos TV Box se cierran solos al iniciar el decodificador de audio FFmpeg
 * (un fallo nativo que no deja rastro). Se marca "arrancando" antes de crear el
 * reproductor y se borra cuando algo llega a reproducirse: si al volver a abrir
 * la marca sigue ahí, el arranque anterior se cayó y FFmpeg se desactiva.
 */
object PlayerSafety {
    private const val PREFS = "jox3_player"
    private const val KEY_STARTING = "starting"
    private const val KEY_FFMPEG_OFF = "ffmpeg_off"
    private const val KEY_OFF_VERSION = "ffmpeg_off_version"

    private fun version(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    @JvmStatic
    fun markStarting(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_STARTING, false)) {
            sp.edit().putBoolean(KEY_FFMPEG_OFF, true).putString(KEY_OFF_VERSION, version(ctx)).commit()
        }
        sp.edit().putBoolean(KEY_STARTING, true).commit()
    }

    @JvmStatic
    fun startedOk(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_STARTING, false)) sp.edit().putBoolean(KEY_STARTING, false).apply()
    }

    /**
     * FFmpeg se reactiva solo cuando llega una versión nueva de la app: así un
     * arreglo se vuelve a probar sin que el usuario tenga que hacer nada.
     */
    @JvmStatic
    fun ffmpegAllowed(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_FFMPEG_OFF, false)) return true
        if (sp.getString(KEY_OFF_VERSION, null) != version(ctx)) { resetFfmpeg(ctx); return true }
        return false
    }

    @JvmStatic
    fun disableFfmpeg(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FFMPEG_OFF, true).putString(KEY_OFF_VERSION, version(ctx)).commit()
    }

    /** Volver a intentar con FFmpeg. */
    @JvmStatic
    fun resetFfmpeg(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FFMPEG_OFF, false).putBoolean(KEY_STARTING, false).apply()
    }
}
