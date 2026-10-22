package com.jox3.tvdesktop.data

/** Cuenta Xtream Codes guardada (igual que en la app Android). */
data class Account(
    val host: String,
    val user: String,
    val pass: String,
) {
    val key: String get() = "$host|$user"

    /** Nombre corto para mostrar: el dominio del servidor. */
    val label: String
        get() = host.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')

    /** Nunca muestra la contraseña (por si la cuenta termina en un registro de errores). */
    override fun toString() = "Account($host, $user, ***)"
}

/** Estado de la cuenta que devuelve el servidor al iniciar sesión. */
data class ServerInfo(
    val status: String,
    val expires: Long?,       // ms, null = sin fecha
    val activeCons: String,
    val maxCons: String,
)
