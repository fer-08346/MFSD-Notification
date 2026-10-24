# Instrucción original para desarrollar MFSD Notification

Esta es la solicitud inicial que dio origen al proyecto, conservada para tener contexto sobre el objetivo y las necesidades de la aplicación.

> quiero que me hagas una aplicación se llame MFSD notification, o como sea se escriba notificación en ingles para android, que se sincronice con el reloj de mi sistema , acceda a mis notificaciones y me cree un resumen cada media hora, pero que me permita seleccionar una síntecis de voz diferente a la de mi sistema. por ejemplo, si en mi texto a voz tenga sámsumg, que la app me permita poner eloquence o algúno de los que tenga instalados. deve ser haccesible con lector de pantalla, poder configurarse con el volumen de accesibilidad, llamada o multimedia, según yo prefiera, quiero que lea whatsapp, tienes tantos mensajes de grupos, tantos de chat. y que las apps que yo elija me lean las notificaciones apenas caigan

## Requisitos adicionales indicados después

> miraa me olbidé de decirte que quieroquetenga un boton para desactibarla, ya que en la noche no quiero que me lea nada. y no quiero que sea solo whatsapp, quiero que sea todo lo que tenga el teléfono que me mande notificaciones. por si accaso, mi android es 16 y no pongas cómo compilarlo, solo dáme el código y yo veo como resuelbo. a, dentro del zip pon un md con la instrucción que te di para empezar a desarrollarla, así tengo contexto

## Interpretación funcional que guía el proyecto

- Nombre: **MFSD Notification**.
- Plataforma objetivo: **Android 16 / API 36**.
- Sincronizar la hora de los resúmenes con la hora del teléfono.
- Recibir avisos de todas las aplicaciones que Android exponga al servicio de acceso a notificaciones.
- Resumir los avisos recibidos aproximadamente cada 30 minutos.
- Permitir que la usuaria elija las aplicaciones que interrumpen con lectura de voz en el momento de llegada; esa lista no limita las aplicaciones incluidas en el resumen.
- Disponer de un botón general para detener la voz y pausar la lectura y los resúmenes, especialmente durante la noche.
- Permitir elegir un motor TTS instalado que sea distinto al usado por el lector de pantalla y seleccionar el uso de audio de accesibilidad, llamada o multimedia.
- Mantener una interfaz con controles nativos y nombres claros para lectores de pantalla.
- No incluir instrucciones para compilar el proyecto en la documentación del ZIP; se entrega el código fuente para que la usuaria decida cómo resolver esa parte.
