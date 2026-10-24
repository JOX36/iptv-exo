package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import com.jox3.tvdesktop.core.Secrets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cuentas guardadas y la cuenta activa, en accounts.json (carpeta de datos del programa).
 * Mismo comportamiento que AccountStore de Android: la más reciente primero, máximo 20.
 * Las contraseñas se guardan cifradas con Windows ([Secrets]). Si una no se puede
 * descifrar, la cuenta se conserva y solo se pide escribir la contraseña otra vez.
 */
class AccountStore(private val file: File = File(AppDirs.data, "accounts.json")) {

    /** Lista observable: la pantalla se actualiza sola al guardar o borrar. */
    val accounts = mutableStateListOf<Account>()
    private val currentKey = mutableStateOf<String?>(null)

    init { load() }

    fun current(): Account? = currentKey.value?.let { k -> accounts.firstOrNull { it.key == k } }

    /** Guarda la cuenta (la más reciente primero) y la marca como activa. */
    fun use(acc: Account) {
        val list = (listOf(acc) + accounts.filter { it.key != acc.key }).take(MAX)
        accounts.clear(); accounts.addAll(list)
        currentKey.value = acc.key
        save()
    }

    /** Agrega cuentas sin cambiar la que está en uso (las repetidas se ignoran). Devuelve cuántas eran nuevas. */
    fun addAll(accs: List<Account>): Int {
        val fresh = accs.filter { a -> accounts.none { it.key == a.key } }.distinctBy { it.key }
        // Si una que ya estaba llegó con otra contraseña (la cambiaron en otro equipo), se actualiza
        var changed = false
        accs.forEach { a ->
            val i = accounts.indexOfFirst { it.key == a.key }
            if (i >= 0 && accounts[i].pass != a.pass && a.pass.isNotEmpty()) { accounts[i] = a; changed = true }
        }
        if (fresh.isEmpty() && !changed) return 0
        val list = (accounts + fresh).take(MAX)
        accounts.clear(); accounts.addAll(list)
        save()
        return fresh.size
    }

    fun all(): List<Account> = accounts.toList()

    fun remove(acc: Account) {
        accounts.removeAll { it.key == acc.key }
        if (currentKey.value == acc.key) currentKey.value = null
        save()
    }

    fun logout() { currentKey.value = null; save() }

    private fun load() {
        val data = SafeFile.read(file) { text -> JSONObject(text) } ?: return
        val arr = data.optJSONArray("list") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val host = a.optString("host").trim()
            val user = a.optString("user").trim()
            if (host.isEmpty() || user.isEmpty()) continue
            val pass = Secrets.unprotect(a.optString("pass")) ?: "" // vacía = pedirla de nuevo
            if (accounts.none { it.host == host && it.user == user }) accounts.add(Account(host, user, pass))
        }
        currentKey.value = data.optString("current", "").ifBlank { null }
        Log.i("Cuentas cargadas: ${accounts.size}")
    }

    private fun save() {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().put("host", a.host).put("user", a.user).put("pass", Secrets.protect(a.pass)))
        }
        val o = JSONObject().put("version", 1).put("list", arr).put("current", currentKey.value ?: "")
        try {
            SafeFile.write(file, o.toString(2))
        } catch (e: Exception) {
            Log.e("No se pudieron guardar las cuentas", e) // se sigue funcionando en memoria
        }
    }

    companion object { const val MAX = 20 }
}
