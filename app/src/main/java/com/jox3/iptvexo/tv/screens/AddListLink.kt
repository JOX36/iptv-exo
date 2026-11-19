package com.jox3.iptvexo.tv.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.AccountStore
import com.jox3.iptvexo.tv.data.XtreamApi
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Enlace para agregar una lista desde otra app (JOX3 Radar): `jox3://add?url=<URL codificada>`.
 * La url es lo mismo que acepta [XtreamApi.parseLogin]: `…/get.php?username=…&password=…`
 * o `servidor/usuario/clave`.
 *
 * Nunca se agrega sola: siempre aparece "¿Agregar esta lista?" con el servidor y hay que
 * aceptar (si no, cualquier enlace de internet podría meter una cuenta sin que se note).
 * Usuario y clave no se muestran ni se escriben en el registro.
 */
object AddListLink {
    /** Enlace recibido, esperando confirmación (lo muestra [AddListPrompt]). */
    var pending by mutableStateOf<String?>(null)

    private const val MAX_LEN = 500

    /** Si el intent trae `jox3://add?url=…`, lo deja pendiente y devuelve true. */
    fun take(data: Uri?): Boolean {
        val d = data ?: return false
        if (d.scheme != "jox3" || d.host != "add") return false
        val url = runCatching { d.getQueryParameter("url") }.getOrNull()?.trim().orEmpty()
        val ok = url.length in 8..MAX_LEN &&
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) &&
            runCatching { Uri.parse(url).host }.getOrNull()?.isNotBlank() == true
        if (ok) pending = url
        return true   // era un enlace "add" (aunque venga mal): no pasarlo a otros manejadores
    }

    /** Lo que se muestra: solo el servidor (dominio y puerto), nunca usuario ni clave. */
    fun serverLabel(url: String): String = runCatching {
        val u = Uri.parse(url)
        (u.host.orEmpty() + if (u.port > 0) ":${u.port}" else "").take(80)
    }.getOrDefault("")
}

/**
 * Confirmación de [AddListLink]. Se pone en la raíz de la app (TV y celular): aparece encima
 * de lo que haya (inicio de sesión, perfiles o la app). Al aceptar valida la cuenta igual que el
 * inicio de sesión normal; si responde, la guarda (sin duplicar) y la abre con [onOpen].
 */
@Composable
fun AddListPrompt(tv: Boolean, accounts: AccountStore, onOpen: (Account, isNew: Boolean) -> Unit) {
    val url = AddListLink.pending ?: return
    val server = remember(url) { AddListLink.serverLabel(url) }
    var busy by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val okReq = remember { FocusRequester() }
    LaunchedEffect(url) { if (tv) { delay(150); runCatching { okReq.requestFocus() } } }

    fun cancel() { if (!busy) AddListLink.pending = null }
    fun accept() {
        if (busy) return
        val acc = XtreamApi.parseLogin(url, "", "")
        if (acc == null) { error = "El enlace no trae usuario y contraseña de la lista."; return }
        busy = true; error = ""
        scope.launch {
            try {
                XtreamApi(acc).serverInfo()   // la misma validación del inicio de sesión
                val existing = accounts.all().firstOrNull { it.key == acc.key }
                AddListLink.pending = null
                onOpen(existing?.copy(pass = acc.pass) ?: acc, existing == null)
            } catch (e: Exception) {
                // Sin guardar nada. El mensaje del servidor no lleva usuario ni clave.
                error = "No se pudo agregar: " + (e.message ?: "el servidor no respondió")
            } finally { busy = false }
        }
    }

    Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 460.dp).clip(RoundedCornerShape(18.dp)).background(Jx.Rail).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("¿Agregar esta lista?", 20, Jx.Text, bold = true)
            T("Servidor: ${server.ifBlank { "desconocido" }}", 15, Jx.Accent, bold = true)
            T("La envió otra app (por ejemplo JOX3 Radar). Antes de guardarla se comprueba que la cuenta funcione.", 12, Jx.Muted)
            if (busy) T("Comprobando la cuenta…", 13, Jx.TextSoft)
            if (error.isNotBlank()) T(error, 13, Jx.Live)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Btn(tv, if (busy) "Comprobando…" else "Agregar", primary = true, modifier = Modifier.focusRequester(okReq)) { accept() }
                Btn(tv, "Cancelar", primary = false) { cancel() }
            }
        }
    }
}

@Composable
private fun Btn(tv: Boolean, label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { focused -> Jx.Text; primary -> Jx.Accent; else -> Jx.SurfaceHi }
    val fg = if (focused || primary) Jx.Bg else Jx.TextSoft
    val inner: @Composable () -> Unit = {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (focused) Modifier.border(1.dp, Jx.Text, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 18.dp, vertical = 11.dp)) {
            BasicText(label, style = TextStyle(color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
    }
    if (tv) FocusSurface(onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(12.dp),
        focusedScale = 1.04f, container = Color.Transparent, focusedContainer = Color.Transparent, ring = false) { inner() }
    else Box(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) { inner() }
}

@Composable
private fun T(text: String, size: Int, color: Color, bold: Boolean = false) {
    BasicText(text, style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}
