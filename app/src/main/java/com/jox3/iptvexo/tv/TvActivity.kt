package com.jox3.iptvexo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.jox3.iptvexo.App
import com.jox3.iptvexo.CrashReporter
import com.jox3.iptvexo.PlayerSafety
import com.jox3.iptvexo.NetClient
import com.jox3.iptvexo.Updater
import com.jox3.iptvexo.tv.ui.JoxTheme

/**
 * Interfaz nativa para Android TV / TV Box (Jetpack Compose for TV).
 * El celular sigue usando la interfaz web de siempre; el reproductor es el mismo.
 */
class TvActivity : ComponentActivity(), TvHost {

    override val player = PlayerLauncher()
    private lateinit var updater: Updater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.jox3.iptvexo.tv.data.Downloads.resumePending(this)
        hideSystemBars()

        // Imágenes (logos, pósters, fondos): mismo cliente HTTP que la app y caché
        // en disco, así las carátulas no se vuelven a descargar en cada visita.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(NetClient.api())
                .crossfade(180)
                .respectCacheHeaders(false)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
                .build()
        )

        updater = Updater(this)
        updater.checkOnStartup()

        GoogleTv.pendingLink = intent?.data
        setContent { JoxTheme { TvApp(this) } }
        showLastCrash()
    }

    /** Ya abierta: un toque en "Seguir viendo" o en el canal JOX3 del inicio de la TV. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { GoogleTv.pendingLink = it }
    }

    /** Si la app se cerró por un error, mostrar qué pasó (una sola vez). */
    private fun showLastCrash() {
        val crash = App.takeLastCrash(this)
        if (crash.isEmpty()) return
        val ffmpegOff = !PlayerSafety.ffmpegAllowed(this)
        val msg = (if (ffmpegOff) "El audio avanzado (FFmpeg) se desactivó para evitar que vuelva a pasar. " +
            "Se volverá a activar solo con la próxima actualización.\n\n" else "") + "Detalle técnico:\n" + crash.take(1500)
        android.app.AlertDialog.Builder(this)
            .setTitle("La app se cerró la última vez")
            .setMessage(msg)
            .setPositiveButton("Entendido", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
        player.repo?.library?.refreshProgress()
    }

    /**
     * Red de seguridad del foco: si Compose intenta mover el foco a un elemento que en ese
     * instante no está en pantalla, lanza "FocusRequester is not initialized" y la app se
     * cierra. Aquí se ignora esa pulsación (la siguiente funciona normal) en vez de cerrar.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = try {
        super.dispatchKeyEvent(event)
    } catch (e: IllegalStateException) {
        if (e.message?.contains("FocusRequester is not initialized") == true) {
            android.util.Log.w("JOX3", "Foco perdido, se ignora la tecla", e)
            CrashReporter.report(this, e, fatal = false)
            true
        } else throw e
    }

    override fun checkUpdates() = updater.checkNow()
    override fun appVersion(): String = updater.installedVersion()

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
