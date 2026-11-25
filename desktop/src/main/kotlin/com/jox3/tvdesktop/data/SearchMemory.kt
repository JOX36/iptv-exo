package com.jox3.tvdesktop.data

/**
 * Lo último que se buscó, para no perderlo (igual que Android): si desde el buscador se abre un
 * canal, una película o una serie (aunque sea de otro servidor y el programa cambie de servidor),
 * al volver al buscador siguen los mismos resultados para elegir otra opción.
 */
object SearchMemory {
    @Volatile var query: String = ""
    @Volatile var only: String? = null
    /** Lo que se está viendo (película o serie) se abrió desde el buscador: "Volver" regresa a él. */
    @Volatile var fromSearch: Boolean = false
}
