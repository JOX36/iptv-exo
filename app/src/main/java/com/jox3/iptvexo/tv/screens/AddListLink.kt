package com.jox3.iptvexo.tv.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.jox3.iptvexo.tv.data.Profile
import com.jox3.iptvexo.tv.data.ProfileStore
import com.jox3.iptvexo.tv.data.Profiles
import com.jox3.iptvexo.tv.data.ServerHub
import com.jox3.iptvexo.tv.data.XtreamApi
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import kotlinx.coroutines.delay

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
 * de lo que haya. Al abrirse consulta el servidor (la misma validación del inicio de sesión) y
 * muestra solo lo útil: nombre, días de vigencia, conexiones y velocidad de respuesta. Si aún
 * no hay perfil abierto, pregunta en cuál cargar la cuenta. Con [onOpen] se guarda y se abre.
 */
@Composable
fun AddListPrompt(tv: Boolean, accounts: AccountStore, onOpen: (Account, isNew: Boolean, profile: Profile?) -> Unit) {
    val url = AddListLink.pending ?: return
    val ctx = LocalContext.current
    val acc = remember(url) { XtreamApi.parseLogin(url, "", "") }
    val existing = remember(url) { acc?.let { a -> accounts.all().firstOrNull { it.key == a.key } } }
    var status by remember(url) { mutableStateOf<ServerHub.Status?>(null) }
    // Consultar el servidor apenas se abre (no se guarda nada todavía)
    LaunchedEffect(url) {
        val a = acc ?: return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        status = try {
            val info = XtreamApi(a).serverInfo()
            ServerHub.Status(true, System.currentTimeMillis() - t0, info)
        } catch (e: Exception) {
            ServerHub.Status(false, System.currentTimeMillis() - t0, null, e.message ?: "No responde")
        }
    }
    // Sin perfil abierto: elegir en cuál se carga
    val askProfile = Profiles.current == null
    val profiles = remember(url, askProfile) {
        if (!askProfile || acc == null) emptyList() else runCatching { ProfileStore(ctx.applicationContext, acc).list.toList() }.getOrDefault(emptyList())
    }
    var chosen by remember(url) { mutableStateOf(profiles.firstOrNull()) }
    val okReq = remember { FocusRequester() }
    LaunchedEffect(url) { if (tv) { delay(150); runCatching { okReq.requestFocus() } } }

    fun cancel() { AddListLink.pending = null }
    val st = status
    val ready = acc != null && st?.ok == true

    Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 480.dp).clip(RoundedCornerShape(18.dp)).background(Jx.Rail).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("¿Agregar esta lista?", 21, Jx.Text, bold = true)
            T(AddListLink.serverLabel(url).ifBlank { "Servidor desconocido" }, 17, Jx.Accent, bold = true)
            when {
                acc == null -> T("El enlace no trae usuario y contraseña.", 14, Jx.Live)
                st == null -> T("Consultando el servidor…", 14, Jx.TextSoft)
                !st.ok -> T("No responde: " + st.error, 14, Jx.Live)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val days = st.daysLeft
                    Stat("Vence", when { days == null -> "Sin fecha"; days == 0L -> "Hoy"; else -> "$days días" },
                        if (days != null && days <= 5) Jx.Gold else Jx.Text, Modifier.weight(1f))
                    Stat("Conexiones", st.info?.let { "${it.activeCons} / ${it.maxCons}" } ?: "—", Jx.Text, Modifier.weight(1f))
                    Stat("Respuesta", "${st.pingMs} ms", if (st.pingMs > 1500) Jx.Gold else Jx.Text, Modifier.weight(1f))
                }
            }
            if (askProfile && profiles.size > 1 && ready) {
                T("¿En qué perfil la cargo?", 14, Jx.TextSoft, bold = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    profiles.forEach { p ->
                        Btn(tv, "${p.emoji} ${p.name}", primary = false, selected = chosen?.id == p.id) { chosen = p }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                Btn(tv, if (existing != null) "Abrir" else "Agregar", primary = ready, modifier = Modifier.focusRequester(okReq)) {
                    if (!ready) return@Btn
                    AddListLink.pending = null
                    onOpen(existing?.copy(pass = acc!!.pass) ?: acc!!, existing == null, if (askProfile) chosen else null)
                }
                Btn(tv, "Cancelar", primary = false) { cancel() }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Jx.Surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
        T(label, 12, Jx.Muted)
        T(value, 16, color, bold = true)
    }
}

@Composable
private fun Btn(tv: Boolean, label: String, primary: Boolean, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { focused -> Jx.Text; primary -> Jx.Accent; selected -> Jx.SurfaceHi; else -> Jx.Surface }
    val fg = when { focused || primary -> Jx.Bg; selected -> Jx.Accent; else -> Jx.TextSoft }
    val inner: @Composable () -> Unit = {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (focused) Modifier.border(1.dp, Jx.Text, RoundedCornerShape(12.dp))
                else if (selected) Modifier.border(1.5.dp, Jx.Accent, RoundedCornerShape(12.dp)) else Modifier)
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
