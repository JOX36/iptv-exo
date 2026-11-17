package com.jox3.iptvexo.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Memoria del foco de una lista: al volver a ella (con las flechas o desde un
 * detalle), el foco cae en el último elemento donde estaba.
 *
 * Por qué no se usa `focusRestorer()` de Compose: en esta versión cierra la app
 * cuando la lista cambia ("Release should only be called once"), y la variante
 * con saveFocusedChild/restoreFocusedChild pide el foco DENTRO del movimiento de
 * foco y luego lo cancela: el control parecía "pegado" al volver a las categorías.
 *
 * Aquí la lista solo REDIRIGE el movimiento al elemento recordado, y únicamente si
 * ese elemento existe en pantalla; si no, entra al más cercano como siempre.
 */
@Stable
class FocusMemory {
    internal val req = FocusRequester()
    internal var key by mutableStateOf<Any?>(null)
    internal var attached = 0
    /** Recordar un elemento de antemano (p. ej. la categoría seleccionada). */
    fun remember(key: Any?) { this.key = key }
}

@Composable
fun rememberFocusMemory(): FocusMemory = remember { FocusMemory() }

/** La lista entera: al entrar, va al elemento recordado. */
fun Modifier.focusMemoryGroup(mem: FocusMemory): Modifier = this
    .focusProperties { enter = { if (mem.attached > 0) mem.req else FocusRequester.Default } }
    .focusGroup()

/** Cada elemento de la lista (con su clave única). */
@Composable
fun Modifier.focusMemoryItem(mem: FocusMemory, key: Any): Modifier {
    val isTarget by remember(mem, key) { derivedStateOf { mem.key == key } }
    DisposableEffect(isTarget) {
        if (isTarget) mem.attached++
        onDispose { if (isTarget) mem.attached-- }
    }
    return this
        .then(if (isTarget) Modifier.focusRequester(mem.req) else Modifier)
        .onFocusChanged { if (it.hasFocus) mem.key = key }
}

/**
 * Destino de foco "seguro": un FocusRequester que sabe si su elemento está en pantalla.
 * Se usa en los `enter = { … }` (p. ej. la barra lateral): si el elemento no existe en ese
 * instante, el foco va al más cercano en vez de cerrar la app ("FocusRequester is not
 * initialized").
 */
@Stable
class SafeFocusTarget {
    val req = FocusRequester()
    internal var attached = 0
    /** Para `focusProperties { enter = { target.orDefault() } }`. */
    fun orDefault(): FocusRequester = if (attached > 0) req else FocusRequester.Default
}

@Composable
fun rememberSafeFocusTarget(): SafeFocusTarget = remember { SafeFocusTarget() }

/** Marca el elemento destino (solo uno a la vez). */
@Composable
fun Modifier.safeFocusTarget(t: SafeFocusTarget): Modifier {
    DisposableEffect(t) {
        t.attached++
        onDispose { t.attached-- }
    }
    return this.focusRequester(t.req)
}

/**
 * Recuerda el último botón o tarjeta que tuvo el foco (todos los de la app pasan por
 * [FocusSurface]). Al abrir pantalla completa se guarda ([save]) y al volver se le devuelve
 * el foco exactamente a ese elemento ([restore]); la lista se desplaza sola hasta él.
 * Antes el foco caía "en cualquier parte" (a veces fuera de la pantalla) y había que mover
 * las flechas para encontrarlo.
 */
object FocusTracker {
    class Entry internal constructor() {
        val req = FocusRequester()
        internal var attached = false
    }
    @Volatile internal var last: Entry? = null
    private var saved: Entry? = null

    /** Guardar dónde está el foco (justo antes de abrir algo encima). */
    fun save() { saved = last?.takeIf { it.attached } }

    /** Devolver el foco a lo guardado. false si ya no existe (entonces se usa lo de siempre). */
    fun restore(): Boolean {
        val e = saved ?: return false
        saved = null
        if (!e.attached) return false
        return runCatching { e.req.requestFocus(); true }.getOrDefault(false)
    }
}

/** Lo usa [FocusSurface]: anota este elemento como "el último con foco". */
@Composable
fun Modifier.trackFocus(): Modifier {
    val e = remember { FocusTracker.Entry() }
    DisposableEffect(e) {
        e.attached = true
        onDispose { e.attached = false }
    }
    return this.focusRequester(e.req).onFocusChanged { if (it.isFocused) FocusTracker.last = e }
}

/** Fila sin memoria (sus elementos los ponen otras pantallas): entra al elemento más cercano. */
fun Modifier.safeFocusRestorer(): Modifier = this.focusGroup()
