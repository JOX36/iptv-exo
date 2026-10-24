package com.jox3.tvdesktop.data

import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import com.jox3.tvdesktop.core.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Código JOX3: los servidores de una persona viajan con su perfil a cualquier equipo.
 * EXACTAMENTE igual que la app Android (tv/data/Vault.kt), así un código creado en el TV Box
 * funciona en el PC y al revés.
 *
 * Todo se cifra EN EL EQUIPO con una llave que sale del código (PBKDF2 + AES-256-GCM).
 * A la nube solo sube el texto cifrado: nadie puede leer usuarios ni contraseñas sin el código.
 */
object Vault {
    private const val SCOPE = "_vault"
    private const val ALPHA = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"   // sin 0/O ni 1/I para no confundir
    private const val LEN = 10
    private const val ITERATIONS = 60_000
    private val SALT = "jox3-vault-v1".toByteArray()

    class Keys(val id: String, val key: ByteArray)
    class Content(val servers: List<Account>, val updated: Long, val profileId: String = "")
    class NotFound : Exception("No hay servidores guardados con ese código")

    fun newCode(): String {
        val r = SecureRandom()
        return (1..LEN).map { ALPHA[r.nextInt(ALPHA.length)] }.joinToString("")
    }

    /** "7K4P99QM2A" → "JX-7K4P9-9QM2A" */
    fun pretty(code: String) = "JX-" + code.take(5) + "-" + code.drop(5)

    /** Lo que escribió la persona → el código limpio, o null si no tiene la forma correcta. */
    fun normalize(input: String): String? {
        var s = input.uppercase().filter { it.isLetterOrDigit() }
        if (s.length == LEN + 2 && s.startsWith("JX")) s = s.drop(2)
        return s.takeIf { it.length == LEN && it.all { c -> c in ALPHA } }
    }

    private val cache = HashMap<String, Keys>()

    /** Llave y número de casillero a partir del código (tarda un momento a propósito). */
    suspend fun derive(code: String): Keys = withContext(Dispatchers.Default) { deriveNow(code) }

    internal fun deriveNow(code: String): Keys {
        synchronized(cache) { cache[code] }?.let { return it }
        val out = pbkdf2(code.toByteArray(), SALT, ITERATIONS, 64)
        val keys = Keys(out.copyOfRange(32, 64).joinToString("") { "%02x".format(it) }, out.copyOfRange(0, 32))
        synchronized(cache) { cache[code] = keys }
        return keys
    }

    /** PBKDF2-HMAC-SHA256 (escrito igual que en Android para dar exactamente los mismos bytes). */
    private fun pbkdf2(pass: ByteArray, salt: ByteArray, iters: Int, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pass, "HmacSHA256"))
        val out = ByteArray(len)
        var off = 0
        var block = 1
        while (off < len) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (n in 1 until iters) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, len - off)
            System.arraycopy(t, 0, out, off, n)
            off += n; block++
        }
        return out
    }

    /** AES-256-GCM: IV aleatorio de 12 bytes + texto cifrado (tag de 128 bits), en Base64 sin saltos. */
    internal fun encrypt(keys: Keys, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    internal fun decrypt(keys: Keys, data: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(data.trim())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()

    /** Baja y descifra los servidores del código. Lanza [NotFound] si no hay nada guardado. */
    suspend fun fetch(code: String): Content {
        val keys = derive(code)
        val res = CloudSync.post(keys.id, SCOPE, 0L, emptyList()) ?: throw Exception("No se pudo conectar. Revisa el internet")
        val item = res.items.firstOrNull { it.optString("list") == "vault" && !it.optBoolean("deleted") } ?: throw NotFound()
        val json = decrypt(keys, item.optString("data")) ?: throw NotFound()
        val o = JSONObject(json)
        val arr = o.optJSONArray("servers") ?: JSONArray()
        val servers = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { s -> Account(s.optString("host"), s.optString("user"), s.optString("pass")) }
                ?.takeIf { it.host.isNotBlank() && it.user.isNotBlank() }
        }
        return Content(servers, o.optLong("updated"), o.optString("profile"))
    }

    /** Contenido cifrado: {v:1, servers:[{host,user,pass}], updated, profile}. */
    internal fun contentJson(servers: List<Account>, profileId: String, now: Long): String {
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("host", it.host).put("user", it.user).put("pass", it.pass)) }
        return JSONObject().put("v", 1).put("servers", arr).put("updated", now).put("profile", profileId).toString()
    }

    suspend fun upload(code: String, servers: List<Account>, profileId: String = ""): Boolean {
        val keys = derive(code)
        val now = System.currentTimeMillis()
        val data = encrypt(keys, contentJson(servers, profileId, now))
        val change = JSONObject().put("list", "vault").put("item", "v").put("ts", now).put("data", data)
        return CloudSync.post(keys.id, SCOPE, now, listOf(change)) != null
    }

    suspend fun delete(code: String): Boolean {
        val keys = derive(code)
        val now = System.currentTimeMillis()
        val change = JSONObject().put("list", "vault").put("item", "v").put("ts", now).put("deleted", true)
        return CloudSync.post(keys.id, SCOPE, now, listOf(change)) != null
    }

    // ── En este equipo (vault.json; los códigos se guardan cifrados con Windows) ──

    private val file get() = File(AppDirs.data, "vault.json")
    private fun readLocal(): JSONObject = SafeFile.read(file) { JSONObject(it) } ?: JSONObject()
    private fun writeLocal(o: JSONObject) {
        try { SafeFile.write(file, o.toString()) } catch (e: Exception) { Log.w("No se pudo guardar el código JOX3", e) }
    }

    /** Código guardado en este equipo para ese casillero (para mostrarlo y mantenerlo al día). */
    @Synchronized
    fun localCode(vaultId: String): String? {
        if (vaultId.isEmpty()) return null
        val stored = readLocal().optJSONObject("codes")?.optString(vaultId).orEmpty()
        return stored.takeIf { it.isNotEmpty() }?.let { Secrets.unprotect(it) }?.takeIf { normalize(it) != null }
    }

    @Synchronized
    fun saveLocal(vaultId: String, code: String) {
        val o = readLocal()
        o.put("codes", (o.optJSONObject("codes") ?: JSONObject()).put(vaultId, Secrets.protect(code)))
        writeLocal(o)
    }

    @Synchronized
    fun forgetLocal(vaultId: String) {
        val o = readLocal()
        o.optJSONObject("codes")?.remove(vaultId)
        writeLocal(o)
    }

    /** El perfil guarda servidores con código y este equipo aún no los tiene (ni dijo "no preguntar"). */
    fun needsUnlock(p: Profile): Boolean {
        val v = p.vaultId
        return v.isNotEmpty() && localCode(v) == null && !dismissed(v) && v !in skippedThisLaunch
    }

    /** "No volver a preguntar en este equipo". */
    @Synchronized
    fun dismissed(vaultId: String) = readLocal().optJSONObject("skip")?.optBoolean(vaultId) == true

    @Synchronized
    fun dismiss(vaultId: String) {
        val o = readLocal()
        o.put("skip", (o.optJSONObject("skip") ?: JSONObject()).put(vaultId, true))
        writeLocal(o)
    }

    /** "Ahora no": no se vuelve a preguntar hasta que se abra el programa otra vez. */
    val skippedThisLaunch = HashSet<String>()

    /**
     * Si este equipo tiene el código del perfil: trae los servidores agregados en otros equipos
     * y vuelve a subir la lista si cambió algo aquí (por ejemplo la contraseña de uno).
     */
    suspend fun refresh(profile: Profile, accounts: AccountStore) {
        val code = localCode(profile.vaultId) ?: return
        runCatching {
            val content = fetch(code)
            val remote = content.servers
            // Lo guardado desde otro equipo también queda en este (como servidor de esta persona)
            val before = accounts.all().map { it.key }.toSet()
            accounts.addAll(remote)
            ServerAccess.claimFromVault(profile, remote, remote.map { it.key }.toSet() - before, accounts)
            // Contraseñas cambiadas aquí y servidores nuevos que agregó en este equipo
            val local = accounts.all()
            val extra = local.filter { ServerAccess.owner(it.key) == profile.id && remote.none { r -> r.key == it.key } }
            val updated = remote.map { r -> local.firstOrNull { it.key == r.key } ?: r } + extra
            if (updated != remote || content.profileId != profile.id) {
                if (upload(code, updated, profile.id)) ServerAccess.setVaultKeys(profile.id, updated.map { it.key }.toSet())
            }
        }.onFailure { Log.w("Código JOX3: no se pudo actualizar (${it.message})") }
    }
}
