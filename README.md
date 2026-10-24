# MFSD Notification

Proyecto fuente de una aplicación Android nativa escrita en Kotlin, ajustada para Android 16 (API 36).

## Comportamiento

- Botón general **Desactivar MFSD Notification / Activar MFSD Notification**. Al desactivar, detiene la voz en curso, cancela el siguiente resumen y pausa la captura. Al reactivar, empieza un nuevo periodo y no lee avisos anteriores a la pausa.
- Recibe notificaciones de todas las aplicaciones que Android entregue al servicio de acceso a notificaciones.
- Guarda los avisos para el resumen de todas las aplicaciones, sin limitarlo a WhatsApp ni a la lista de lectura inmediata.
- La lista de aplicaciones controla solamente qué notificaciones se leen en voz alta al llegar. WhatsApp se marca por defecto si aparece en la lista.
- Permite seleccionar todas las aplicaciones de la lista para lectura inmediata o desmarcarlas de una vez.
- Genera un resumen hablado aproximadamente cada 30 minutos a partir de la hora del teléfono.
- Permite escoger un motor de síntesis de voz instalado, independiente de la configuración de TalkBack, y elegir el uso de audio de accesibilidad, llamada o multimedia.
- Ofrece controles nativos de Android y etiquetas orientadas a lectores de pantalla.
- Guarda hasta 500 registros localmente; incluye un botón para borrar el historial.

## Archivos principales

- `app/src/main/java/com/mfsd/notification/MainActivity.kt`: interfaz, botón de activación general y configuración accesible.
- `NotificationCaptureService.kt`: recepción de notificaciones de otras aplicaciones y lectura inmediata selectiva.
- `NotificationRecord.kt`: almacenamiento local, deduplicación y redacción del resumen.
- `SummaryWorker.kt`: planificación de resúmenes aproximadamente cada media hora.
- `TtsSpeaker.kt`: selección de motor TTS, canal de audio, cola de voz y detención inmediata.
- `app/src/main/AndroidManifest.xml`: definición del servicio de acceso a notificaciones y actividad principal.
- `DESARROLLO_INSTRUCCION.md`: solicitud original y requisitos adicionales que motivan este proyecto.

## Consideraciones de funcionamiento

- Android exige que la persona usuaria conceda manualmente el acceso a notificaciones en los ajustes del sistema. La app no puede leer avisos antes de obtener ese permiso.
- El sistema entrega las notificaciones que permite ver a un `NotificationListenerService`; no hay garantía de acceso a avisos que Android o la aplicación emisora oculten.
- Las notificaciones permanentes, como reproducción multimedia o servicios en primer plano, se excluyen para evitar que actualizaciones continuas llenen el historial.
- El planificador de Android puede retrasar los resúmenes por ahorro de batería. “Cada 30 minutos” es una frecuencia aproximada, no una garantía al segundo.
- El número de mensajes de grupos y chats de WhatsApp es aproximado; Android puede agrupar notificaciones sin exponer todos los mensajes o el tipo de conversación.
- Eloquence solo aparece en el selector si está instalado como un motor TTS de Android y el sistema lo informa como disponible.
- Los nombres disponibles en la lista de lectura inmediata combinan aplicaciones del lanzador y aplicaciones que ya han enviado notificaciones recibidas por la app. El resumen no depende de esa lista.
- Las preferencias, los textos y el historial de notificaciones se guardan localmente. El proyecto no declara permiso de Internet.
