package com.mfsd.notification

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Administra una síntesis de voz elegida por la usuaria, independiente del motor de TalkBack. */
object TtsSpeaker {
    private data class QueuedSpeech(
        val text: String,
        val isCall: Boolean = false,
        val onFinished: ((Boolean) -> Unit)? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<QueuedSpeech>()
    private val callbacks = HashMap<String, (Boolean) -> Unit>()
    private val timeoutRunnables = HashMap<String, Runnable>()
    private var tts: TextToSpeech? = null
    private var activeEngine = "__not_initialized__"
    private var ready = false
    private var appContext: Context? = null

    fun speak(context: Context, text: String) {
        if (!NotificationStore.prefs(context).getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)) return
        enqueue(context, text, isCall = false, onFinished = null)
    }

    /**
     * Anuncia una llamada entrante de inmediato con prioridad sobre otras notificaciones,
     * deteniendo la cola anterior y utilizando un canal de audio confortable.
     */
    fun speakCall(context: Context, text: String) {
        if (!NotificationStore.prefs(context).getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)) return
        mainHandler.post {
            // Cancelar notificaciones de lectura que estuvieran pendientes para priorizar la llamada
            pending.clear()
            try { tts?.stop() } catch (_: Exception) { }
            enqueue(context, text, isCall = true, onFinished = null)
        }
    }

    /** Detiene de inmediato la voz actual y vacía la cola. */
    fun stop() {
        mainHandler.post {
            ProximitySilencer.stopListening()
            clearTimeouts()
            val queuedCallbacks = pending.mapNotNull { it.onFinished }
            pending.clear()
            try { tts?.stop() } catch (_: Exception) { }
            val activeCallbacks = callbacks.values.toList()
            callbacks.clear()
            (queuedCallbacks + activeCallbacks).forEach { callback ->
                try { callback(false) } catch (_: Exception) { }
            }
        }
    }

    /** Se usa desde el trabajador de resumen para no marcarlo como leído antes de que termine. */
    fun speakAndWait(context: Context, text: String, timeoutSeconds: Long = 90L): Boolean {
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        enqueue(context, text, isCall = false) { ok ->
            success.set(ok)
            latch.countDown()
        }
        return try {
            latch.await(timeoutSeconds, TimeUnit.SECONDS) && success.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun enqueue(context: Context, text: String, isCall: Boolean, onFinished: ((Boolean) -> Unit)?) {
        val app = context.applicationContext
        appContext = app
        mainHandler.post {
            if (!NotificationStore.prefs(app).getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)) {
                onFinished?.invoke(false)
                return@post
            }

            // Anti-acumulación: si no es llamada y ya hay 2 mensajes esperando, descartamos los viejos
            if (!isCall && pending.size >= 2) {
                val dropped = pending.removeFirst()
                dropped.onFinished?.invoke(false)
            }

            pending.addLast(QueuedSpeech(text, isCall, onFinished))

            // Activar sensor de proximidad mientras hay locución
            ProximitySilencer.startListening(app)

            val selectedEngine = NotificationStore.prefs(app).getString(NotificationStore.KEY_TTS_ENGINE, "") ?: ""
            if (tts == null || selectedEngine != activeEngine || !ready) {
                recreateEngine(app, selectedEngine)
            } else {
                configureAudio(tts!!, app, isCall)
                drainQueue()
            }
        }
    }

    private fun recreateEngine(context: Context, enginePackage: String) {
        ready = false
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
        activeEngine = enginePackage

        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (activeEngine != enginePackage) return@post
                val current = tts ?: return@post
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    val nextIsCall = pending.firstOrNull()?.isCall ?: false
                    configureAudio(current, context, nextIsCall)
                    current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { finishUtterance(utteranceId, true) }
                        @Deprecated("Deprecated in Android API")
                        override fun onError(utteranceId: String?) { finishUtterance(utteranceId, false) }
                        override fun onError(utteranceId: String?, errorCode: Int) { finishUtterance(utteranceId, false) }
                    })
                    drainQueue()
                } else {
                    ready = false
                    val failed = pending.toList()
                    pending.clear()
                    ProximitySilencer.stopListening()
                    failed.forEach { it.onFinished?.invoke(false) }
                }
            }
        }
        tts = if (enginePackage.isBlank()) TextToSpeech(context, listener)
              else TextToSpeech(context, listener, enginePackage)
    }

    private fun configureAudio(engine: TextToSpeech, context: Context, isCall: Boolean) {
        val prefs = NotificationStore.prefs(context)
        val audioMode = if (isCall) {
            prefs.getString(NotificationStore.KEY_CALL_AUDIO_CHANNEL, "call")
        } else {
            prefs.getString(NotificationStore.KEY_AUDIO_MODE, "accessibility")
        }

        val usage = when (audioMode) {
            "call" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            "media" -> AudioAttributes.USAGE_MEDIA
            else -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        }
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) { }

        val colombianSpanish = Locale("es", "CO")
        try {
            val languageResult = engine.setLanguage(colombianSpanish)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("es", "ES"))
            }
        } catch (_: Exception) { }
    }

    private fun drainQueue() {
        val engine = tts ?: return
        if (!ready) return
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            val id = UUID.randomUUID().toString()
            if (item.onFinished != null) callbacks[id] = item.onFinished

            // Watchdog: temporizador de seguridad de 14 segundos para evitar que utterances
            // colgados bloqueen el motor si el servicio de TTS nunca llama a onDone
            val watchdog = Runnable {
                finishUtterance(id, false)
            }
            timeoutRunnables[id] = watchdog
            mainHandler.postDelayed(watchdog, 14000L)

            val result = try {
                engine.speak(item.text, TextToSpeech.QUEUE_ADD, Bundle(), id)
            } catch (_: Exception) {
                TextToSpeech.ERROR
            }

            if (result == TextToSpeech.ERROR) {
                // Auto-recuperación si el motor falló: marcar como no listo para recrearlo limpiamente
                ready = false
                finishUtterance(id, false)
                appContext?.let { recreateEngine(it, activeEngine) }
            }
        }
    }

    private fun finishUtterance(id: String?, successful: Boolean) {
        if (id == null) return
        mainHandler.post {
            timeoutRunnables.remove(id)?.let { mainHandler.removeCallbacks(it) }
            callbacks.remove(id)?.invoke(successful)
            if (callbacks.isEmpty() && pending.isEmpty()) {
                ProximitySilencer.stopListening()
            }
        }
    }

    private fun clearTimeouts() {
        timeoutRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        timeoutRunnables.clear()
    }
}
