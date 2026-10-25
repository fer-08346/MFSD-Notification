package com.mfsd.notification

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Detecta si el usuario pasa la mano o cubre el sensor de proximidad
 * para silenciar de inmediato la lectura de notificaciones en curso.
 */
object ProximitySilencer : SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isListening = false

    fun init(context: Context) {
        if (sensorManager != null) return
        val app = context.applicationContext
        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    fun startListening(context: Context) {
        val app = context.applicationContext
        val enabled = NotificationStore.prefs(app)
            .getBoolean(NotificationStore.KEY_PROXIMITY_SILENCE, true)
        if (!enabled) return

        init(app)
        val sensor = proximitySensor ?: return
        val manager = sensorManager ?: return

        if (!isListening) {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            isListening = true
        }
    }

    fun stopListening() {
        if (isListening) {
            try {
                sensorManager?.unregisterListener(this)
            } catch (_: Exception) { }
            isListening = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values.getOrNull(0) ?: return
            val maxRange = proximitySensor?.maximumRange ?: 5f
            // Se considera tapado si la distancia es menor a 1 cm o menor que la mitad del rango del sensor
            val isCovered = distance < 1f || distance < (maxRange / 2f)
            if (isCovered) {
                // Silenciar inmediatamente la voz
                TtsSpeaker.stop()
                stopListening()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
