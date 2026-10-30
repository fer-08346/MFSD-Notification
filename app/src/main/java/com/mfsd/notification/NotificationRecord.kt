package com.mfsd.notification

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
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
    const val KEY_PAUSE_ON_MUSIC = "pause_on_music"
    const val KEY_DND_SLEEP_MODE = "dnd_sleep_mode"
    const val KEY_PROXIMITY_SILENCE = "proximity_silence"
    const val KEY_ANTI_SPAM = "anti_spam"
    const val KEY_SUMMARY_INTERVAL_MINUTES = "summary_interval_minutes"
    const val DEFAULT_SUMMARY_INTERVAL_MINUTES = 30L

    // Modo sueño por horario programado
    const val KEY_SLEEP_SCHEDULE_ENABLED = "sleep_schedule_enabled"
    const val KEY_SLEEP_START_HOUR = "sleep_start_hour"
    const val KEY_SLEEP_START_MINUTE = "sleep_start_minute"
    const val KEY_SLEEP_END_HOUR = "sleep_end_hour"
    const val KEY_SLEEP_END_MINUTE = "sleep_end_minute"
    const val KEY_AUTO_CLEAR_BEFORE_SLEEP = "auto_clear_before_sleep"
    private const val KEY_LAST_AUTO_CLEARED_DATE = "last_auto_cleared_date"

    // Anuncio de llamadas entrantes
    const val KEY_ANNOUNCE_CALLS = "announce_calls"
    const val KEY_CALL_AUDIO_CHANNEL = "call_audio_channel"

    const val KEY_EVENTS = "notification_events"
    const val KEY_LAST_SUMMARY_AT = "last_summary_at"
    const val KEY_SCHEDULER_INITIALIZED = "scheduler_initialized"

    // Reducido a 150 para que el almacenamiento sea ultra ligero y nunca bloquee el hilo ni la memoria
    private const val MAX_EVENTS = 150
    private val lock = Any()

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet() ?: emptySet()

    fun saveSelectedPackages(context: Context, values: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED_APPS, values.toSet()).apply()
    }

    fun summaryIntervalMinutes(context: Context): Long =
        prefs(context).getLong(KEY_SUMMARY_INTERVAL_MINUTES, DEFAULT_SUMMARY_INTERVAL_MINUTES).coerceAtLeast(1L)

    fun setSummaryIntervalMinutes(context: Context, minutes: Long) {
        prefs(context).edit().putLong(KEY_SUMMARY_INTERVAL_MINUTES, minutes.coerceAtLeast(1L)).apply()
    }

    fun intervalName(minutes: Long): String = when (minutes) {
        1L -> "el último minuto"
        5L -> "los últimos cinco minutos"
        10L -> "los últimos diez minutos"
        15L -> "los últimos quince minutos"
        30L -> "los últimos treinta minutos"
        60L -> "la última hora"
        120L -> "las últimas dos horas"
        else -> "los últimos $minutes minutos"
    }

    /**
     * Verifica si actualmente el teléfono se encuentra en el horario de sueño configurado.
     */
    fun isSleepTimeNow(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_DND_SLEEP_MODE, true)) return false
        if (!p.getBoolean(KEY_SLEEP_SCHEDULE_ENABLED, true)) return false

        val cal = Calendar.getInstance()
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinutes = p.getInt(KEY_SLEEP_START_HOUR, 22) * 60 + p.getInt(KEY_SLEEP_START_MINUTE, 0)
        val endMinutes = p.getInt(KEY_SLEEP_END_HOUR, 7) * 60 + p.getInt(KEY_SLEEP_END_MINUTE, 0)

        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            // Cruza la medianoche (por ejemplo, de 22:00 a 07:00)
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    /**
     * Comprueba y ejecuta la auto-limpieza del historial 5 minutos antes de la hora de dormir.
     * Evita que el historial crezca durante días y congele la aplicación.
     */
    fun checkAndPerformAutoClear(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_AUTO_CLEAR_BEFORE_SLEEP, true)) return
        if (!p.getBoolean(KEY_SLEEP_SCHEDULE_ENABLED, true)) return

        val cal = Calendar.getInstance()
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinutes = p.getInt(KEY_SLEEP_START_HOUR, 22) * 60 + p.getInt(KEY_SLEEP_START_MINUTE, 0)

        // 5 minutos antes de la hora de inicio de dormir
        val clearThreshold = (startMinutes - 5 + 1440) % 1440
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastCleared = p.getString(KEY_LAST_AUTO_CLEARED_DATE, "")

        // Se ejecuta si estamos en la ventana previa o dentro del inicio de sueño y no se ha purgado hoy
        val isTimeForClear = if (startMinutes >= 5) {
            currentMinutes in clearThreshold..startMinutes
        } else {
            currentMinutes >= clearThreshold || currentMinutes <= startMinutes
        }

        if (isTimeForClear && lastCleared != todayStr) {
            clear(context)
            p.edit().putString(KEY_LAST_AUTO_CLEARED_DATE, todayStr).apply()
        }
    }

    fun formatHourMinute(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(cal.time)
    }

    fun add(context: Context, record: NotificationRecord) {
        synchronized(lock) {
            val p = prefs(context)
            val old = try { JSONArray(p.getString(KEY_EVENTS, "[]") ?: "[]") }
                       catch (_: Exception) { JSONArray() }

            // Purgar notificaciones de más de 24 horas para mantener la base de datos veloz
            val cutoff24h = System.currentTimeMillis() - (24 * 3600 * 1000L)
            val filtered = JSONArray()
            for (i in 0 until old.length()) {
                val item = try { old.getJSONObject(i) } catch (_: Exception) { continue }
                if (item.optLong("postedAt") >= cutoff24h) {
                    filtered.put(item)
                }
            }

            filtered.put(record.toJson())
            val trimmed = JSONArray()
            val start = (filtered.length() - MAX_EVENTS).coerceAtLeast(0)
            for (i in start until filtered.length()) trimmed.put(filtered.getJSONObject(i))
            p.edit().putString(KEY_EVENTS, trimmed.toString()).apply()
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
            val p = prefs(context)
            val raw = p.getString(KEY_EVENTS, "[]") ?: "[]"
            val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
            val remaining = JSONArray()
            for (i in 0 until array.length()) {
                val item = try { array.getJSONObject(i) } catch (_: Exception) { continue }
                if (item.optLong("postedAt") > cutoff) remaining.put(item)
            }
            p.edit()
                .putString(KEY_EVENTS, remaining.toString())
                .putLong(KEY_LAST_SUMMARY_AT, cutoff)
                .apply()
        }
    }

    fun buildSummary(
        context: Context,
        source: List<NotificationRecord>,
        includeTime: Boolean = true,
        intervalMinutes: Long = summaryIntervalMinutes(context)
    ): String {
        val currentTime = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())
        val header = if (includeTime) "Resumen de MFSD Notification, hora $currentTime." else "Resumen de MFSD Notification."
        val period = intervalName(intervalMinutes)
        if (source.isEmpty()) {
            return "$header Durante $period no llegaron notificaciones nuevas."
        }

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

        val opening = "$header En $period se registraron ${records.size} notificaciones en ${byApp.size} aplicaciones."
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
