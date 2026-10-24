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
    private data class QueuedSpeech(val text: String, val onFinished: ((Boolean) -> Unit)?)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<QueuedSpeech>()
    private val callbacks = HashMap<String, (Boolean) -> Unit>()
    private var tts: TextToSpeech? = null
    private var activeEngine = "__not_initialized__"
    private var ready = false

    fun speak(context: Context, text: String) {
        if (!NotificationStore.prefs(context).getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)) return
        enqueue(context, text, null)
    }

    /** Detiene de inmediato la voz actual y vacía la cola cuando se desactiva la aplicación. */
    fun stop() {
        mainHandler.post {
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
        enqueue(context, text) { ok ->
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

    private fun enqueue(context: Context, text: String, onFinished: ((Boolean) -> Unit)?) {
        val app = context.applicationContext
        mainHandler.post {
            if (!NotificationStore.prefs(app).getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)) {
                onFinished?.invoke(false)
                return@post
            }
            pending.addLast(QueuedSpeech(text, onFinished))
            val selectedEngine = NotificationStore.prefs(app).getString(NotificationStore.KEY_TTS_ENGINE, "") ?: ""
            if (tts == null || selectedEngine != activeEngine) {
                recreateEngine(app, selectedEngine)
            } else if (ready) {
                tts?.let { configure(it, app) }
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
                    configure(current, context)
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
                    failed.forEach { it.onFinished?.invoke(false) }
                }
            }
        }
        tts = if (enginePackage.isBlank()) TextToSpeech(context, listener)
              else TextToSpeech(context, listener, enginePackage)
    }

    private fun configure(engine: TextToSpeech, context: Context) {
        val audioMode = NotificationStore.prefs(context).getString(NotificationStore.KEY_AUDIO_MODE, "accessibility")
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
            val result = try {
                engine.speak(item.text, TextToSpeech.QUEUE_ADD, Bundle(), id)
            } catch (_: Exception) { TextToSpeech.ERROR }
            if (result == TextToSpeech.ERROR) finishUtterance(id, false)
            else if (item.onFinished == null) {
                // No se necesita callback, pero el motor puede continuar hablando en cola.
            }
        }
    }

    private fun finishUtterance(id: String?, successful: Boolean) {
        if (id == null) return
        mainHandler.post { callbacks.remove(id)?.invoke(successful) }
    }
}
