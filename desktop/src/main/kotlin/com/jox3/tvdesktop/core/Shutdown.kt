package com.jox3.tvdesktop.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tareas que se hacen justo antes de cerrar el programa (guardar dónde va la película, etc.).
 * [exit] lo pone la ventana: cierra ordenadamente (lo usa también el actualizador).
 */
object Shutdown {
    private val tasks = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var exit: (() -> Unit)? = null

    fun add(task: () -> Unit): () -> Unit { tasks += task; return { tasks -= task } }

    fun runAll() { tasks.forEach { runCatching { it() }.onFailure { e -> Log.w("Falló una tarea al cerrar", e) } } }
}
