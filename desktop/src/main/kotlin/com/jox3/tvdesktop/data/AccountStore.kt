package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Carpeta de datos del programa: %APPDATA%\JOX3 TV (o ~/.jox3tv fuera de Windows). */
object AppDirs {
    val data: File by lazy {
        val appData = System.getenv("APPDATA")
        val dir = if (!appData.isNullOrBlank()) File(appData, "JOX3 TV")
        else File(System.getProperty("user.home"), ".jox3tv")
        dir.apply { mkdirs() }
    }
}

/**
 * Cuentas guardadas y la cuenta activa, en un archivo JSON (accounts.json).
 * Mismo comportamiento que AccountStore de Android: la más reciente primero, máximo 10.
 */
class AccountStore(private val file: File = File(AppDirs.data, "accounts.json")) {

    /** Lista observable: la pantalla se actualiza sola al guardar o borrar. */
    val accounts = mutableStateListOf<Account>()
    private val currentKey = mutableStateOf<String?>(null)

    init { load() }

    fun current(): Account? = currentKey.value?.let { k -> accounts.firstOrNull { it.key == k } }

    /** Guarda la cuenta (la más reciente primero) y la marca como activa. */
    fun use(acc: Account) {
        val list = (listOf(acc) + accounts.filter { it.key != acc.key }).take(10)
        accounts.clear(); accounts.addAll(list)
        currentKey.value = acc.key
        save()
    }

    fun remove(acc: Account) {
        accounts.removeAll { it.key == acc.key }
        if (currentKey.value == acc.key) currentKey.value = null
        save()
    }

    fun logout() { currentKey.value = null; save() }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("list") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val host = a.optString("host"); val user = a.optString("user"); val pass = a.optString("pass")
                if (host.isNotBlank() && user.isNotBlank()) accounts.add(Account(host, user, pass))
            }
            currentKey.value = o.optString("current", "").ifBlank { null }
        } catch (e: Exception) {
            // Archivo dañado: se empieza de cero sin cerrar el programa.
        }
    }

    private fun save() {
        val arr = JSONArray()
        accounts.forEach { a -> arr.put(JSONObject().put("host", a.host).put("user", a.user).put("pass", a.pass)) }
        val o = JSONObject().put("list", arr).put("current", currentKey.value ?: "")
        try {
            // Se escribe en un archivo temporal y luego se reemplaza: si se va la luz no se pierde todo.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) { /* sin espacio o sin permisos: se sigue funcionando en memoria */ }
    }
}
