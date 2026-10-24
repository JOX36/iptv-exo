package com.jox3.iptvexo.tv.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Código JOX3: los servidores de una persona viajan con su perfil a cualquier equipo.
 *
 * Todo se cifra EN EL EQUIPO con una llave que sale del código (PBKDF2 + AES-256-GCM).
 * A la nube solo sube el texto cifrado: ni el servidor ni quien vea la base de datos
 * puede leer usuarios ni contraseñas. Sin el código no hay forma de recuperarlo.
 */
object Vault {
    private const val SCOPE = "_vault"
    private const val ALPHA = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"   // sin 0/O ni 1/I para no confundir
    private const val LEN = 10
    private const val ITERATIONS = 60_000
    private val SALT = "jox3-vault-v1".toByteArray()

    class Keys(val id: String, val key: ByteArray)
    class Content(val servers: List<Account>, val updated: Long)
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
    suspend fun derive(code: String): Keys = withContext(Dispatchers.Default) {
        synchronized(cache) { cache[code] }?.let { return@withContext it }
        val out = pbkdf2(code.toByteArray(), SALT, ITERATIONS, 64)
        val keys = Keys(out.copyOfRange(32, 64).joinToString("") { "%02x".format(it) }, out.copyOfRange(0, 32))
        synchronized(cache) { cache[code] = keys }
        keys
    }

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

    private fun encrypt(keys: Keys, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(keys: Keys, data: String): String? = runCatching {
        val raw = Base64.decode(data, Base64.NO_WRAP)
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
        return Content(servers, o.optLong("updated"))
    }

    suspend fun upload(code: String, servers: List<Account>): Boolean {
        val keys = derive(code)
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("host", it.host).put("user", it.user).put("pass", it.pass)) }
        val data = encrypt(keys, JSONObject().put("v", 1).put("servers", arr).put("updated", now).toString())
        val change = JSONObject().put("list", "vault").put("item", "v").put("ts", now).put("data", data)
        return CloudSync.post(keys.id, SCOPE, now, listOf(change)) != null
    }

    suspend fun delete(code: String): Boolean {
        val keys = derive(code)
        val change = JSONObject().put("list", "vault").put("item", "v").put("ts", System.currentTimeMillis()).put("deleted", true)
        return CloudSync.post(keys.id, SCOPE, System.currentTimeMillis(), listOf(change)) != null
    }

    // ── En este equipo ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv_vault", Context.MODE_PRIVATE)

    /** Código guardado en este equipo para ese casillero (para mostrarlo y mantenerlo al día). */
    fun localCode(ctx: Context, vaultId: String): String? =
        vaultId.takeIf { it.isNotEmpty() }?.let { prefs(ctx).getString("code.$it", null) }

    fun saveLocal(ctx: Context, vaultId: String, code: String) {
        prefs(ctx).edit().putString("code.$vaultId", code).apply()
    }

    fun forgetLocal(ctx: Context, vaultId: String) {
        prefs(ctx).edit().remove("code.$vaultId").apply()
    }

    /** "No volver a preguntar en este equipo". */
    fun dismissed(ctx: Context, vaultId: String) = prefs(ctx).getBoolean("skip.$vaultId", false)
    fun dismiss(ctx: Context, vaultId: String) { prefs(ctx).edit().putBoolean("skip.$vaultId", true).apply() }
    internal val skippedThisLaunch = HashSet<String>()

    /**
     * Si este equipo tiene el código del perfil: vuelve a subir los servidores elegidos
     * cuando cambió algo (por ejemplo la contraseña de uno). Lo de otros equipos se conserva.
     */
    suspend fun refresh(ctx: Context, profile: Profile, accounts: AccountStore) {
        val code = localCode(ctx, profile.vaultId) ?: return
        runCatching {
            val remote = fetch(code).servers
            // Lo guardado desde otro equipo también queda en este
            accounts.addAll(remote)
            // Si aquí cambió la contraseña de alguno, se actualiza en la nube
            val local = accounts.all()
            val updated = remote.map { r -> local.firstOrNull { it.key == r.key } ?: r }
            if (updated != remote) upload(code, updated)
        }
    }
}
