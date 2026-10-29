package com.jox3.tvdesktop.data

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.core.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Avísame cuando empiece": recordatorios de programas de la guía (igual que la app Android).
 * Con el programa abierto sale un aviso para cambiar al canal con un clic y, si la ventana está
 * minimizada, también la notificación de Windows.
 */
object Reminders {
    data class Reminder(
        val acc: String, val chId: String, val chName: String, val logo: String, val cat: String,
        val title: String, val start: Long, val end: Long,
    ) {
        val key get() = "$acc|$chId|$start"
        fun channel() = Channel(chId, chName, logo, cat, 1)
    }

    /** Sube cuando cambia la lista (para repintar los ⏰ de la guía). */
    val version = mutableIntStateOf(0)
    /** Recordatorio que está empezando ahora (lo muestra la pantalla principal). */
    val due = mutableStateOf<Reminder?>(null)

    @Volatile var fileOverride: File? = null
    private val file get() = fileOverride ?: File(AppDirs.data, "reminders.json")

    @Volatile private var cache: List<Reminder>? = null
    private var shown = LinkedHashSet<String>()

    @Synchronized
    fun all(): List<Reminder> = cache ?: runCatching {
        val o = SafeFile.read(file) { JSONObject(it) } ?: JSONObject()
        val arr = o.optJSONArray("list") ?: JSONArray()
        o.optJSONArray("shown")?.let { a -> for (i in 0 until a.length()) shown.add(a.optString(i)) }
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }.getOrDefault(emptyList()).also { cache = it }

    @Synchronized
    private fun save(list: List<Reminder>) {
        cache = list
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        while (shown.size > 50) shown.remove(shown.first())
        try { SafeFile.write(file, JSONObject().put("list", arr).put("shown", JSONArray(shown.toList())).toString()) }
        catch (e: Exception) { Log.w("No se pudieron guardar los recordatorios", e) }
        version.intValue++
    }

    fun has(acc: Account, ch: Channel, p: EpgItem): Boolean = all().any { it.key == "${acc.key}|${ch.id}|${p.start}" }

    /** Pone o quita el aviso. Devuelve el mensaje para mostrar. */
    fun toggle(acc: Account, ch: Channel, p: EpgItem): String {
        val now = System.currentTimeMillis()
        val list = all().filter { it.end > now }
        val r = Reminder(acc.key, ch.id, ch.name, ch.logo, ch.categoryId, p.cleanTitle, p.start, p.end)
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.forLanguageTag("es"))
        return if (list.any { it.key == r.key }) {
            save(list.filter { it.key != r.key }); "Aviso quitado"
        } else {
            save(list + r); "⏰ Te aviso a las ${fmt.format(java.util.Date(p.start))}: ${p.cleanTitle}"
        }
    }

    fun remove(r: Reminder) = save(all().filter { it.key != r.key })

    /**
     * La pantalla principal lo llama cada ~20 s: si algo empieza (1 min antes hasta 5 min
     * después), se avisa una sola vez. Devuelve el recordatorio nuevo, si hay.
     */
    @Synchronized
    fun checkDue(now: Long = System.currentTimeMillis()): Reminder? {
        if (due.value != null) return null
        val r = all().firstOrNull { it.start - 60_000 <= now && now < it.start + 5 * 60_000 && it.key !in shown } ?: return null
        shown.add(r.key)
        save(all().filter { it.end > now })
        due.value = r
        return r
    }

    private fun toJson(r: Reminder) = JSONObject().put("acc", r.acc).put("id", r.chId).put("name", r.chName).put("logo", r.logo)
        .put("cat", r.cat).put("title", r.title).put("start", r.start).put("end", r.end)
    private fun fromJson(o: JSONObject) = Reminder(o.optString("acc"), o.optString("id"), o.optString("name"), o.optString("logo"),
        o.optString("cat"), o.optString("title"), o.optLong("start"), o.optLong("end"))
}
