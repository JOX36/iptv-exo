package com.jox3.iptvexo.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.iptvexo.tv.data.Reminders
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import kotlinx.coroutines.delay

/**
 * "⏰ Ya empieza…": aviso de un recordatorio con la app abierta. OK / tocar = ver ahora;
 * Atrás o "Cerrar" lo quita. Se va solo al minuto.
 */
@Composable
fun ReminderBanner(tv: Boolean, r: Reminders.Reminder, onWatch: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val req = remember { FocusRequester() }
    androidx.activity.compose.BackHandler(onBack = onClose)
    LaunchedEffect(r.key) { if (tv) { delay(150); runCatching { req.requestFocus() } }; delay(60_000); onClose() }
    val fmt = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale("es")) }
    Row(
        modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0111923)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteImage(r.logo, r.chName, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), scale = ContentScale.Fit)
        Column(Modifier.weight(1f)) {
            BasicText("⏰ YA EMPIEZA · ${fmt.format(java.util.Date(r.start))}", style = TextStyle(color = Jx.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            BasicText(r.title, maxLines = 1, style = TextStyle(color = Jx.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            BasicText(r.chName, maxLines = 1, style = TextStyle(color = Jx.Muted, fontSize = 12.sp))
        }
        if (tv) {
            FocusSurface(onClick = onWatch, modifier = Modifier.focusRequester(req), shape = RoundedCornerShape(12.dp),
                container = Jx.Accent, focusedContainer = Jx.Text, content = Jx.OnAccent, focusedContent = Jx.Bg, ring = false) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    BasicText("▶ Ver ahora", style = TextStyle(color = Jx.Bg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                }
            }
            BasicText("Atrás: cerrar", style = TextStyle(color = Jx.Faint, fontSize = 11.sp))
        } else {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Jx.Accent).clickable(onClick = onWatch).padding(horizontal = 14.dp, vertical = 10.dp)) {
                BasicText("▶ Ver", style = TextStyle(color = Jx.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            }
            Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose).padding(8.dp)) {
                BasicText("✕", style = TextStyle(color = Jx.Muted, fontSize = 16.sp))
            }
        }
    }
}
