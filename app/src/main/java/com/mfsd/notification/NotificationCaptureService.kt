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
        private val recentSpokenByPackage = HashMap<String, LastSpoken>()
        private var lastCallKey = ""
        private var lastCallTime = 0L
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

        // 1. Ejecutar auto-limpieza preventiva si estamos a 5 minutos del modo sueño
        NotificationStore.checkAndPerformAutoClear(this)

        val extras = sbn.notification.extras
        // Omitir notificaciones de reproductores multimedia activos
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

        // 2. DETECCIÓN Y ANUNCIO DE LLAMADAS ENTRANTES (Teléfono y WhatsApp)
        val announceCalls = prefs.getBoolean(NotificationStore.KEY_ANNOUNCE_CALLS, true)
        val isWhatsApp = sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b"
        val isDialer = sbn.packageName.contains("dialer", ignoreCase = true) ||
            sbn.packageName.contains("incallui", ignoreCase = true) ||
            sbn.packageName.contains("telecom", ignoreCase = true)

        val combinedLower = "$rawTitle $rawText".lowercase(Locale.ROOT)
        val isCallCategory = sbn.notification.category == Notification.CATEGORY_CALL
        val hasCallWord = combinedLower.contains("llamada") ||
            combinedLower.contains("incoming call") ||
            combinedLower.contains("videollamada")

        val isIncomingCall = isCallCategory || (isWhatsApp && hasCallWord) || (isDialer && hasCallWord)

        if (isIncomingCall && announceCalls && canSpeakInstantly) {
            // No anunciar si estamos en modo sueño o No Molestar
            if (!isQuietSleepOrDndActive()) {
                val caller = when {
                    rawTitle.isNotBlank() && !rawTitle.equals("WhatsApp", true) && !rawTitle.equals(appLabel, true) -> rawTitle
                    rawText.isNotBlank() && !rawText.contains("llamada", true) -> rawText
                    else -> "desconocido"
                }

                val announcement = when {
                    isWhatsApp -> "Llamada de WhatsApp de $caller"
                    isDialer || isCallCategory -> "Llamada entrante de teléfono de $caller"
                    else -> "Llamada entrante de $appLabel de $caller"
                }

                // Debouncing de llamadas: anunciar cada 7 segundos como máximo para no aturdir ni pelear con el timbre
                val now = System.currentTimeMillis()
                val callKey = "${sbn.packageName}|$caller"
                synchronized(lock) {
                    if (lastCallKey != callKey || (now - lastCallTime) > 7000L) {
                        lastCallKey = callKey
                        lastCallTime = now
                        TtsSpeaker.speakCall(this, announcement)
                    }
                }
            }
            return
        }

        // 3. FLUJO HABITUAL DE NOTIFICACIONES
        val instantRead = prefs.getBoolean(NotificationStore.KEY_READ_INSTANTLY, true)
        val summaryEnabled = prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)
        if (!instantRead && !summaryEnabled) return

        val isGroup = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extras?.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) ?: false
        } else {
            extras?.getBoolean("android.isGroupConversation") ?: false
        }
        val conversationTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val messagesCount = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 1

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
            // Verificar si el usuario está escuchando música
            val pauseOnMusic = prefs.getBoolean(NotificationStore.KEY_PAUSE_ON_MUSIC, true)
            if (pauseOnMusic) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager?.isMusicActive == true) {
                    return
                }
            }

            // Verificar si está en Modo Sueño (por horario programado o por DND nativo)
            if (isQuietSleepOrDndActive()) {
                return
            }

            val mode = prefs.getString(NotificationStore.KEY_INSTANT_MODE, NotificationStore.INSTANT_MODE_FULL)
            val speech = if (mode == NotificationStore.INSTANT_MODE_BRIEF) {
                record.spokenBrief()
            } else {
                record.spokenText()
            }

            // Sistema Anti-Spam y Anti-Repetición
            val antiSpamEnabled = prefs.getBoolean(NotificationStore.KEY_ANTI_SPAM, true)
            if (antiSpamEnabled) {
                val now = System.currentTimeMillis()
                val hash = "${record.title}|${record.text}".trim()
                synchronized(lock) {
                    val last = recentSpokenByPackage[sbn.packageName]
                    if (last != null) {
                        if (last.contentHash == hash && (now - last.timeMs) < 25_000L) {
                            return
                        }
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

    /**
     * Devuelve verdadero si la aplicación debe silenciarse por estar en Modo Sueño
     * (sea por el horario programado o por el modo No Molestar nativo de Android).
     */
    private fun isQuietSleepOrDndActive(): Boolean {
        // 1. Por horario programado de sueño en el reloj
        if (NotificationStore.isSleepTimeNow(this)) return true

        // 2. Por modo No Molestar nativo de Android
        val prefs = NotificationStore.prefs(this)
        if (prefs.getBoolean(NotificationStore.KEY_DND_SLEEP_MODE, true)) {
            try {
                val filter = currentInterruptionFilter
                if (filter != INTERRUPTION_FILTER_ALL && filter != INTERRUPTION_FILTER_UNKNOWN) {
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
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
