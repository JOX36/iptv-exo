package com.jox3.iptvexo.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester

/**
 * Al volver a una lista, el foco regresa al elemento donde estaba.
 *
 * Reemplaza a `focusRestorer()` de Compose, que en esta versión cierra la app
 * ("Release should only be called once") cuando una lista desaparece o cambia
 * mientras guarda el foco (p. ej. "Continuar viendo" al salir de una película).
 * Esta versión no "fija" elementos de la lista: si el elemento ya no existe,
 * simplemente entra al primero visible.
 */
fun Modifier.safeFocusRestorer(): Modifier = composed {
    val req = remember { FocusRequester() }
    this
        .focusRequester(req)
        .focusProperties {
            exit = { runCatching { req.saveFocusedChild() }; FocusRequester.Default }
            enter = {
                if (runCatching { req.restoreFocusedChild() }.getOrDefault(false)) FocusRequester.Cancel
                else FocusRequester.Default
            }
        }
        .focusGroup()
}
