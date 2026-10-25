package com.mfsd.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val SUMMARY_WORK_NAME = "mfsd-notification-half-hour-summary"

object SummaryScheduler {
    fun startFromNow(context: Context) {
        val prefs = NotificationStore.prefs(context)
        if (!prefs.getBoolean(NotificationStore.KEY_MASTER_ENABLED, true) ||
            !prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)) {
            stop(context)
            return
        }
        prefs.edit()
            .putLong(NotificationStore.KEY_LAST_SUMMARY_AT, System.currentTimeMillis())
            .putBoolean(NotificationStore.KEY_SCHEDULER_INITIALIZED, true)
            .apply()
        WorkManager.getInstance(context).cancelUniqueWork(SUMMARY_WORK_NAME)
        val first = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SUMMARY_WORK_NAME, ExistingWorkPolicy.REPLACE, first)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SUMMARY_WORK_NAME)
        NotificationStore.prefs(context).edit()
            .putBoolean(NotificationStore.KEY_SCHEDULER_INITIALIZED, false)
            .apply()
    }

    fun enqueueNext(context: Context) {
        val prefs = NotificationStore.prefs(context)
        if (!prefs.getBoolean(NotificationStore.KEY_MASTER_ENABLED, true) ||
            !prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)) return
        val next = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SUMMARY_WORK_NAME, ExistingWorkPolicy.APPEND, next)
    }
}

class SummaryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        val prefs = NotificationStore.prefs(app)
        if (!prefs.getBoolean(NotificationStore.KEY_MASTER_ENABLED, true) ||
            !prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)) return Result.success()

        // Se programa al comenzar el ciclo para conservar una cadencia cercana a 30 minutos.
        try { SummaryScheduler.enqueueNext(app) } catch (_: Exception) { }

        // Si el usuario está escuchando música, no interrumpir con el resumen
        val pauseOnMusic = prefs.getBoolean(NotificationStore.KEY_PAUSE_ON_MUSIC, true)
        val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (pauseOnMusic && audioManager?.isMusicActive == true) {
            return Result.success()
        }

        // Si el teléfono está en Modo Sueño o No Molestar, no emitir sonido
        val dndSleepEnabled = prefs.getBoolean(NotificationStore.KEY_DND_SLEEP_MODE, true)
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (dndSleepEnabled && notificationManager != null) {
            val filter = notificationManager.currentInterruptionFilter
            if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                return Result.success()
            }
        }

        try {
            val cutoff = System.currentTimeMillis()
            val last = prefs.getLong(NotificationStore.KEY_LAST_SUMMARY_AT, cutoff - TimeUnit.MINUTES.toMillis(30))
            val events = NotificationStore.readAll(app).filter { it.postedAt > last && it.postedAt <= cutoff }
            val summary = NotificationStore.buildSummary(app, events)
            val spoken = TtsSpeaker.speakAndWait(app, summary)
            if (spoken) NotificationStore.finishSummary(app, cutoff)
        } catch (_: Exception) {
            // Un fallo en un resumen no debe detener la cadena
        }
        return Result.success()
    }
}
