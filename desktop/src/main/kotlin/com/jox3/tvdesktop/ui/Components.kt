package com.jox3.tvdesktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Logo "JOX3 TV" con el icono del programa. */
@Composable
fun Brand(iconSize: Dp = 40.dp, textSize: Int = 24) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource("icon.png"),
            contentDescription = null,
            modifier = Modifier.size(iconSize).clip(RoundedCornerShape(iconSize / 5)),
        )
        Spacer(Modifier.width(12.dp))
        Text("JOX3", fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = textSize.sp, color = Jx.Text)
        Spacer(Modifier.width(6.dp))
        Text("TV", fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = textSize.sp, color = Jx.Accent)
    }
}

/** Etiqueta pequeña tipo "píldora" (estado de la cuenta, "Pronto", etc.). */
@Composable
fun Pill(text: String, color: Color = Jx.Accent, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = Exo2)
    }
}

/** Fondo que se ilumina un poco al pasar el mouse por encima. */
@Composable
fun Modifier.hoverSurface(
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    base: Color = Jx.Surface,
    hover: Color = Jx.SurfaceHi,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return this
        .clip(shape)
        .background(if (hovered) hover else base)
        .border(1.dp, if (hovered) Jx.Accent.copy(alpha = 0.5f) else Jx.Line, shape)
        .hoverable(source)
}
