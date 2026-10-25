package com.mfsd.notification

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import java.util.Locale

/** Recibe y procesa las notificaciones de Android de forma inteligente. */
class NotificationCaptureService : NotificationListenerService() {

    private data class LastSpoken(val contentHash: String, val timeMs: Long)

    companion object {
        // Guarda el último contenido hablado por aplicación para evitar repeticiones (ej. Telegram o WhatsApp)
        private val recentSpokenByPackage = HashMap<String, LastSpoken>()
        private val lock = Any()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn, canSpeakInstantly = false)
            }
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        processNotification(sbn, canSpeakInstantly = true)
    }

    private fun processNotification(sbn: StatusBarNotification, canSpeakInstantly: Boolean) {
        if (sbn.packageName == packageName) return

        val prefs = NotificationStore.prefs(this)
        if (!prefs.getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)) return

        val instantRead = prefs.getBoolean(NotificationStore.KEY_READ_INSTANTLY, true)
        val summaryEnabled = prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)
        if (!instantRead && !summaryEnabled) return

        // Omitir notificaciones de reproductores de música activa
        val extras = sbn.notification.extras
        val isMediaPlayback = extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            sbn.notification.category == Notification.CATEGORY_TRANSPORT
        if (isMediaPlayback) return

        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()

        val rawText = when {
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) != null ->
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            extras?.getCharSequence(Notification.EXTRA_TEXT) != null ->
                extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) != null -> {
                val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                lines?.filterNotNull()?.takeLast(3)?.joinToString(". ") { it.toString() } ?: ""
            }
            extras?.getCharSequence(Notification.EXTRA_SUB_TEXT) != null ->
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()
            extras?.getCharSequence(Notification.EXTRA_INFO_TEXT) != null ->
                extras.getCharSequence(Notification.EXTRA_INFO_TEXT).toString()
            else -> ""
        }.trim()

        if (rawTitle.isBlank() && rawText.isBlank()) return

        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { sbn.packageName }

        val isGroup = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extras?.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) ?: false
        } else {
            extras?.getBoolean("android.isGroupConversation") ?: false
        }
        val conversationTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val messagesCount = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 1

        val isWhatsApp = sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b"
        val groupKind = if (isWhatsApp) classifyWhatsApp(isGroup, conversationTitle, rawTitle, rawText, appLabel) else -1
        val countFromText = Regex("(?i)\\b(\\d+)\\s+(?:mensajes?|messages?)\\b")
            .find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val messageCount = (countFromText ?: messagesCount).coerceIn(1, 999)

        val record = NotificationRecord(
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            packageName = sbn.packageName,
            appName = appLabel,
            title = rawTitle.take(140),
            text = rawText.take(400),
            postedAt = System.currentTimeMillis(),
            whatsappKind = groupKind,
            visibleMessageCount = messageCount
        )

        // Registrar en el historial para los resúmenes y lectura manual
        if (summaryEnabled) NotificationStore.add(this, record)

        // Lectura al instante
        val isAppSelected = sbn.packageName in NotificationStore.selectedPackages(this)
        if (canSpeakInstantly && instantRead && isAppSelected) {
            // 1. Detectar si el usuario está escuchando música
            val pauseOnMusic = prefs.getBoolean(NotificationStore.KEY_PAUSE_ON_MUSIC, true)
            if (pauseOnMusic) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager?.isMusicActive == true) {
                    return // No interrumpir la música con la voz
                }
            }

            // 2. Detectar si el teléfono está en Modo Sueño o No Molestar (DND)
            val dndSleepEnabled = prefs.getBoolean(NotificationStore.KEY_DND_SLEEP_MODE, true)
            if (dndSleepEnabled && isSleepOrDndActive()) {
                return // Respetar el descanso y el silencio
            }

            val mode = prefs.getString(NotificationStore.KEY_INSTANT_MODE, NotificationStore.INSTANT_MODE_FULL)
            val speech = if (mode == NotificationStore.INSTANT_MODE_BRIEF) {
                record.spokenBrief()
            } else {
                record.spokenText()
            }

            // 3. Sistema Anti-Spam y Anti-Repetición (para Telegram, chats activos, etc.)
            val antiSpamEnabled = prefs.getBoolean(NotificationStore.KEY_ANTI_SPAM, true)
            if (antiSpamEnabled) {
                val now = System.currentTimeMillis()
                val hash = "${record.title}|${record.text}".trim()
                synchronized(lock) {
                    val last = recentSpokenByPackage[sbn.packageName]
                    if (last != null) {
                        // Si es exactamente el mismo texto hace menos de 25 segundos, no repetir
                        if (last.contentHash == hash && (now - last.timeMs) < 25_000L) {
                            return
                        }
                        // Si llegan mensajes en ráfaga (menos de 2.5 segundos) de la misma app, no aturdir
                        if ((now - last.timeMs) < 2500L) {
                            return
                        }
                    }
                    recentSpokenByPackage[sbn.packageName] = LastSpoken(hash, now)
                }
            }

            TtsSpeaker.speak(this, speech)
        }
    }

    private fun isSleepOrDndActive(): Boolean {
        return try {
            val filter = currentInterruptionFilter
            filter != INTERRUPTION_FILTER_ALL && filter != INTERRUPTION_FILTER_UNKNOWN
        } catch (_: Exception) { false }
    }

    private fun classifyWhatsApp(isGroup: Boolean, conversationTitle: String?, title: String, text: String, appLabel: String): Int {
        if (isGroup || !conversationTitle.isNullOrBlank()) return 1
        val combined = "$title $text".lowercase(Locale.ROOT)
        if (combined.contains("grupo") || combined.contains("group")) return 1
        if (title.isNotBlank() && !title.equals(appLabel, ignoreCase = true) && !title.equals("WhatsApp", ignoreCase = true)) return 0
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return -1
        return -1
    }
}
