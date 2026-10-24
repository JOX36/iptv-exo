package com.jox3.iptvexo.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.ActionButton
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.PosterCard
import com.jox3.iptvexo.tv.ui.RemoteImage
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay

/**
 * "Porque viste X, te preparé estos títulos": sale al terminar una película o una serie.
 * OK abre el título; Atrás o "Volver al inicio" cierra. Si nadie toca nada, se cierra sola.
 */
@Composable
fun BecauseScreen(repo: TvRepo, source: SavedItem, onOpen: (SavedItem) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val recs by produceState<List<SavedItem>?>(null, source.id) {
        value = runCatching { repo.becauseYouWatched(ctx, source) }.getOrDefault(emptyList())
    }
    // Se guarda para la fila del Inicio
    LaunchedEffect(recs) { recs?.let { repo.library.setBecause(source.name, it) } }

    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var left by remember { mutableIntStateOf(30) }
    LaunchedEffect(lastKey, recs) {
        val r = recs ?: return@LaunchedEffect
        val total = if (r.isEmpty()) 4 else 30
        for (s in total downTo 1) { left = s; delay(1000) }
        onClose()
    }
    BackHandler(onBack = onClose)
    val firstReq = remember { FocusRequester() }
    LaunchedEffect(recs) { delay(120); runCatching { firstReq.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Jx.Bg)
            .onPreviewKeyEvent { lastKey = System.currentTimeMillis(); false }
            .focusGroup()
    ) {
        // Fondo: la carátula de lo que se terminó, muy tenue
        RemoteImage(source.image, source.name, Modifier.fillMaxSize().alpha(0.18f), scale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99070A0F), Jx.Bg))))

        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Text("TERMINASTE", style = Type.Eyebrow, color = Jx.Accent)
            Spacer(Modifier.height(6.dp))
            Text("Porque viste ${source.name}", style = Type.Hero, color = Jx.Text, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            val r = recs
            Text(
                when {
                    r == null -> "Preparando recomendaciones…"
                    r.isEmpty() -> "Esta vez no encontré títulos parecidos en tu servidor."
                    else -> "Te preparé estos títulos que puedes ver aquí"
                },
                style = Type.Body, color = Jx.Muted,
            )
            Spacer(Modifier.height(22.dp))
            if (!r.isNullOrEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(r, key = { _, it -> it.kind + it.id }) { i, it ->
                        PosterCard(it.name, it.image, onClick = { onOpen(it) }, width = 120.dp,
                            subtitle = if (it.kind == "series") "Serie" else "Película",
                            modifier = if (i == 0) Modifier.focusRequester(firstReq) else Modifier)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionButton("Volver al inicio", onClose, Modifier.then(if (r.isNullOrEmpty()) Modifier.focusRequester(firstReq) else Modifier))
                if (r != null) Text("Se cierra en $left s", style = Type.Small, color = Jx.Faint,
                    modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
