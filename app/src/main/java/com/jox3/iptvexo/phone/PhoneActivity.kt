package com.jox3.iptvexo.phone

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.jox3.iptvexo.R
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

    // ── Ventana flotante (PiP) ──
    /** Lo que la ventana flotante controla (lo pone la interfaz). */
    interface PipControls {
        /** Hay algo reproduciéndose que se pueda ver en la ventanita. */
        val active: Boolean
        val playing: Boolean
        val live: Boolean
        fun playPause()
        fun next()
        fun prev()
    }
    var pipControls: PipControls? = null
    /** true mientras la app está en la ventana flotante: se muestra solo el video. */
    var inPip by mutableStateOf(false); private set
    private var pipReceiver: BroadcastReceiver? = null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(icon: Int, title: String, action: String, code: Int): RemoteAction? {
        val pi = PendingIntent.getBroadcast(this, code, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams? {
        val c = pipControls
        val actions = buildList {
            if (c?.live == true) pipAction(R.drawable.ic_prev, "Canal anterior", PIP_PREV, 1)?.let { add(it) }
            pipAction(if (c?.playing == true) R.drawable.ic_pause else R.drawable.ic_play,
                if (c?.playing == true) "Pausa" else "Reproducir", PIP_PLAY, 2)?.let { add(it) }
            if (c?.live == true) pipAction(R.drawable.ic_next, "Canal siguiente", PIP_NEXT, 3)?.let { add(it) }
        }
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(actions)
        // Android 12+: al salir con el botón de inicio pasa sola a la ventanita
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(c?.active == true).setSeamlessResizeEnabled(true)
        return b.build()
    }

    /** Botón PiP: el video sigue en una ventanita encima de las otras apps. */
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            android.widget.Toast.makeText(this, "Tu Android no tiene ventana flotante", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        if (pipControls?.active != true) return
        runCatching { enterPictureInPictureMode(pipParams()!!) }.onFailure {
            android.widget.Toast.makeText(this, "La ventana flotante está desactivada para JOX3 en los ajustes del celular",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** La app está en la ventana flotante (o entrando a ella). */
    fun isPipNow(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /** Actualiza los botones de la ventanita (pausa/reproducir) y la entrada automática. */
    fun updatePip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(pipParams()!!) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Antes de Android 12 no hay entrada automática: se entra aquí al pulsar inicio
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && pipControls?.active == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, i: Intent?) {
                    val c = pipControls ?: return
                    when (i?.action) {
                        PIP_PLAY -> c.playPause()
                        PIP_NEXT -> c.next()
                        PIP_PREV -> c.prev()
                    }
                    main.postDelayed({ updatePip() }, 300)
                }
            }
            ContextCompat.registerReceiver(this, r, IntentFilter().apply { addAction(PIP_PLAY); addAction(PIP_NEXT); addAction(PIP_PREV) },
                ContextCompat.RECEIVER_NOT_EXPORTED)
            pipReceiver = r
        } else {
            pipReceiver?.let { runCatching { unregisterReceiver(it) } }
            pipReceiver = null
        }
    }

    companion object {
        private const val PIP_PLAY = "com.jox3.iptvexo.PIP_PLAY"
        private const val PIP_NEXT = "com.jox3.iptvexo.PIP_NEXT"
        private const val PIP_PREV = "com.jox3.iptvexo.PIP_PREV"
    }

    /** Enlace de invitación a "Ver juntos" (jox3://together/123456): unirse a esa sala. */
    private fun takeTogetherLink(i: android.content.Intent?) {
        val d = i?.data ?: return
        // Notificación de un recordatorio: abrir ese canal
        if (d.scheme == "jox3" && d.host == "open") { com.jox3.iptvexo.tv.GoogleTv.pendingLink = d; return }
        if (d.scheme == "jox3" && d.host == "together") {
            d.lastPathSegment?.filter { it.isDigit() }?.takeIf { it.length == 6 }?.let {
                com.jox3.iptvexo.tv.player.WatchTogether.pendingJoin = it
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeTogetherLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.jox3.iptvexo.tv.data.Downloads.resumePending(this)

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

        takeTogetherLink(intent)
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
