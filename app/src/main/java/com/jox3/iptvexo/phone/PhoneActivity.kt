package com.jox3.iptvexo.phone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.jox3.iptvexo.App
import com.jox3.iptvexo.MainActivity
import com.jox3.iptvexo.NetClient
import com.jox3.iptvexo.PlayerSafety
import com.jox3.iptvexo.Updater

/**
 * Interfaz nativa del celular (Jetpack Compose), hermana de la de TV: comparte
 * cuentas, catálogo, guía, búsqueda y reproductores. Gira sin recrearse
 * (configChanges en el manifiesto): el video sigue sin cortes al pasar a horizontal.
 */
class PhoneActivity : ComponentActivity() {

    private lateinit var updater: Updater
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(NetClient.api())
                .crossfade(160)
                .respectCacheHeaders(false)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
                .build()
        )

        updater = Updater(this)
        updater.checkOnStartup()

        setContent { PhoneTheme { PhoneApp(this) } }
        showLastCrash()
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    fun checkUpdates() = updater.checkNow()
    fun appVersion(): String = updater.installedVersion()

    /** Pantalla completa: oculta las barras del sistema (se muestran con un deslizamiento). */
    fun setFullscreenMode(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    /** Botón de pantalla completa: gira a horizontal. */
    fun enterLandscape() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Atrás en pantalla completa: vuelve a vertical y luego deja girar libre otra vez. */
    fun exitLandscape() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        main.postDelayed({ requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }, 1500)
    }

    /** Volver a la interfaz anterior (web) y recordarla. */
    fun openClassicUi() {
        getSharedPreferences(MainActivity.PREFS_UI, MODE_PRIVATE).edit()
            .putBoolean(MainActivity.KEY_CLASSIC_PHONE, true).apply()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

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
}
