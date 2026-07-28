# Spec — Offline Voice Input (Android)

Describe el **QUÉ** del proyecto. **Para CÓMO está construido ver
[`architecture.md`](./architecture.md).** Para reglas que aplican a los
agentes IA al modificar el código, ver [`../AGENTS.md`](../AGENTS.md).

## Objetivo

App Android para **transcripción de voz offline** que devuelve texto a
cualquier app del sistema. Incluye:

1. Voice input modal que aparece al pulsar el micro del teclado o sitio web.
2. Subtítulos en vivo sobre cualquier audio/video del dispositivo.
3. Un teclado propio (IME) opcional para hablar como método de entrada.
4. **Post-procesado IA opcional** (fork addition): refina la transcripción
   con un LLM compatible OpenAI — opt-in, desactivado por defecto.
5. **Corrección fonética de palabras personalizadas** (fork addition):
   el usuario mantiene un diccionario de términos correctos (nombres
   propios, jerga técnica) en un marker file `filesDir/custom_words`. Tras
   el ASR, un corrector fonético ES+EN reemplaza palabras mal reconocidas
   que suenan como un término del diccionario. Totalmente offline,
   determinista, funciona con cualquier modelo. Opt-in por presencia del
   fichero (no requiere toggle separado).

**Promesa nuclear:** la captura de audio y el ASR son **100 % on-device**.
Si el post-procesado IA está apagado, ningún byte sale del teléfono. Si está
encendido, es porque el usuario lo pidió explícitamente y configuró la API
key.

## Usuarios objetivo

- **Hispano/anglófono/eurofono** que escribe en apps o sitios web y quiere
  privacidad real sobre su audio.
- Usuarios sin acceso permanente a Internet o que lo evitan por privacidad.
- Personas con limitaciones motoras donde el teclado táctil/virtual es
  inaccesible y necesitan "escribir hablando".

## Restricciones hard (no negociables)

- **Offline-first.** Captura + ASR corren en local. Post-procesado IA es
  opt-in. Default = sin red.
- **`arm64-v8a` únicamente.** ggml core exige dotprod + fp16 (ARMv8.2,
  ~2018+). `check_cpu_features` aborta limpio en CPUs más viejas.
- **`minSdk 26` / `targetSdk 35`** (Android 8.0 → 15).
- **Modelo bundled:** Canary 180M Flash Q8_0 (~209 MB), descargado al
  primer build con verificación SHA-256.
- **Sin `SharedPreferences` para ajustes cross-process.** Los toggles van
  como marker files en `filesDir()` — ver AGENTS.md §4.5.

## No-objetivos

### Fuera de scope (explícito)

- Streaming translation bidireccional. (El flag `model_translate` activa
  un solo sentido; no es una conversación EN↔ES en tiempo real.)
- Text-generation / Q&A / agente conversacional basado en audio.
- Speaker diarization ("quién habló cuándo").
- Voice biometrics / identificación del hablante.
- Custom wake-word ("Hey, …"). El trigger es siempre UI/IME/intent.
- Biasing del vocabulario al ASR vía `initial_prompt` de Whisper (se
  eligió un post-filtro fonético post-ASR en su lugar — ver
  `architecture.md` §Historia de decisiones).
- Push-to-talk sobre llamadas VoIP o gaming.

### Diferido (posible en roadmap, NO ahora)

- Sync entre dispositivos de los ajustes / modelo / prompts.
- Japonés / coreano / chino contenedor-bundled (sólo Canary; el usuario
  puede importar otros GGUF compatibles via Settings).
- API pública a apps externas más allá de `RecognizerIntent` y `Intent.SEND`.

## Superficies funcionales

| # | Superficie | Mecanismo Android |
|---|---|---|
| 1 | **Popup de voz** | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` |
| 2 | **Servicio de reconocimiento** | `android.speech.RecognitionService` |
| 3 | **Teclado de voz (IME)** | `InputMethodService` (`android:process=":ime"`) |
| 4 | **Subtítulos en vivo** | MediaProjection foreground service |
| 5 | **Transcripción de archivos** | `Intent.ACTION_SEND/VIEW audio/*` |
| 6 | **Diccionario fonético personalizado** | Marker file `filesDir/custom_words` + `CustomWordsActivity` (editor) + `corrector.rs` (post-ASR) |

## Criterios de aceptación (a fuzz)

- Abrir la app, instalar en un Pixel 8 stock, escribir hablando en WhatsApp:
  el texto aparece en ≤ 1.5× tiempo real, sin ninguna llamada de red.
- Si la llamada al LLM post-procesador falla mid-transcripción, el texto
  crudo llega al usuario — nunca se pierde una transcripción por un error
  de la API externa.
- El corrector fonético nunca pierde texto: cualquier fallo (sin
  diccionario, I/O error, panic) devuelve la transcripción cruda sin
  modificar.
- El cambio de idioma en el picker aplica tanto en el popup como en el IME,
  sin necesidad de reiniciar la app ni recargar modelo.

## Cambios esperados

Este fichero **cambia con poca frecuencia** (semanas/meses). Edítalo sólo
cuando el **alcance del producto** cambia materialmente. Cambios de
implementación van a [`architecture.md`](./architecture.md). Cambios de
reglas para IAs van a [`../AGENTS.md`](../AGENTS.md).
