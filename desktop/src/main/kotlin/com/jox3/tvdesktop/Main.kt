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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.AccountStore
import com.jox3.tvdesktop.data.ServerInfo
import com.jox3.tvdesktop.data.XtreamApi
import com.jox3.tvdesktop.ui.Brand
import com.jox3.tvdesktop.ui.HomeScreen
import com.jox3.tvdesktop.ui.Jx
import com.jox3.tvdesktop.ui.JxTheme
import com.jox3.tvdesktop.ui.LoginScreen
import kotlinx.coroutines.launch
import java.awt.Dimension

/** Versión del programa (la pone el instalador; "dev" al compilar a mano). */
val APP_VERSION: String = System.getProperty("jox3.version") ?: "dev"

fun main() = application {
    val state = rememberWindowState(size = DpSize(1280.dp, 780.dp), position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "JOX3 TV",
        icon = painterResource("icon.png"),
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(960, 600) }
        JxTheme { App() }
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
        busy = true; error = null
        try {
            val info = XtreamApi(acc).serverInfo()
            store.use(acc)
            screen = Screen.Home(acc, info)
        } catch (e: Exception) {
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
        if (last != null) connect(last) else screen = Screen.Login
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
            onPickSaved = { acc -> scope.launch { connect(acc) } },
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
