package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.AccountStore
import com.jox3.iptvexo.tv.data.XtreamApi
import com.jox3.iptvexo.tv.ui.ActionButton
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.TvTextField
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.launch

/** Inicio de sesión Xtream Codes + cuentas guardadas. */
@Composable
fun LoginScreen(accounts: AccountStore, onLogin: (Account) -> Unit) {
    val saved = remember { accounts.all() }
    val last = saved.firstOrNull()
    var host by remember { mutableStateOf(last?.host.orEmpty()) }
    var user by remember { mutableStateOf(last?.user.orEmpty()) }
    var pass by remember { mutableStateOf(last?.pass.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val firstReq = remember { FocusRequester() }

    fun submit() {
        if (busy) return
        val acc = XtreamApi.parseLogin(host, user, pass)
        if (acc == null) { error = "Completa servidor, usuario y contraseña"; return }
        busy = true; error = ""
        scope.launch {
            try {
                XtreamApi(acc).serverInfo()   // valida usuario y contraseña
                onLogin(acc)
            } catch (e: Exception) {
                error = e.message ?: "No se pudo conectar con el servidor"
            } finally { busy = false }
        }
    }

    Row(Modifier.fillMaxSize().background(Jx.Bg).padding(horizontal = 72.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp)) {

        // ── Formulario ──
        Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("JOX3 TV", style = Type.Hero, color = Jx.Accent)
            Text("Live · Películas · Series", style = Type.Body, color = Jx.Muted)
            Spacer(Modifier.height(28.dp))
            TvTextField("Servidor", host, { host = it }, Modifier.fillMaxWidth().focusRequester(firstReq),
                placeholder = "http://servidor:8080", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(14.dp))
            TvTextField("Usuario", user, { user = it }, Modifier.fillMaxWidth(), placeholder = "usuario")
            Spacer(Modifier.height(14.dp))
            TvTextField("Contraseña", pass, { pass = it }, Modifier.fillMaxWidth(), placeholder = "contraseña",
                password = true, keyboardType = KeyboardType.Password, onDone = ::submit)
            Spacer(Modifier.height(22.dp))
            ActionButton(if (busy) "Conectando…" else "Entrar", ::submit, Modifier.fillMaxWidth(),
                icon = R.drawable.tv_ic_play, primary = true)
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(error, style = Type.Label, color = Jx.Live)
            }
            Spacer(Modifier.height(16.dp))
            Text("También puedes pegar en Servidor el enlace completo de tu lista (get.php?username=…).",
                style = Type.Small, color = Jx.Faint)
        }

        // ── Cuentas guardadas ──
        if (saved.isNotEmpty()) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text("CUENTAS GUARDADAS", style = Type.Eyebrow, color = Jx.Muted)
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(saved, key = { it.key }) { acc ->
                        FocusSurface(
                            onClick = { onLogin(acc) },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            focusedScale = 1.02f,
                            container = Jx.Surface,
                        ) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
                                Text(acc.label, style = Type.Label)
                                Text(acc.user, style = Type.Small, color = Jx.Faint)
                            }
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f))
        }
    }

    LaunchedEffect(Unit) { runCatching { firstReq.requestFocus() } }
}
