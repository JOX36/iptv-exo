package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Account

/**
 * Inicio de sesión Xtream: formulario a la izquierda y cuentas guardadas a la derecha.
 * [busy] muestra el círculo de carga; [error] el mensaje del último intento.
 */
@Composable
fun LoginScreen(
    saved: List<Account>,
    busy: Boolean,
    error: String?,
    prefill: Account?,
    onLogin: (host: String, user: String, pass: String) -> Unit,
    onPickSaved: (Account) -> Unit,
    onDeleteSaved: (Account) -> Unit,
) {
    var host by remember(prefill) { mutableStateOf(prefill?.host.orEmpty()) }
    var user by remember(prefill) { mutableStateOf(prefill?.user.orEmpty()) }
    var pass by remember(prefill) { mutableStateOf(prefill?.pass.orEmpty()) }
    var showPass by remember { mutableStateOf(false) }
    val firstField = remember { FocusRequester() }

    fun submit() { if (!busy) onLogin(host, user, pass) }

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Jx.Accent.copy(alpha = 0.10f), Jx.Bg), radius = 1400f)
        )
    ) {
        Row(
            Modifier.fillMaxSize().padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Formulario ──
            Column(Modifier.widthIn(max = 440.dp).weight(1f, fill = false)) {
                Brand(iconSize = 56.dp, textSize = 34)
                Spacer(Modifier.height(10.dp))
                Text("Inicia sesión con tu cuenta Xtream", color = Jx.Muted)
                Spacer(Modifier.height(28.dp))

                val enterSubmits = Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { submit(); true } else false
                }

                JxField("Servidor", host, { host = it }, "http://servidor.com:8080",
                    Modifier.focusRequester(firstField).then(enterSubmits))
                Spacer(Modifier.height(14.dp))
                JxField("Usuario", user, { user = it }, null, enterSubmits)
                Spacer(Modifier.height(14.dp))
                JxField(
                    "Contraseña", pass, { pass = it }, null, enterSubmits,
                    visual = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        TextButton(onClick = { showPass = !showPass }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Text(if (showPass) "Ocultar" else "Ver", color = Jx.Accent)
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "También puedes pegar en Servidor un enlace completo (get.php?username=…).",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )

                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(error, color = Jx.Live)
                }

                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = { submit() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Jx.Accent, contentColor = Jx.OnAccent),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Jx.OnAccent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Conectando…")
                    } else Text("Entrar")
                }
            }

            // ── Cuentas guardadas ──
            if (saved.isNotEmpty()) {
                Column(
                    Modifier.widthIn(min = 280.dp, max = 360.dp).fillMaxHeight(0.7f)
                        .background(Jx.Rail, RoundedCornerShape(16.dp))
                        .border(1.dp, Jx.Line, RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Text("Cuentas guardadas", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Haz clic para entrar", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(saved, key = { it.key }) { acc ->
                            SavedAccountRow(acc, enabled = !busy, onClick = { onPickSaved(acc) }, onDelete = { onDeleteSaved(acc) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAccountRow(acc: Account, enabled: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .hoverSurface()
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(acc.label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(acc.user, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Text("✕", color = Jx.Faint, fontSize = 16.sp) // quitar cuenta
        }
    }
}

@Composable
private fun JxField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String?,
    modifier: Modifier = Modifier,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Jx.Faint) } },
        singleLine = true,
        visualTransformation = visual,
        trailingIcon = trailing,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Jx.Accent,
            unfocusedBorderColor = Jx.Line,
            focusedLabelColor = Jx.Accent,
            unfocusedLabelColor = Jx.Muted,
            cursorColor = Jx.Accent,
            focusedContainerColor = Jx.Surface,
            unfocusedContainerColor = Jx.Surface,
        ),
    )
}
