package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Reminders
import kotlinx.coroutines.delay

/** "⏰ Ya empieza…": aviso de un recordatorio. "Ver ahora" lleva al canal; se va solo al minuto. */
@Composable
fun ReminderBanner(r: Reminders.Reminder, modifier: Modifier, onWatch: () -> Unit, onClose: () -> Unit) {
    LaunchedEffect(r.key) { delay(60_000); onClose() }
    val fmt = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.forLanguageTag("es")) }
    Row(
        modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0111923)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteImage(r.logo, r.chName, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), scale = ContentScale.Fit)
        Column(Modifier.weight(1f)) {
            Text("⏰ YA EMPIEZA · ${fmt.format(java.util.Date(r.start))}", color = Jx.Accent, fontFamily = Exo2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(r.title, color = Jx.Text, fontFamily = Exo2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.chName, color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp, maxLines = 1)
        }
        PillButton("▶ Ver ahora") { onWatch() }
        GhostButton("Cerrar") { onClose() }
    }
}
