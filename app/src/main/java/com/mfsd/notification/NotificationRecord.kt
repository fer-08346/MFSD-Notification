package com.mfsd.notification

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Un registro de notificación guardado únicamente en el teléfono. */
data class NotificationRecord(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** 1 = grupo, 0 = chat individual, -1 = Android no dio datos suficientes. */
    val whatsappKind: Int,
    /** Cantidad aproximada de mensajes expuestos en el estilo de Android. */
    val visibleMessageCount: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("packageName", packageName)
        put("appName", appName)
        put("title", title)
        put("text", text)
        put("postedAt", postedAt)
        put("whatsappKind", whatsappKind)
        put("visibleMessageCount", visibleMessageCount)
    }

    /** Lectura completa: aplicación, remitente/título y cuerpo del mensaje. */
    fun spokenText(): String {
        val parts = mutableListOf<String>()
        parts.add(appName)
        if (title.isNotBlank() && !title.equals(appName, ignoreCase = true)) parts.add(title)
        if (text.isNotBlank()) parts.add(text)
        return parts.joinToString(". ")
    }

    /** Resumen breve: solo aplicación y remitente o título. */
    fun spokenBrief(): String {
        return if (title.isNotBlank() && !title.equals(appName, ignoreCase = true)) {
            "$appName: $title"
        } else {
            "$appName. Nueva notificación"
        }
    }

    companion object {
        fun fromJson(json: JSONObject): NotificationRecord = NotificationRecord(
            key = json.optString("key"),
            packageName = json.optString("packageName"),
            appName = json.optString("appName", "Aplicación"),
            title = json.optString("title"),
            text = json.optString("text"),
            postedAt = json.optLong("postedAt"),
            whatsappKind = json.optInt("whatsappKind", -1),
            visibleMessageCount = json.optInt("visibleMessageCount", 1).coerceAtLeast(1)
        )
    }
}

object NotificationStore {
    const val PREFS_NAME = "mfsd_notification_settings"
    const val KEY_SELECTED_APPS = "selected_apps"
    const val KEY_MASTER_ENABLED = "master_enabled"
    const val KEY_READ_INSTANTLY = "read_instantly"
    const val KEY_SUMMARY_ENABLED = "summary_enabled"
    const val KEY_TTS_ENGINE = "tts_engine"
    const val KEY_AUDIO_MODE = "audio_mode"
    const val KEY_INSTANT_MODE = "instant_read_mode"
    const val INSTANT_MODE_FULL = "full"
    const val INSTANT_MODE_BRIEF = "brief"
    const val KEY_KEEP_ALIVE = "keep_alive"
    const val KEY_EVENTS = "notification_events"
    const val KEY_LAST_SUMMARY_AT = "last_summary_at"
    const val KEY_SCHEDULER_INITIALIZED = "scheduler_initialized"
    private const val MAX_EVENTS = 1000
    private val lock = Any()

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet() ?: emptySet()

    fun saveSelectedPackages(context: Context, values: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED_APPS, values.toSet()).apply()
    }

    fun add(context: Context, record: NotificationRecord) {
        synchronized(lock) {
            val prefs = prefs(context)
            val old = try { JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]") }
                       catch (_: Exception) { JSONArray() }
            old.put(record.toJson())
            val trimmed = JSONArray()
            val start = (old.length() - MAX_EVENTS).coerceAtLeast(0)
            for (i in start until old.length()) trimmed.put(old.getJSONObject(i))
            prefs.edit().putString(KEY_EVENTS, trimmed.toString()).apply()
        }
    }

    fun readAll(context: Context): List<NotificationRecord> = synchronized(lock) {
        val raw = prefs(context).getString(KEY_EVENTS, "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        buildList {
            for (i in 0 until array.length()) {
                try { add(NotificationRecord.fromJson(array.getJSONObject(i))) }
                catch (_: Exception) { /* Se omite un registro dañado, sin cerrar la app. */ }
            }
        }
    }

    /** Evita que el próximo resumen incluya avisos anteriores a una pausa global. */
    fun moveSummaryCutoffToNow(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SUMMARY_AT, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .putString(KEY_EVENTS, "[]")
            .putLong(KEY_LAST_SUMMARY_AT, System.currentTimeMillis())
            .apply()
    }

    /** Avanza el corte y conserva cualquier evento que haya llegado después de él. */
    fun finishSummary(context: Context, cutoff: Long) {
        synchronized(lock) {
            val prefs = prefs(context)
            val raw = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
            val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            val remaining = JSONArray()
            for (i in 0 until array.length()) {
                val item = try { array.getJSONObject(i) } catch (_: Exception) { continue }
                if (item.optLong("postedAt") > cutoff) remaining.put(item)
            }
            prefs.edit()
                .putString(KEY_EVENTS, remaining.toString())
                .putLong(KEY_LAST_SUMMARY_AT, cutoff)
                .apply()
        }
    }

    fun buildSummary(context: Context, source: List<NotificationRecord>, includeTime: Boolean = true): String {
        val currentTime = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())
        val header = if (includeTime) "Resumen de MFSD Notification, hora $currentTime." else "Resumen de MFSD Notification."
        if (source.isEmpty()) {
            return "$header Durante los últimos treinta minutos no llegaron notificaciones nuevas."
        }

        // Para una misma notificación agrupada, se toma la versión más reciente del intervalo.
        val latestByKey = LinkedHashMap<String, NotificationRecord>()
        source.sortedBy { it.postedAt }.forEach { record ->
            latestByKey[record.packageName + "|" + record.key] = record
        }
        val records = latestByKey.values.toList()
        val byApp = records.groupBy { it.packageName }
        val appCounts = byApp.entries
            .map { entry ->
                val label = entry.value.lastOrNull()?.appName ?: "Aplicación"
                Triple(label, entry.value.size, entry.key)
            }
            .sortedByDescending { it.second }

        val opening = "$header Se registraron ${records.size} notificaciones en ${byApp.size} aplicaciones."
        // Desglose de todas las aplicaciones registradas
        val appBreakdown = appCounts.joinToString(". ") { (name, count, _) -> "$name: $count avisos" }

        val whatsapp = records.filter { it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b" }
        val whatsappText = if (whatsapp.isEmpty()) "" else {
            val groups = whatsapp.filter { it.whatsappKind == 1 }.sumOf { it.visibleMessageCount }
            val chats = whatsapp.filter { it.whatsappKind == 0 }.sumOf { it.visibleMessageCount }
            val unknown = whatsapp.filter { it.whatsappKind == -1 }.sumOf { it.visibleMessageCount }
            val bits = mutableListOf<String>()
            if (groups > 0) bits.add("aproximadamente $groups mensajes visibles de grupos")
            if (chats > 0) bits.add("aproximadamente $chats mensajes visibles de chats individuales")
            if (unknown > 0) bits.add("$unknown mensajes o avisos de WhatsApp que no pude clasificar")
            if (bits.isEmpty()) "" else "En WhatsApp detecté ${bits.joinToString(" y ")}."
        }

        val examples = records.asReversed()
            .filter { it.text.isNotBlank() || it.title.isNotBlank() }
            .take(5)
            .joinToString(". ") { record ->
                val headline = if (record.title.isNotBlank() && !record.title.equals(record.appName, true)) {
                    "${record.appName}, ${record.title}"
                } else record.appName
                val body = record.text.take(150)
                if (body.isBlank()) headline else "$headline: $body"
            }

        return listOf(opening, appBreakdown, whatsappText, if (examples.isNotBlank()) "Últimos detalles: $examples" else "")
            .filter { it.isNotBlank() }
            .joinToString(". ")
            .take(2000)
    }
}
