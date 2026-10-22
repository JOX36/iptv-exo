package com.jox3.tvdesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jox3.tvdesktop.core.Crash
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SelfTest
import com.jox3.tvdesktop.core.SingleInstance
import com.jox3.tvdesktop.core.WindowPrefs
import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.AccountStore
import com.jox3.tvdesktop.data.ServerInfo
import com.jox3.tvdesktop.data.XtreamApi
import com.jox3.tvdesktop.ui.Brand
import com.jox3.tvdesktop.ui.HomeScreen
import com.jox3.tvdesktop.ui.Jx
import com.jox3.tvdesktop.ui.JxTheme
import com.jox3.tvdesktop.ui.LoginScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Frame
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/** Versión del programa (la pone el instalador; "dev" al compilar a mano). */
val APP_VERSION: String = System.getProperty("jox3.version") ?: "dev"

/** Avisos de "muéstrate" que manda una segunda copia del programa. */
private val showRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // Autoprueba del programa empaquetado (la usa CI antes de publicar): sin ventana.
    val st = args.indexOf("--self-test")
    if (st >= 0) exitProcess(SelfTest.run(java.io.File(args.getOrNull(st + 1) ?: "selftest.txt")))

    Crash.install()
    Log.i("=== JOX3 TV $APP_VERSION | Java ${System.getProperty("java.version")} | " +
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")} ===")

    when (SingleInstance.acquire { showRequests.tryEmit(Unit) }) {
        SingleInstance.Result.PRIMARY -> Unit
        SingleInstance.Result.SHOWN_EXISTING -> exitProcess(0)
        SingleInstance.Result.ALREADY_RUNNING -> {
            JOptionPane.showMessageDialog(null, "JOX3 TV ya está abierto.", "JOX3 TV", JOptionPane.INFORMATION_MESSAGE)
            exitProcess(0)
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { SingleInstance.release() })

    val prefs = WindowPrefs.load()

    application {
        val pos = prefs.visiblePosition()
        val state = rememberWindowState(
            placement = if (prefs.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = pos?.let { WindowPosition(it.first.dp, it.second.dp) } ?: WindowPosition(Alignment.Center),
            size = DpSize(prefs.width.dp, prefs.height.dp),
        )
        // Último tamaño/posición "normal" (no maximizada), para restaurarlo bien al volver a abrir.
        var floating by remember { mutableStateOf(prefs) }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.placement, state.size, state.position) }.collect { (pl, size, p) ->
                if (pl == WindowPlacement.Floating && p.isSpecified) {
                    floating = WindowPrefs(p.x.value.toInt(), p.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt())
                }
            }
        }

        fun close() {
            WindowPrefs.save(floating.copy(maximized = state.placement == WindowPlacement.Maximized))
            Log.i("Programa cerrado por el usuario")
            exitApplication()
        }

        // Si algo falla dentro de la ventana: se registra, se avisa y se cierra ordenadamente.
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory { _ ->
                WindowExceptionHandler { e ->
                    Log.e("Fallo en la ventana", e)
                    Crash.showDialog(e, fatal = true)
                    SingleInstance.release()
                    exitProcess(1)
                }
            }
        ) {
            Window(
                onCloseRequest = { close() },
                state = state,
                title = "JOX3 TV",
                icon = painterResource("icon.png"),
            ) {
                LaunchedEffect(Unit) {
                    window.minimumSize = Dimension(960, 600)
                    // Otra copia pidió mostrarse: traer esta ventana al frente.
                    showRequests.collect {
                        state.isMinimized = false
                        window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                        window.isAlwaysOnTop = true; window.toFront(); window.requestFocus(); window.isAlwaysOnTop = false
                    }
                }
                JxTheme { App() }
            }
        }
    }
}

/** Qué se muestra: conectando al abrir, inicio de sesión o el inicio de la cuenta. */
private sealed interface Screen {
    data object Starting : Screen
    data object Login : Screen
    data class Home(val account: Account, val info: ServerInfo) : Screen
}

@Composable
private fun App() {
    val store = remember { AccountStore() }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Starting) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prefill by remember { mutableStateOf<Account?>(null) }

    /** Comprueba la cuenta con el servidor; si responde bien, la guarda y entra. */
    suspend fun connect(acc: Account) {
        if (busy) return
        busy = true; error = null
        try {
            val info = XtreamApi(acc).serverInfo()
            store.use(acc)
            Log.i("Sesión iniciada en ${acc.label} (${info.status})")
            screen = Screen.Home(acc, info)
        } catch (e: Exception) {
            Log.w("No se pudo iniciar sesión en ${acc.label}: ${e.message}")
            error = e.message ?: "No se pudo conectar"
            prefill = acc
            screen = Screen.Login
        } finally {
            busy = false
        }
    }

    // Al abrir: si había una cuenta activa, entra sola (como en Android).
    LaunchedEffect(Unit) {
        val last = store.current()
        if (last != null && last.pass.isNotEmpty()) connect(last) else { prefill = last; screen = Screen.Login }
    }

    when (val s = screen) {
        Screen.Starting -> Splash()
        Screen.Login -> LoginScreen(
            saved = store.accounts,
            busy = busy,
            error = error,
            prefill = prefill,
            onLogin = { h, u, p ->
                val acc = XtreamApi.parseLogin(h, u, p)
                if (acc == null) error = "Completa servidor, usuario y contraseña"
                else scope.launch { connect(acc) }
            },
            onPickSaved = { acc ->
                if (acc.pass.isEmpty()) {
                    // Contraseña que no se pudo descifrar (por ejemplo, datos copiados de otro PC).
                    prefill = acc; error = "Escribe de nuevo la contraseña de esta cuenta"
                } else scope.launch { connect(acc) }
            },
            onDeleteSaved = { acc ->
                store.remove(acc)
                if (prefill?.key == acc.key) prefill = null
            },
        )
        is Screen.Home -> HomeScreen(
            account = s.account,
            info = s.info,
            version = APP_VERSION,
            onLogout = {
                store.logout()
                prefill = null; error = null
                screen = Screen.Login
            },
            onOpenLogs = { Crash.openLogs() },
        )
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(Jx.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Brand(iconSize = 64.dp, textSize = 38)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Jx.Accent)
            Spacer(Modifier.height(14.dp))
            Text("Conectando…", color = Jx.Muted)
        }
    }
}
