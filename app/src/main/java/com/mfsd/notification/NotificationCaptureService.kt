package com.mfsd.notification

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import java.util.Locale

/** Recibe y procesa las notificaciones de Android. */
class NotificationCaptureService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Al conectar el servicio, capturamos las notificaciones ya presentes en el sistema
        // para que no se omitan en el historial ni en los resúmenes.
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

        // Omitir únicamente notificaciones de reproducción multimedia activa
        val extras = sbn.notification.extras
        val isMediaPlayback = extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            sbn.notification.category == Notification.CATEGORY_TRANSPORT
        if (isMediaPlayback) return

        // Extraer título
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()

        // Extraer texto revisando múltiples estilos y campos posibles
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

        // Si no hay ningún contenido textual, omitir
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

        // Registrar para el resumen y el historial
        if (summaryEnabled) NotificationStore.add(this, record)

        // Lectura al instante si la aplicación está seleccionada
        val isAppSelected = sbn.packageName in NotificationStore.selectedPackages(this)
        if (canSpeakInstantly && instantRead && isAppSelected) {
            val mode = prefs.getString(NotificationStore.KEY_INSTANT_MODE, NotificationStore.INSTANT_MODE_FULL)
            val speech = if (mode == NotificationStore.INSTANT_MODE_BRIEF) {
                record.spokenBrief()
            } else {
                record.spokenText()
            }
            TtsSpeaker.speak(this, speech)
        }
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
