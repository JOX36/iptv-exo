package com.jox3.iptvexo.tv.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jox3.iptvexo.tv.data.RemoteAddServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.focus.onFocusChanged
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
    var withCode by remember { mutableStateOf(false) }
    if (withCode) { VaultCodeLogin(tv = true, onLogin = onLogin, onCancel = { withCode = false }); return }
    var saved by remember { mutableStateOf(accounts.all()) }
    var deleting by remember { mutableStateOf<String?>(null) }   // cuenta esperando confirmación
    val last = saved.firstOrNull()
    var host by remember { mutableStateOf(last?.host.orEmpty()) }
    var user by remember { mutableStateOf(last?.user.orEmpty()) }
    var pass by remember { mutableStateOf(last?.pass.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val firstReq = remember { FocusRequester() }

    // ── Agregar desde el celular (QR) ──
    var qrUrl by remember { mutableStateOf<String?>(null) }   // null = preparando, "" = sin red local
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var received by remember { mutableStateOf<List<Account>>(emptyList()) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val srv = RemoteAddServer { accs -> main.post { received = accs } }
        val job = scope.launch(Dispatchers.IO) {
            val u = srv.start()
            val bmp = if (u.isEmpty()) null else runCatching { RemoteAddServer.qrBitmap(u).asImageBitmap() }.getOrNull()
            withContext(Dispatchers.Main) { qrUrl = u; qr = bmp }
        }
        onDispose { job.cancel(); srv.stop() }
    }

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

    // Llegó una cuenta desde el celular: llenar el formulario y entrar
    LaunchedEffect(received) {
        val a = received.firstOrNull() ?: return@LaunchedEffect
        received.drop(1).reversed().forEach { accounts.use(it) }   // las demás quedan guardadas
        host = a.host; user = a.user; pass = a.pass
        error = ""
        submit()
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
            Spacer(Modifier.height(10.dp))
            Text("¿Ya usas JOX3 en otro equipo? Entra con tu código y se cargan tus servidores.",
                style = Type.Small, color = Jx.TextSoft)
            Spacer(Modifier.height(6.dp))
            ActionButton("Tengo un código JOX3", { withCode = true }, Modifier.fillMaxWidth())
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(error, style = Type.Label, color = Jx.Live)
            }
            Spacer(Modifier.height(16.dp))
            Text("También puedes pegar en Servidor el enlace completo de tu lista (get.php?username=…).",
                style = Type.Small, color = Jx.Faint)
        }

        // ── Cuentas guardadas + agregar desde el celular ──
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            if (saved.isNotEmpty()) {
                Text("CUENTAS GUARDADAS", style = Type.Eyebrow, color = Jx.Muted)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(saved, key = { it.key }) { acc ->
                        val confirm = deleting == acc.key
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FocusSurface(
                                onClick = { onLogin(acc) },
                                modifier = Modifier.weight(1f).height(60.dp),
                                focusedScale = 1.02f,
                                container = Jx.Surface,
                            ) {
                                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
                                    Text(if (confirm) "¿Eliminar esta cuenta? OK en la papelera" else acc.label, style = Type.Label,
                                        color = if (confirm) Jx.Live else Jx.Text)
                                    Text(acc.user, style = Type.Small, color = Jx.Faint)
                                }
                            }
                            // Papelera: la primera pulsación pide confirmar, la segunda elimina
                            FocusSurface(
                                onClick = {
                                    if (confirm) { accounts.delete(acc); saved = accounts.all(); deleting = null }
                                    else deleting = acc.key
                                },
                                modifier = Modifier.size(60.dp).onFocusChanged { if (!it.isFocused && deleting == acc.key) deleting = null },
                                focusedScale = 1.04f,
                                container = if (confirm) Jx.Live else Jx.Surface,
                                focusedContainer = if (confirm) Jx.Live else Jx.SurfaceHi,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    com.jox3.iptvexo.tv.ui.JxIcon(R.drawable.ic_delete, 24.dp, description = "Eliminar cuenta")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            PhoneQrCard(qrUrl, qr)
        }
    }

    LaunchedEffect(Unit) { runCatching { firstReq.requestFocus() } }
}

/** Tarjeta con el QR para enviar la cuenta desde el celular (misma red WiFi). */
@Composable
private fun PhoneQrCard(url: String?, qr: ImageBitmap?) {
    Row(
        Modifier.fillMaxWidth().background(Jx.Surface, RoundedCornerShape(14.dp)).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(156.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(8.dp),
            contentAlignment = Alignment.Center) {
            if (qr != null) Image(qr, contentDescription = "Código QR", modifier = Modifier.fillMaxSize())
            else Text(if (url == null) "…" else "Sin red", style = Type.Label, color = Color.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Agregar desde el celular", style = Type.H2, color = Jx.Text)
            if (url == "") {
                Text("Conecta el TV a la red WiFi o por cable para usar esta opción.", style = Type.Small, color = Jx.Muted)
            } else {
                Text("1. Conecta el celular al mismo WiFi del TV", style = Type.Small, color = Jx.Muted)
                Text("2. Escanea este código con la cámara", style = Type.Small, color = Jx.Muted)
                Text("3. Pega el enlace de tu lista y pulsa Enviar", style = Type.Small, color = Jx.Muted)
                if (!url.isNullOrEmpty()) Text(url.substringBefore("/?"), style = Type.Small, color = Jx.Faint)
            }
        }
    }
}
