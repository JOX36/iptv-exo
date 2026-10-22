package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.jox3.tvdesktop.data.Account
import com.jox3.tvdesktop.data.ServerInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inicio tras entrar. Por ahora muestra las secciones (llegan en las etapas 2 y 3)
 * y el estado de la cuenta.
 */
@Composable
fun HomeScreen(
    account: Account,
    info: ServerInfo,
    version: String,
    onLogout: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Jx.Bg)) {
        // ── Barra superior ──
        Row(
            Modifier.fillMaxWidth().background(Jx.Rail).padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Brand(iconSize = 34.dp, textSize = 22)
            Spacer(Modifier.width(36.dp))
            listOf("En vivo", "Películas", "Series").forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 22.dp)) {
                    Text(name, color = Jx.Faint, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(6.dp))
                    Pill("Pronto", Jx.Faint)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(account.label, color = Jx.Muted, style = MaterialTheme.typography.titleSmall)
        }

        // ── Contenido ──
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth()
                    .background(Jx.Surface, RoundedCornerShape(18.dp))
                    .border(1.dp, Jx.Line, RoundedCornerShape(18.dp))
                    .padding(28.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tu cuenta", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    val active = info.status.equals("Active", ignoreCase = true)
                    Pill(if (active) "ACTIVA" else info.status.uppercase().ifBlank { "SIN ESTADO" }, if (active) Jx.Ok else Jx.Gold)
                }
                Spacer(Modifier.height(18.dp))
                InfoRow("Servidor", account.host)
                InfoRow("Usuario", account.user)
                InfoRow("Vence", info.expires?.let { fmtDate(it) } ?: "Sin fecha de vencimiento")
                InfoRow("Conexiones", "${info.activeCons} en uso de ${info.maxCons}")
                Spacer(Modifier.height(22.dp))
                Text(
                    "Las secciones En vivo, Películas y Series llegan en las próximas versiones.",
                    color = Jx.Muted,
                )
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // La cuenta queda guardada: se puede volver a entrar con un clic.
                    OutlinedButton(
                        onClick = onLogout,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Jx.Accent),
                    ) { Text("Cambiar de cuenta") }
                }
            }
            Text(
                "Versión $version",
                color = Jx.Faint,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Jx.Muted, modifier = Modifier.width(120.dp))
        Text(value, color = Jx.TextSoft)
    }
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es")).format(Date(ms))
