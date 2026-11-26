package com.jox3.iptvexo.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.tv.data.Today
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.FocusTracker
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay

/** Aviso de "Avísame cuando juegue…" (TV): abajo a la derecha, con "Ver ahora" y "Después". */
@Composable
fun AlertBanner(a: Today.Pick, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val req = remember { FocusRequester() }
    fun close(watch: Boolean) {
        if (watch) onWatch() else { onDismiss(); runCatching { FocusTracker.restore() } }
    }
    BackHandler { close(false) }
    LaunchedEffect(a) {
        runCatching { FocusTracker.save() }
        delay(80); runCatching { req.requestFocus() }
        delay(45_000); close(false)   // si no se responde, se va solo
    }
    Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.width(440.dp).background(Color(0xF2101722), RoundedCornerShape(16.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(a.channel.logo, a.guide, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x33081626)).padding(5.dp),
                    scale = ContentScale.Fit)
                Column(Modifier.weight(1f)) {
                    Text("🔔 " + (if (a.liveNow) "Ya empezó" else "Empieza " + a.whenText().lowercase()) + " · " + a.guide,
                        style = Type.Small, color = Jx.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.title, style = Type.Label, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusSurface(onClick = { close(true) }, modifier = Modifier.focusRequester(req).height(44.dp).width(180.dp),
                    container = Jx.Accent, focusedContainer = Jx.Text, content = Jx.Bg, focusedContent = Jx.Bg) {
                    Text("▶ Ver ahora", style = Type.Label, modifier = Modifier.align(Alignment.Center))
                }
                FocusSurface(onClick = { close(false) }, modifier = Modifier.height(44.dp).width(140.dp), container = Jx.Surface) {
                    Text("Después", style = Type.Label, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
