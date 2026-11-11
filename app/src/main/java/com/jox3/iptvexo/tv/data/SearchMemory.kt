package com.jox3.iptvexo.tv.data

/**
 * Lo último que se buscó, para no perderlo: si desde el buscador se abre un canal, una película
 * o una serie (aunque sea de otro servidor y la app cambie de servidor), Atrás vuelve a los
 * mismos resultados para elegir otra opción.
 */
object SearchMemory {
    @Volatile var query: String = ""
    @Volatile var only: String? = null
    /** Lo que se está viendo se abrió desde el buscador: Atrás vuelve a él. */
    @Volatile var fromSearch: Boolean = false
}
