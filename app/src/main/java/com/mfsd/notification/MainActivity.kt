package com.mfsd.notification

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private data class AppChoice(val label: String, val packageName: String)
    private data class EngineChoice(val label: String, val packageName: String)

    private lateinit var accessStatus: TextView
    private lateinit var accessButton: Button
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var masterStatus: TextView
    private lateinit var masterControlButton: Button
    private lateinit var appsSummaryStatus: TextView
    private lateinit var summarySwitch: Switch
    private lateinit var summaryIntervalText: TextView
    private lateinit var summaryIntervalSpinner: Spinner
    private lateinit var instantSwitch: Switch
    private lateinit var keepAliveSwitch: Switch
    private var updatingIntervalSpinner = false
    private var appEnabled = true
    private var appChoices: List<AppChoice> = emptyList()
    private var engineChoices: List<EngineChoice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        appEnabled = NotificationStore.prefs(this)
            .getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)

        createInterface()
        loadAppChoices()
        loadEngines()
        refreshAccessStatus()
        refreshBatteryStatus()

        val prefs = NotificationStore.prefs(this)
        if (appEnabled) {
            if (prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)) {
                if (!prefs.getBoolean(NotificationStore.KEY_SCHEDULER_INITIALIZED, false)) {
                    SummaryScheduler.startFromNow(this)
                }
            }
            if (prefs.getBoolean(NotificationStore.KEY_KEEP_ALIVE, true)) {
                KeepAliveService.start(this)
            }
        } else {
            SummaryScheduler.stop(this)
            KeepAliveService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accessStatus.isInitialized) refreshAccessStatus()
        if (::batteryStatus.isInitialized) refreshBatteryStatus()
        loadAppChoices()
    }

    private fun createInterface() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            contentDescription = "Configuración de MFSD Notification"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        scroll.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 35) {
            @Suppress("DEPRECATION")
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottomInset = insets.systemWindowInsetBottom
                view.setPadding(view.paddingLeft, dp(8) + topInset, view.paddingRight, dp(8) + bottomInset)
                insets
            }
            scroll.requestApplyInsets()
        }

        val heading = TextView(this).apply {
            text = "MFSD Notification"
            textSize = 27f
            setTextColor(0xFF382776.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "MFSD Notification, configuración principal"
        }
        root.addView(heading, matchWrap())
        addText(root, "Lee en voz alta las notificaciones que elijas y reúne un resumen periódico. La voz se configura aparte de la que utiliza tu lector de pantalla.", 17f)

        // CONTROL GENERAL
        root.addView(sectionTitle("Control general"))
        masterStatus = addText(root, masterStatusText(), 17f)
        masterControlButton = button(masterButtonText()) {
            setMasterEnabled(!appEnabled)
        }
        masterControlButton.contentDescription = masterButtonText()
        root.addView(masterControlButton, matchWrap())
        addText(root, "Al desactivarla, detiene la voz inmediatamente y pausa la captura y los resúmenes. Al reactivarla, comienza un nuevo periodo.", 15f)

        // PERMISOS DEL SISTEMA
        root.addView(sectionTitle("Permisos del sistema"))
        accessStatus = addText(root, "Acceso a notificaciones: Comprobando...", 16f)
        accessButton = button("Conceder o revisar acceso a notificaciones") {
            openNotificationAccessSettings()
        }
        root.addView(accessButton, matchWrap())

        root.addView(button("Abrir ajustes de texto a voz de Android") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("En Accesibilidad, busca Salida de texto a voz para ver tus motores instalados.")
            } catch (_: Exception) {
                toast("Abre Ajustes de Android y busca Salida de texto a voz.")
            }
        })

        // SEGUNDO PLANO Y BATERÍA
        root.addView(sectionTitle("Segundo plano y confiabilidad"))
        keepAliveSwitch = Switch(this).apply {
            text = "Mantener activa en segundo plano permanentemente"
            textSize = 17f
            minHeight = dp(60)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isChecked = NotificationStore.prefs(this@MainActivity)
                .getBoolean(NotificationStore.KEY_KEEP_ALIVE, true)
            setOnCheckedChangeListener { _, checked ->
                NotificationStore.prefs(this@MainActivity).edit()
                    .putBoolean(NotificationStore.KEY_KEEP_ALIVE, checked).apply()
                if (checked && appEnabled) {
                    KeepAliveService.start(this@MainActivity)
                    toast("Servicio en segundo plano activado.")
                } else {
                    KeepAliveService.stop(this@MainActivity)
                    toast("Servicio en segundo plano detenido.")
                }
            }
        }
        root.addView(keepAliveSwitch, matchWrap())

        batteryStatus = addText(root, "Segundo plano sin restricciones de batería: Comprobando...", 16f)
        batteryButton = button("Permitir segundo plano sin restricciones (batería)") {
            requestBatteryOptimizationExemption()
        }
        root.addView(batteryButton, matchWrap())
        addText(root, "Permite que la app trabaje en segundo plano sin restricciones para que el teléfono no congele las notificaciones al apagar la pantalla.", 15f)

        // MODOS DE LECTURA Y RESUMEN PERSONALIZABLE
        root.addView(sectionTitle("Modos de lectura"))
        instantSwitch = Switch(this).apply {
            text = "Leer al instante las aplicaciones seleccionadas"
            textSize = 17f
            minHeight = dp(60)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isChecked = NotificationStore.prefs(this@MainActivity)
                .getBoolean(NotificationStore.KEY_READ_INSTANTLY, true)
            setOnCheckedChangeListener { _, checked ->
                NotificationStore.prefs(this@MainActivity).edit()
                    .putBoolean(NotificationStore.KEY_READ_INSTANTLY, checked).apply()
            }
        }
        root.addView(instantSwitch, matchWrap())

        root.addView(label("¿Cómo quieres que lea al instante?"))
        val instantModeSpinner = Spinner(this).apply {
            contentDescription = "Detalle de la lectura al instante"
            minimumHeight = dp(52)
        }
        val instantModeOptions = listOf(
            "Notificación completa (aplicación, remitente y mensaje)",
            "Resumen breve (solo aplicación y remitente o título)"
        )
        val instantModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, instantModeOptions)
        instantModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        instantModeSpinner.adapter = instantModeAdapter
        val storedInstantMode = NotificationStore.prefs(this)
            .getString(NotificationStore.KEY_INSTANT_MODE, NotificationStore.INSTANT_MODE_FULL)
        instantModeSpinner.setSelection(if (storedInstantMode == NotificationStore.INSTANT_MODE_BRIEF) 1 else 0, false)
        instantModeSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            val mode = if (position == 1) NotificationStore.INSTANT_MODE_BRIEF else NotificationStore.INSTANT_MODE_FULL
            NotificationStore.prefs(this).edit().putString(NotificationStore.KEY_INSTANT_MODE, mode).apply()
        }
        root.addView(instantModeSpinner, matchWrap())

        summarySwitch = Switch(this).apply {
            text = "Leer resumen periódico de notificaciones"
            textSize = 17f
            minHeight = dp(60)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isChecked = NotificationStore.prefs(this@MainActivity)
                .getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                val prefs = NotificationStore.prefs(this@MainActivity)
                prefs.edit().putBoolean(NotificationStore.KEY_SUMMARY_ENABLED, checked).apply()
                if (checked && appEnabled) SummaryScheduler.startFromNow(this@MainActivity)
                else SummaryScheduler.stop(this@MainActivity)
                val currentMinutes = NotificationStore.summaryIntervalMinutes(this@MainActivity)
                val message = when {
                    checked && appEnabled -> "Resumen activado cada $currentMinutes minutos."
                    checked -> "Resumen activado, pero MFSD Notification está en pausa. Actívala para iniciar."
                    else -> "Resumen desactivado."
                }
                toast(message)
            }
        }
        root.addView(summarySwitch, matchWrap())

        summaryIntervalText = addText(root, summaryIntervalDescription(), 16f)
        root.addView(label("Frecuencia del resumen:"))
        summaryIntervalSpinner = Spinner(this).apply {
            contentDescription = "Frecuencia para reproducir el resumen periódico"
            minimumHeight = dp(52)
        }
        val intervalOptions = listOf(
            "Cada 5 minutos",
            "Cada 15 minutos",
            "Cada 30 minutos (predeterminado)",
            "Cada 60 minutos (1 hora)",
            "Tiempo personalizado en minutos..."
        )
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalOptions)
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        summaryIntervalSpinner.adapter = intervalAdapter
        syncIntervalSpinnerSelection()

        summaryIntervalSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (updatingIntervalSpinner) return@SimpleItemSelectedListener
            when (position) {
                0 -> applySummaryInterval(5L)
                1 -> applySummaryInterval(15L)
                2 -> applySummaryInterval(30L)
                3 -> applySummaryInterval(60L)
                4 -> showCustomIntervalDialog()
            }
        }
        root.addView(summaryIntervalSpinner, matchWrap())

        // COMPORTAMIENTO INTELIGENTE Y COMODIDAD
        root.addView(sectionTitle("Comportamiento inteligente"))
        addText(root, "Ajustes para evitar interrupciones incómodas y lecturas repetitivas.", 15f)

        val musicSwitch = Switch(this).apply {
            text = "No interrumpir si estoy escuchando música"
            textSize = 17f
            minHeight = dp(60)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isChecked = NotificationStore.prefs(this@MainActivity)
                .getBoolean(NotificationStore.KEY_PAUSE_ON_MUSIC, true)
            setOnCheckedChangeListener { _, checked ->
                NotificationStore.prefs(this@MainActivity).edit()
                    .putBoolean(NotificationStore.KEY_PAUSE_ON_MUSIC, checked).apply()
            }
        }
        root.addView(musicSwitch, matchWrap())

        val dndSwitch = Switch(this).apply {
            text = "Pausar en Modo Sueño o No Molestar"
            textSize = 17f
            minHeight = dp(60)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isChecked = NotificationStore.prefs(this@MainActivity)
                .getBoolean(NotificationStore.KEY_DND_SLEEP_MODE, true)
            setOnCheckedChangeListener { _, checked ->
                NotificationStore.prefs(this@MainActivity).edit()
                    .putBoolean(NotificationStore.KEY_DND_SLEEP_MODE, checked).apply()
            }
        }
        root.addView(dndSwitch, matchWrap())

        val proximitySwitch = Switch(this).apply {
            text = "Silenciar pasando la mano (sensor de proximidad)"
            textSize = 17f
            minHeight = dp(60)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isChecked = NotificationStore.prefs(this@MainActivity)
                .getBoolean(NotificationStore.KEY_PROXIMITY_SILENCE, true)
            setOnCheckedChangeListener { _, checked ->
                NotificationStore.prefs(this@MainActivity).edit()
                    .putBoolean(NotificationStore.KEY_PROXIMITY_SILENCE, checked).apply()
            }
        }
        root.addView(proximitySwitch, matchWrap())

        val antiSpamSwitch = Switch(this).apply {
            text = "Evitar repeticiones y avisos seguidos (anti-spam)"
            textSize = 17f
            minHeight = dp(60)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isChecked = NotificationStore.prefs(this@MainActivity)
                .getBoolean(NotificationStore.KEY_ANTI_SPAM, true)
            setOnCheckedChangeListener { _, checked ->
                NotificationStore.prefs(this@MainActivity).edit()
                    .putBoolean(NotificationStore.KEY_ANTI_SPAM, checked).apply()
            }
        }
        root.addView(antiSpamSwitch, matchWrap())

        // CONFIGURACIÓN DE VOZ
        root.addView(sectionTitle("Voz independiente"))
        addText(root, "Selecciona el motor de voz deseado (como Eloquence, Vocalizer o Samsung) y el volumen a emplear.", 15f)
        root.addView(label("Motor de voz para MFSD Notification"))
        val engineSpinner = Spinner(this).apply {
            tag = "engine_spinner"
            contentDescription = "Motor de voz para las notificaciones"
            minimumHeight = dp(52)
        }
        root.addView(engineSpinner, matchWrap())

        root.addView(label("Volumen que debe utilizar la voz"))
        val audioSpinner = Spinner(this).apply {
            tag = "audio_spinner"
            contentDescription = "Canal de audio de la voz"
            minimumHeight = dp(52)
        }
        root.addView(audioSpinner, matchWrap())

        root.addView(button("Probar la voz seleccionada") {
            if (!appEnabled) {
                toast("MFSD Notification está desactivada. Actívala para probar la voz.")
            } else {
                TtsSpeaker.speak(this, "Esta es una prueba de voz de MFSD Notification. Si escuchas este mensaje, el motor funciona correctamente.")
                toast("Prueba de voz enviada.")
            }
        })

        // APLICACIONES PARA LECTURA INMEDIATA
        root.addView(sectionTitle("Aplicaciones para lectura inmediata"))
        addText(root, "Elige las aplicaciones que se leerán al instante cuando llegue un aviso. El resumen periódico reunirá todas las aplicaciones del teléfono.", 15f)

        appsSummaryStatus = addText(root, "Cargando aplicaciones...", 16f)

        root.addView(button("Elegir aplicaciones para lectura inmediata...") {
            showAppSelectionDialog()
        })
        root.addView(button("Seleccionar todas las aplicaciones") {
            NotificationStore.saveSelectedPackages(this, appChoices.map { it.packageName }.toSet())
            updateAppSummaryText()
            toast("Todas las aplicaciones se leerán al instante.")
        })
        root.addView(button("Desmarcar todas las aplicaciones") {
            NotificationStore.saveSelectedPackages(this, emptySet())
            updateAppSummaryText()
            toast("No se leerán avisos al instante; se reservan para el resumen.")
        })

        // HISTORIAL Y REVISIÓN MANUAL
        root.addView(sectionTitle("Historial y lectura manual"))
        addText(root, "Consulta todas las notificaciones recibidas para recorrerlas una por una o escucharlas.", 15f)

        root.addView(button("Ver historial de notificaciones recibidas") {
            showHistoryDialog()
        })
        root.addView(button("Leer muestra del resumen en voz alta") {
            if (!appEnabled) {
                toast("MFSD Notification está desactivada. Actívala para escuchar una muestra.")
            } else {
                val now = System.currentTimeMillis()
                val interval = NotificationStore.summaryIntervalMinutes(this)
                val from = NotificationStore.prefs(this).getLong(
                    NotificationStore.KEY_LAST_SUMMARY_AT,
                    now - interval * 60 * 1000L
                )
                val recent = NotificationStore.readAll(this).filter { it.postedAt > from && it.postedAt <= now }
                val message = NotificationStore.buildSummary(this, recent, intervalMinutes = interval)
                TtsSpeaker.speak(this, message)
                toast("Reproduciendo muestra de resumen.")
            }
        })
        root.addView(button("Borrar historial guardado") {
            NotificationStore.clear(this)
            toast("Historial borrado del teléfono.")
        })

        addText(root, "Privacidad: el contenido de las notificaciones se procesa exclusivamente en tu teléfono, sin conexión a Internet.", 14f)

        // Listeners para spinners de voz y audio
        engineSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (position in engineChoices.indices) {
                NotificationStore.prefs(this).edit()
                    .putString(NotificationStore.KEY_TTS_ENGINE, engineChoices[position].packageName)
                    .apply()
            }
        }
        audioSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            val mode = when (position) {
                1 -> "call"
                2 -> "media"
                else -> "accessibility"
            }
            NotificationStore.prefs(this).edit().putString(NotificationStore.KEY_AUDIO_MODE, mode).apply()
        }
    }

    private fun summaryIntervalDescription(): String {
        val minutes = NotificationStore.summaryIntervalMinutes(this)
        return "Frecuencia actual del resumen: Cada $minutes minutos."
    }

    private fun syncIntervalSpinnerSelection() {
        if (!::summaryIntervalSpinner.isInitialized) return
        val currentMinutes = NotificationStore.summaryIntervalMinutes(this)
        updatingIntervalSpinner = true
        val index = when (currentMinutes) {
            5L -> 0
            15L -> 1
            30L -> 2
            60L -> 3
            else -> 4
        }
        summaryIntervalSpinner.setSelection(index, false)
        updatingIntervalSpinner = false
    }

    private fun applySummaryInterval(minutes: Long) {
        NotificationStore.setSummaryIntervalMinutes(this, minutes)
        if (::summaryIntervalText.isInitialized) {
            summaryIntervalText.text = summaryIntervalDescription()
            summaryIntervalText.contentDescription = summaryIntervalDescription()
            summaryIntervalText.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        val summaryEnabled = NotificationStore.prefs(this)
            .getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)
        if (appEnabled && summaryEnabled) {
            SummaryScheduler.startFromNow(this)
        }
        toast("Frecuencia del resumen configurada: Cada $minutes minutos.")
    }

    private fun showCustomIntervalDialog() {
        val current = NotificationStore.summaryIntervalMinutes(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            hint = "Escribe los minutos (ej. 20)"
            contentDescription = "Campo de texto para escribir el intervalo del resumen en minutos"
            selectAll()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(10), dp(24), dp(10))
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(this)
            .setTitle("Frecuencia personalizada")
            .setMessage("Escribe cada cuántos minutos deseas que se reproduzca el resumen de notificaciones:")
            .setView(container)
            .setPositiveButton("Guardar") { dialog, _ ->
                val entered = input.text.toString().trim().toLongOrNull()
                if (entered != null && entered >= 1L) {
                    applySummaryInterval(entered)
                    syncIntervalSpinnerSelection()
                    dialog.dismiss()
                } else {
                    toast("Por favor ingresa un número válido de al menos 1 minuto.")
                    syncIntervalSpinnerSelection()
                }
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                syncIntervalSpinnerSelection()
                dialog.dismiss()
            }
            .setOnCancelListener {
                syncIntervalSpinnerSelection()
            }
            .show()
    }

    private fun loadAppChoices() {
        val combinedChoices = LinkedHashMap<String, AppChoice>()
        findLaunchableApps().forEach { combinedChoices[it.packageName] = it }
        NotificationStore.readAll(this).forEach { record ->
            if (record.packageName != packageName && !combinedChoices.containsKey(record.packageName)) {
                combinedChoices[record.packageName] = AppChoice(record.appName.ifBlank { record.packageName }, record.packageName)
            }
        }
        appChoices = combinedChoices.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

        val prefs = NotificationStore.prefs(this)
        val stored = prefs.getStringSet(NotificationStore.KEY_SELECTED_APPS, null)
        if (stored == null) {
            val defaults = appChoices.filter { it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b" }
                .map { it.packageName }.toSet()
            NotificationStore.saveSelectedPackages(this, defaults)
        }
        updateAppSummaryText()
    }

    private fun updateAppSummaryText() {
        if (!::appsSummaryStatus.isInitialized) return
        val selected = NotificationStore.selectedPackages(this)
        val total = appChoices.size
        val count = selected.intersect(appChoices.map { it.packageName }.toSet()).size
        appsSummaryStatus.text = "Aplicaciones marcadas para lectura inmediata: $count de $total disponibles."
        appsSummaryStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun showAppSelectionDialog() {
        if (appChoices.isEmpty()) {
            toast("No hay aplicaciones disponibles en este momento.")
            return
        }
        val currentSelected = NotificationStore.selectedPackages(this).toMutableSet()
        val labels = appChoices.map { it.label }.toTypedArray()
        val checked = appChoices.map { it.packageName in currentSelected }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Seleccionar aplicaciones para lectura inmediata")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = appChoices[which].packageName
                if (isChecked) currentSelected.add(pkg) else currentSelected.remove(pkg)
            }
            .setPositiveButton("Guardar") { dialog, _ ->
                NotificationStore.saveSelectedPackages(this, currentSelected)
                updateAppSummaryText()
                toast("Preferencias de aplicaciones guardadas.")
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showHistoryDialog() {
        val records = NotificationStore.readAll(this).reversed()
        if (records.isEmpty()) {
            toast("El historial está vacío. No hay notificaciones guardadas todavía.")
            return
        }
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
        val items = records.map { r ->
            val time = timeFormat.format(Date(r.postedAt))
            val titlePart = if (r.title.isNotBlank()) " | ${r.title}" else ""
            val bodyPart = if (r.text.isNotBlank()) ": ${r.text}" else ""
            "[$time] ${r.appName}$titlePart$bodyPart"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Historial: ${records.size} notificaciones (pulsa una para escucharla)")
            .setItems(items) { _, which ->
                val selected = records[which]
                TtsSpeaker.speak(this, selected.spokenText())
                toast("Leyendo notificación de ${selected.appName}")
            }
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast("Busca MFSD Notification y activa el interruptor de permiso.")
        } catch (_: Exception) {
            toast("Abre Ajustes de Android > Notificaciones > Acceso a notificaciones.")
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    toast("Selecciona Permitir para evitar que el sistema congele la aplicación.")
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        toast("En la lista, desactiva la optimización para MFSD Notification.")
                    } catch (_: Exception) {
                        toast("En Ajustes > Batería desactiva la optimización para MFSD Notification.")
                    }
                }
            } else {
                toast("Segundo plano sin restricciones de batería ya está Permitido.")
                refreshBatteryStatus()
            }
        } else {
            toast("Tu versión de Android no requiere esta configuración de batería.")
        }
    }

    private fun refreshAccessStatus() {
        val expected = ComponentName(this, NotificationCaptureService::class.java)
        val raw = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val enabled = raw.split(":").any { item ->
            val component = ComponentName.unflattenFromString(item)
            component != null && component.packageName == expected.packageName && component.className == expected.className
        }
        if (enabled) {
            accessStatus.text = "Acceso a notificaciones: Permitido. MFSD Notification puede capturar avisos."
            if (::accessButton.isInitialized) {
                accessButton.text = "Acceso a notificaciones: Permitido (toca para revisar)"
                accessButton.contentDescription = "Acceso a notificaciones: Permitido. Toca para revisar ajustes del sistema."
            }
        } else {
            accessStatus.text = "Acceso a notificaciones: No permitido. Pulsa el botón para conceder acceso."
            if (::accessButton.isInitialized) {
                accessButton.text = "Conceder acceso a notificaciones"
                accessButton.contentDescription = "Conceder acceso a notificaciones. Actualmente no permitido."
            }
        }
        accessStatus.contentDescription = accessStatus.text
        accessStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun refreshBatteryStatus() {
        val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } else true

        if (isIgnoring) {
            batteryStatus.text = "Segundo plano sin restricciones de batería: Permitido."
            if (::batteryButton.isInitialized) {
                batteryButton.text = "Batería: Permitido (toca para revisar)"
                batteryButton.contentDescription = "Segundo plano sin restricciones de batería: Permitido. Toca para revisar ajustes de batería."
            }
        } else {
            batteryStatus.text = "Segundo plano sin restricciones de batería: No permitido (restringido por ahorro de energía)."
            if (::batteryButton.isInitialized) {
                batteryButton.text = "Permitir segundo plano sin restricciones (batería)"
                batteryButton.contentDescription = "Permitir segundo plano sin restricciones de batería. Actualmente no permitido."
            }
        }
        batteryStatus.contentDescription = batteryStatus.text
        batteryStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun findLaunchableApps(): List<AppChoice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
        } catch (_: Exception) { emptyList() }
        val choices = LinkedHashMap<String, AppChoice>()
        for (resolve in results) {
            val pkg = resolve.activityInfo?.packageName ?: continue
            if (pkg == packageName) continue
            val label = try { resolve.loadLabel(packageManager).toString().trim() } catch (_: Exception) { pkg }
            if (label.isNotBlank() && !choices.containsKey(pkg)) choices[pkg] = AppChoice(label, pkg)
        }
        return choices.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun loadEngines() {
        val spinner = findSpinner("engine_spinner") ?: return
        val audioSpinner = findSpinner("audio_spinner") ?: return
        val temporary = try { TextToSpeech(this) { } } catch (_: Exception) { null }
        val installed = try { temporary?.engines ?: emptyList() } catch (_: Exception) { emptyList() }
        try { temporary?.shutdown() } catch (_: Exception) { }

        engineChoices = mutableListOf(EngineChoice("Predeterminado del sistema", "")).apply {
            installed.sortedBy { it.label.lowercase(Locale.ROOT) }.forEach { engine ->
                if (engine.name.isNotBlank()) add(EngineChoice(engine.label ?: engine.name, engine.name))
            }
        }.distinctBy { it.packageName }

        val engineAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engineChoices.map { it.label })
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = engineAdapter
        val storedEngine = NotificationStore.prefs(this).getString(NotificationStore.KEY_TTS_ENGINE, "") ?: ""
        val enginePosition = engineChoices.indexOfFirst { it.packageName == storedEngine }.coerceAtLeast(0)
        spinner.setSelection(enginePosition, false)

        val audioOptions = listOf("Volumen de accesibilidad", "Volumen de llamada", "Volumen multimedia")
        val audioAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audioOptions)
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioSpinner.adapter = audioAdapter
        val storedAudio = NotificationStore.prefs(this).getString(NotificationStore.KEY_AUDIO_MODE, "accessibility")
        audioSpinner.setSelection(when (storedAudio) { "call" -> 1; "media" -> 2; else -> 0 }, false)
    }

    private fun findSpinner(tagValue: String): Spinner? {
        val root = findViewById<View>(android.R.id.content) ?: return null
        return findViewWithTag(root, tagValue) as? Spinner
    }

    private fun findViewWithTag(view: View, tagValue: String): View? {
        if (view.tag == tagValue) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewWithTag(view.getChildAt(i), tagValue)
                if (found != null) return found
            }
        }
        return null
    }

    private fun masterStatusText(): String = if (appEnabled) {
        "Estado general: activada. Supervisando notificaciones."
    } else {
        "Estado general: desactivada (en pausa). No se leerá nada."
    }

    private fun masterButtonText(): String = if (appEnabled) {
        "Desactivar MFSD Notification"
    } else {
        "Activar MFSD Notification"
    }

    private fun setMasterEnabled(enabled: Boolean) {
        appEnabled = enabled
        NotificationStore.prefs(this).edit()
            .putBoolean(NotificationStore.KEY_MASTER_ENABLED, enabled)
            .apply()

        if (enabled) {
            val summaryEnabled = NotificationStore.prefs(this)
                .getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)
            if (summaryEnabled) SummaryScheduler.startFromNow(this) else SummaryScheduler.stop(this)
            if (NotificationStore.prefs(this).getBoolean(NotificationStore.KEY_KEEP_ALIVE, true)) {
                KeepAliveService.start(this)
            }
            toast("MFSD Notification activada.")
        } else {
            NotificationStore.moveSummaryCutoffToNow(this)
            SummaryScheduler.stop(this)
            KeepAliveService.stop(this)
            TtsSpeaker.stop()
            toast("MFSD Notification desactivada. Se detuvo la voz y las tareas de fondo.")
        }

        masterStatus.text = masterStatusText()
        masterStatus.contentDescription = masterStatusText()
        masterStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        masterControlButton.text = masterButtonText()
        masterControlButton.contentDescription = masterButtonText()
        masterControlButton.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 21f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xFF382776.toInt())
        setPadding(0, dp(24), 0, dp(8))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun addText(parent: LinearLayout, text: String, size: Float): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFF202020.toInt())
            setPadding(0, dp(7), 0, dp(7))
            setLineSpacing(dp(2).toFloat(), 1.05f)
        }
        parent.addView(view, matchWrap())
        return view
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 16f
        minHeight = dp(54)
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

private class SimpleItemSelectedListener(val onSelected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected(position)
    }
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
