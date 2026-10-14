package com.jox3.iptvexo.tv

import android.content.Intent
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
import com.jox3.iptvexo.MainActivity
import com.jox3.iptvexo.NetClient
import com.jox3.iptvexo.Updater
import com.jox3.iptvexo.tv.ui.JoxTheme

/**
 * Interfaz nativa para Android TV / TV Box (Jetpack Compose for TV).
 * El celular sigue usando la interfaz web de siempre; el reproductor es el mismo.
 */
class TvActivity : ComponentActivity(), TvHost {

    override val player = PlayerLauncher(this)
    private lateinit var updater: Updater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        // Imágenes (logos, pósters, fondos): mismo cliente HTTP que la app y caché
        // en disco, así las carátulas no se vuelven a descargar en cada visita.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(NetClient.api())
                .crossfade(180)
                .respectCacheHeaders(false)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
                .build()
        )

        updater = Updater(this)
        updater.checkOnStartup()

        setContent { JoxTheme { TvApp(this) } }
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
        player.repo?.library?.refreshProgress()
    }

    override fun checkUpdates() = updater.checkNow()
    override fun appVersion(): String = updater.installedVersion()

    /** Vuelve a la interfaz anterior (web) y la recuerda como preferida. */
    override fun openClassicUi() {
        getSharedPreferences(MainActivity.PREFS_UI, MODE_PRIVATE).edit()
            .putBoolean(MainActivity.KEY_CLASSIC_TV, true).apply()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
