package com.mfsd.notification

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : Activity() {
    private data class AppChoice(val label: String, val packageName: String)
    private data class EngineChoice(val label: String, val packageName: String)

    private lateinit var accessStatus: TextView
    private lateinit var masterStatus: TextView
    private lateinit var masterControlButton: Button
    private lateinit var selectedAppsContainer: LinearLayout
    private lateinit var summarySwitch: Switch
    private lateinit var instantSwitch: Switch
    private var appEnabled = true
    private lateinit var appChoices: List<AppChoice>
    private lateinit var engineChoices: List<EngineChoice>
    private var buildingAppList = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        appEnabled = NotificationStore.prefs(this)
            .getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)
        createInterface()
        loadAppChoicesAndChecks()
        loadEngines()
        refreshAccessStatus()

        val prefs = NotificationStore.prefs(this)
        if (appEnabled && prefs.getBoolean(NotificationStore.KEY_SUMMARY_ENABLED, true)) {
            if (!prefs.getBoolean(NotificationStore.KEY_SCHEDULER_INITIALIZED, false)) {
                SummaryScheduler.startFromNow(this)
            }
        } else {
            SummaryScheduler.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accessStatus.isInitialized) refreshAccessStatus()
        if (::selectedAppsContainer.isInitialized) loadAppChoicesAndChecks()
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
        addText(root, "Lee en voz alta las notificaciones que elijas y reúne un resumen cada media hora. La voz se configura aparte de la que utiliza tu lector de pantalla.", 17f)
        addText(root, "La aplicación usa la fecha y hora del teléfono. Los resúmenes pueden retrasarse si Android limita la actividad en segundo plano.", 15f)

        root.addView(sectionTitle("Control general"))
        masterStatus = addText(root, masterStatusText(), 17f)
        masterControlButton = button(masterButtonText()) {
            setMasterEnabled(!appEnabled)
        }
        masterControlButton.contentDescription = masterButtonText()
        root.addView(masterControlButton, matchWrap())
        addText(root, "Al desactivarla, detiene la lectura que esté sonando y pausa tanto la lectura inmediata como los resúmenes. Al volver a activarla, empieza un nuevo periodo: no leerá notificaciones anteriores a la pausa.", 15f)

        root.addView(sectionTitle("Permisos"))
        accessStatus = addText(root, "Estado del acceso a notificaciones: comprobando.", 16f)
        root.addView(button("Conceder o revisar acceso a notificaciones") {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                toast("No pude abrir ese ajuste. Búscalo en Ajustes de Android, acceso a notificaciones.")
            }
        })
        root.addView(button("Abrir ajustes de texto a voz de Android") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("En Accesibilidad, busca Salida de texto a voz para instalar o configurar motores de voz.")
            } catch (_: Exception) {
                toast("Abre Ajustes de Android y busca Salida de texto a voz.")
            }
        })

        root.addView(sectionTitle("Cómo quieres recibir las notificaciones"))
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

        summarySwitch = Switch(this).apply {
            text = "Leer un resumen cada 30 minutos"
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
                val message = when {
                    checked && appEnabled -> "Resumen activado. El próximo se intentará dentro de 30 minutos."
                    checked -> "El resumen está seleccionado, pero MFSD Notification está desactivada. Actívala para que funcione."
                    else -> "Resumen desactivado."
                }
                toast(message)
            }
        }
        root.addView(summarySwitch, matchWrap())

        root.addView(sectionTitle("Voz independiente"))
        addText(root, "Motor de síntesis de voz. Solo aparecen motores instalados que Android permite usar como texto a voz. Eloquence aparecerá si está instalado y registrado como motor.", 15f)
        root.addView(label("Motor de voz para MFSD Notification"))
        val engineSpinner = Spinner(this).apply {
            contentDescription = "Motor de voz para las notificaciones de MFSD Notification"
            minimumHeight = dp(52)
        }
        root.addView(engineSpinner, matchWrap())
        root.addView(label("Volumen que debe utilizar la voz"))
        val audioSpinner = Spinner(this).apply {
            contentDescription = "Tipo de volumen de la voz de las notificaciones"
            minimumHeight = dp(52)
        }
        root.addView(audioSpinner, matchWrap())
        root.addView(button("Probar la voz elegida") {
            if (!appEnabled) {
                toast("MFSD Notification está desactivada. Actívala para probar la voz.")
            } else {
                TtsSpeaker.speak(this, "Esta es una prueba de voz de MFSD Notification. Si escuchas este mensaje, el motor está respondiendo.")
                toast("Prueba de voz enviada.")
            }
        })

        root.addView(sectionTitle("Aplicaciones para lectura inmediata"))
        addText(root, "Marca las aplicaciones cuyas notificaciones quieres que se lean apenas lleguen. El resumen de cada media hora incluye notificaciones de todas las aplicaciones, aunque no estén marcadas aquí. Las apps que ya hayan enviado un aviso pueden aparecer en esta lista aunque no tengan un icono en el lanzador.", 15f)
        root.addView(button("Seleccionar todas para lectura inmediata") {
            NotificationStore.saveSelectedPackages(this, appChoices.map { it.packageName }.toSet())
            loadAppChoicesAndChecks()
            toast("Todas las aplicaciones de la lista se leerán al instante.")
        })
        root.addView(button("Desmarcar todas las aplicaciones") {
            NotificationStore.saveSelectedPackages(this, emptySet())
            loadAppChoicesAndChecks()
            toast("No se leerán avisos al instante; el resumen seguirá incluyendo todas las aplicaciones.")
        })
        selectedAppsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(selectedAppsContainer, matchWrap())

        root.addView(sectionTitle("Resumen e historial"))
        root.addView(button("Leer una muestra del resumen acumulado") {
            if (!appEnabled) {
                toast("MFSD Notification está desactivada. Actívala para escuchar una muestra.")
            } else {
                val now = System.currentTimeMillis()
                val from = NotificationStore.prefs(this).getLong(
                    NotificationStore.KEY_LAST_SUMMARY_AT,
                    now - 30 * 60 * 1000L
                )
                val recent = NotificationStore.readAll(this).filter { it.postedAt > from && it.postedAt <= now }
                val message = NotificationStore.buildSummary(this, recent)
                TtsSpeaker.speak(this, message)
                toast("He enviado el resumen a la voz seleccionada.")
            }
        })
        root.addView(button("Borrar el historial guardado de notificaciones") {
            NotificationStore.clear(this)
            toast("Historial borrado del teléfono.")
        })
        addText(root, "Privacidad: el contenido de las notificaciones se procesa y guarda localmente en este teléfono. Esta aplicación no necesita conexión a Internet. Si Android oculta el contenido de un aviso, la aplicación tampoco puede leerlo.", 15f)
        addText(root, "Nota sobre WhatsApp: el número de mensajes de grupos y chats es aproximado. Android puede agrupar mensajes y no siempre informa qué conversación es un grupo.", 15f)
        addText(root, "Limitación de Android: se reciben las notificaciones que el sistema entrega a los servicios de acceso a notificaciones. Los avisos permanentes, como la reproducción multimedia o algunos servicios en primer plano, se excluyen porque pueden actualizarse continuamente.", 15f)

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
        engineSpinner.tag = "engine_spinner"
        audioSpinner.tag = "audio_spinner"
        root.tag = "root_layout"
        // Los adaptadores se asignan al terminar de crear toda la pantalla.
    }

    private fun loadAppChoicesAndChecks() {
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
        val selected = if (stored == null) {
            appChoices.filter { it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b" }
                .map { it.packageName }.toSet().also { NotificationStore.saveSelectedPackages(this, it) }
        } else stored.toSet()

        buildingAppList = true
        selectedAppsContainer.removeAllViews()
        if (appChoices.isEmpty()) {
            selectedAppsContainer.addView(label("Todavía no hay aplicaciones para elegir. El resumen seguirá reuniendo las notificaciones que lleguen de las aplicaciones del teléfono."), matchWrap())
        } else {
            for (choice in appChoices) {
                val check = CheckBox(this).apply {
                    text = choice.label
                    textSize = 17f
                    minHeight = dp(54)
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    isChecked = choice.packageName in selected
                    contentDescription = "Leer notificaciones de ${choice.label}"
                    setOnCheckedChangeListener { _, checked ->
                        if (buildingAppList) return@setOnCheckedChangeListener
                        val now = NotificationStore.selectedPackages(this@MainActivity).toMutableSet()
                        if (checked) now.add(choice.packageName) else now.remove(choice.packageName)
                        NotificationStore.saveSelectedPackages(this@MainActivity, now)
                    }
                }
                selectedAppsContainer.addView(check, matchWrap())
            }
        }
        buildingAppList = false
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
        "Estado general: activada. La lectura inmediata y los resúmenes pueden funcionar según tus preferencias."
    } else {
        "Estado general: desactivada. No se leerán notificaciones ni se reproducirán resúmenes."
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
            toast("MFSD Notification activada. Empieza un nuevo periodo de notificaciones.")
        } else {
            // El próximo resumen no incluirá eventos anteriores a la desactivación.
            NotificationStore.moveSummaryCutoffToNow(this)
            SummaryScheduler.stop(this)
            TtsSpeaker.stop()
            toast("MFSD Notification desactivada. Se detuvo la voz y no se leerá nada hasta activarla.")
        }

        masterStatus.text = masterStatusText()
        masterStatus.contentDescription = masterStatusText()
        masterStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        masterControlButton.text = masterButtonText()
        masterControlButton.contentDescription = masterButtonText()
        masterControlButton.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    }

    private fun refreshAccessStatus() {
        val expected = ComponentName(this, NotificationCaptureService::class.java)
        val raw = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val enabled = raw.split(":").any { item ->
            val component = ComponentName.unflattenFromString(item)
            component != null && component.packageName == expected.packageName && component.className == expected.className
        }
        accessStatus.text = if (enabled) {
            "Estado del acceso a notificaciones: activado. MFSD Notification puede recibir las notificaciones permitidas por Android."
        } else {
            "Estado del acceso a notificaciones: desactivado. Pulsa el botón siguiente y activa MFSD Notification en la lista de Android."
        }
        accessStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
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
