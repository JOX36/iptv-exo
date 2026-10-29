package com.jox3.iptvexo.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jox3.iptvexo.tv.data.SavedItem
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage

/** "Porque viste X": al terminar una película o una serie en el celular. */
@Composable
fun PhoneBecause(repo: TvRepo, source: SavedItem, onOpen: (SavedItem) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val recs by produceState<List<SavedItem>?>(null, source.id) {
        value = runCatching { repo.becauseYouWatched(ctx, source) }.getOrDefault(emptyList())
    }
    LaunchedEffect(recs) { recs?.let { repo.library.setBecause(source.name, it) } }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Jx.Bg)) {
        RemoteImage(source.image, source.name, Modifier.fillMaxWidth().height(320.dp).alpha(0.25f), scale = ContentScale.Crop)
        Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(Color(0x66070A0F), Jx.Bg))))
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(vertical = 28.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("TERMINASTE", style = PType.Eyebrow, color = Jx.Accent)
                Spacer(Modifier.height(6.dp))
                Text("Porque viste ${source.name}", style = PType.Hero, color = Jx.Text, maxLines = 3)
                Spacer(Modifier.height(6.dp))
                val r = recs
                Text(
                    when {
                        r == null -> "Preparando recomendaciones…"
                        r.isEmpty() -> "Esta vez no encontré títulos parecidos en tu servidor."
                        else -> "Te preparé estos títulos que puedes ver aquí"
                    },
                    style = PType.Body, color = Jx.Muted,
                )
            }
            Spacer(Modifier.height(16.dp))
            val r = recs
            if (!r.isNullOrEmpty()) {
                // Cuadrícula de 3 columnas que llena la pantalla
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(r, key = { it.kind + it.id }) { it ->
                        PosterTile(it.name, it.image, Modifier.fillMaxWidth(),
                            subtitle = if (it.kind == "series") "Serie" else "Película") { onOpen(it) }
                    }
                }
            } else Spacer(Modifier.weight(1f))
            PButton("Volver al inicio", Modifier.fillMaxWidth().padding(horizontal = 20.dp), primary = false) { onClose() }
        }
    }
}
