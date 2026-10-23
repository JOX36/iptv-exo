package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.APP_VERSION
import com.jox3.tvdesktop.core.Shutdown
import com.jox3.tvdesktop.core.Updater
import kotlinx.coroutines.launch

/** Inicia la actualización: descarga, verifica, cierra guardando todo, instala y vuelve a abrir. */
fun startUpdate(scope: kotlinx.coroutines.CoroutineScope, info: Updater.Info) {
    scope.launch { Updater.downloadAndInstall(info) { Shutdown.exit?.invoke() } }
}

/** Aviso de versión nueva (y su progreso), abajo a la izquierda de la ventana. */
@Composable
fun UpdateBanner(modifier: Modifier) {
    val st = Updater.state
    val scope = rememberCoroutineScope()
    var showNotes by remember { mutableStateOf(false) }
    val info = when (st) {
        is Updater.State.Available -> st.info
        is Updater.State.Downloading -> st.info
        is Updater.State.Installing -> st.info
        is Updater.State.Error -> st.info
        else -> null
    } ?: return
    if (st is Updater.State.Available && Updater.dismissed) return

    Column(
        modifier.padding(20.dp).width(380.dp)
            .background(Jx.Surface, RoundedCornerShape(16.dp)).border(1.dp, Jx.Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Text("Nueva versión ${info.version}", color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("Tienes la $APP_VERSION", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
        if (showNotes && info.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                Text(info.notes, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        when (st) {
            is Updater.State.Downloading -> {
                Text("Descargando… ${st.percent} %", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                ProgressLine(st.percent / 100f, Modifier.fillMaxWidth(), height = 4.dp)
            }
            is Updater.State.Installing ->
                Text("Instalando… el programa se cerrará y se abrirá solo en unos segundos.", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
            is Updater.State.Error -> {
                Text(st.message, color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Reintentar") { startUpdate(scope, info) }
                    GhostButton("Más tarde") { Updater.reset() }
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Actualizar ahora") { startUpdate(scope, info) }
                if (info.notes.isNotBlank()) GhostButton(if (showNotes) "Ocultar" else "Novedades") { showNotes = !showNotes }
                GhostButton("Más tarde") { Updater.dismissed = true }
            }
        }
    }
}
