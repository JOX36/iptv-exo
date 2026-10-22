package com.jox3.tvdesktop.core

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

/**
 * Cifra las contraseñas guardadas con la protección de Windows (DPAPI):
 * solo el mismo usuario de Windows en el mismo PC puede leerlas. Si alguien
 * copia accounts.json a otro equipo, las contraseñas no le sirven.
 *
 * Fuera de Windows (o si DPAPI falla) se guarda como antes, para no perder la cuenta.
 */
object Secrets {
    private const val DPAPI = "dpapi:"
    private const val PLAIN = "plain:"
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun protect(secret: String): String {
        if (secret.isEmpty()) return ""
        if (isWindows) {
            try {
                val enc = Crypt32Util.cryptProtectData(secret.toByteArray(Charsets.UTF_8))
                return DPAPI + Base64.getEncoder().encodeToString(enc)
            } catch (e: Throwable) {
                Log.w("No se pudo cifrar la contraseña con Windows; se guarda sin cifrar", e)
            }
        }
        return PLAIN + secret
    }

    /** Devuelve la contraseña o null si no se puede descifrar (por ejemplo, archivo de otro PC). */
    fun unprotect(stored: String): String? = when {
        stored.isEmpty() -> ""
        stored.startsWith(DPAPI) -> try {
            String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored.removePrefix(DPAPI))), Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.w("No se pudo descifrar una contraseña guardada", e); null
        }
        stored.startsWith(PLAIN) -> stored.removePrefix(PLAIN)
        else -> stored // formato antiguo (sin prefijo)
    }
}
