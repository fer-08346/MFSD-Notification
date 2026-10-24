package com.mfsd.notification

import android.app.Notification
import android.app.Notification.MessagingStyle
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import java.util.Locale

/** Recibe las notificaciones que Android autoriza a esta aplicación a consultar. */
class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.packageName == packageName) return

        val prefs = NotificationStore.prefs(this)
        if (!prefs.getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)) return

        val instantRead = prefs.getBoolean(NotificationStore.KEY_READ_INSTANTLY, true)
        val summaryEnabled = prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)
        if (!instantRead && !summaryEnabled) return
        val readInstantlyForThisApp = instantRead &&
            sbn.packageName in NotificationStore.selectedPackages(this)
        // Las notificaciones permanentes (reproducción, servicios en primer plano, etc.)
        // se excluyen porque pueden actualizarse muchas veces por minuto.
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = sbn.notification.extras
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val rawText = when {
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) != null -> extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            extras?.getCharSequence(Notification.EXTRA_TEXT) != null -> extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            else -> ""
        }.trim()
        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { sbn.packageName }

        val style = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
            else null
        } catch (_: Exception) { null }
        val isWhatsApp = sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b"
        val groupKind = if (isWhatsApp) classifyWhatsApp(style, rawTitle, rawText, appLabel) else -1
        val countFromText = Regex("(?i)\\b(\\d+)\\s+(?:mensajes?|messages?)\\b")
            .find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val messageCount = (countFromText ?: style?.messages?.size ?: 1).coerceIn(1, 999)

        val record = NotificationRecord(
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            packageName = sbn.packageName,
            appName = appLabel,
            title = rawTitle.take(140),
            text = rawText.take(300),
            postedAt = System.currentTimeMillis(),
            whatsappKind = groupKind,
            visibleMessageCount = messageCount
        )

        // Los resúmenes reúnen todas las aplicaciones, no solo las marcadas para lectura inmediata.
        if (summaryEnabled) NotificationStore.add(this, record)
        if (readInstantlyForThisApp) {
            val speech = if (rawTitle.isBlank() && rawText.isBlank()) "$appLabel. Llegó una notificación." else record.spokenText()
            TtsSpeaker.speak(this, speech)
        }
    }

    private fun classifyWhatsApp(style: MessagingStyle?, title: String, text: String, appLabel: String): Int {
        if (style != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return if (style.isGroupConversation) 1 else 0
        }
        if (style?.conversationTitle != null) return 1
        val combined = "$title $text".lowercase(Locale.ROOT)
        if (combined.contains("grupo") || combined.contains("group")) return 1
        // El nombre del chat no basta para diferenciar un contacto de un grupo.
        if (title.isNotBlank() && !title.equals(appLabel, ignoreCase = true) && !title.equals("WhatsApp", ignoreCase = true)) return -1
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return -1
        return -1
    }
}
