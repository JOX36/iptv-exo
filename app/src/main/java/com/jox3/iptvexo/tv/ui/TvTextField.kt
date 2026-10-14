package com.jox3.iptvexo.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * Campo de texto para el control remoto: al recorrer el formulario con las
 * flechas NO abre el teclado (en TV es molesto). Solo al pulsar OK sobre el
 * campo se activa la edición; "Listo" en el teclado la cierra.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var everEdited by remember { mutableStateOf(false) }
    var fieldHadFocus by remember { mutableStateOf(false) }
    val fieldReq = remember { FocusRequester() }
    val viewReq = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.Small, color = Jx.Muted)
        if (!editing) {
            FocusSurface(
                onClick = { editing = true; everEdited = true },
                modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(viewReq),
                shape = shape,
                focusedScale = 1.02f,
                container = Jx.Surface,
                focusedContainer = Jx.SurfaceHi,
            ) {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    val shown = if (password && value.isNotEmpty()) "•".repeat(value.length) else value
                    Text(
                        shown.ifEmpty { placeholder },
                        style = Type.Label,
                        color = if (value.isEmpty()) Jx.Faint else Jx.Text,
                        maxLines = 1,
                    )
                }
            }
        } else {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.Label.copy(color = Jx.Text),
                cursorBrush = SolidColor(Jx.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editing = false; keyboard?.hide(); onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Jx.SurfaceHi, shape)
                    .border(BorderStroke(2.dp, Jx.Accent), shape)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
                    .focusRequester(fieldReq)
                    .onFocusChanged {
                        if (it.isFocused) fieldHadFocus = true
                        else if (fieldHadFocus) { fieldHadFocus = false; editing = false }
                    },
            )
        }
    }

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldReq.requestFocus() }
            keyboard?.show()
        } else if (everEdited) {
            runCatching { viewReq.requestFocus() }
        }
    }
}
