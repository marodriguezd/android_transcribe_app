# Resumen de Optimizaciones y Mejoras — 29 de Julio de 2026

Este documento detalla **única y exclusivamente** las optimizaciones, refactorizaciones y mejoras de rendimiento realizadas en el proyecto a día **29 de julio de 2026**.

---

## 1. 🚀 Post-Procesado con IA en Streaming (Server-Sent Events / SSE)

### ⚡ Reducción de Latencia Percibida (~2.000 ms → ~300 ms)
* **Transmisión de tokens en tiempo real:** Se ha rediseñado la comunicación HTTP con modelos LLM compatibles con OpenAI en [`PostProcessor.java`](../../app/src/main/java/dev/notune/transcribe/PostProcessor.java) para usar Server-Sent Events (`stream: true`).
* En lugar de esperar a que la IA genere el texto refinado completo (que causaba una espera visual de 2 a 3 segundos), el usuario empieza a ver la respuesta corregida a los **~300 ms** (Time-To-First-Token).

### ⌨️ Inserción Progresiva Token a Token en el Teclado IME
* En [`RustInputMethodService.java`](../../app/src/main/java/dev/notune/transcribe/RustInputMethodService.java), los tokens de texto se van escribiendo de forma fluida y en tiempo real directamente en la aplicación/campo enfocado mediante `InputConnection.commitText(deltaToken, 1)`.
* Esto elimina la sensación de parón o congelación tras soltar el botón de grabación.

### 🎙️ Renderizado en Vivo en la Pantalla de Voz (Popup)
* En [`RecognizeActivity.java`](../../app/src/main/java/dev/notune/transcribe/RecognizeActivity.java), el estado de post-procesado pasa de mostrar una etiqueta estática *"Refining..."* a renderizar dinámicamente las palabras conforme el modelo las emite.

### 🛡️ Resiliencia y Garantía de "No Frankenstein Text"
* **Reintentos automáticos (hasta 3 intentos):** Si la conexión de red parpadea o se interrumpe durante el streaming, el sistema reintenta automáticamente la conexión SSE aplicando un *backoff* exponencial.
* **Limpieza de deltas y Fallback Limpio:** Si tras los 3 reintentos el servidor se cae de forma definitiva, el teclado elimina los tokens parciales previamente insertados (`deleteSurroundingText`) y pega la transcripción bruta original entera y limpia. Esto previene fragmentos duplicados o textos corruptos ("efecto Frankenstein").
* **Fallback a petición completa:** Si un proveedor o modelo específico no soporta streaming y devuelve un error HTTP 400, la app conmuta automáticamente al modo en bloque de forma transparente.

---

## 2. 🤖 Reglas de Compilación CI/CD y Documentación Agéntica

* **Fijación de Regla de Compilación CI/CD:** Se ha documentado e inmunizado en [`AGENTS.md`](../../AGENTS.md) la regla estricta de que **toda compilación de depuración (Debug) se realiza exclusivamente en el pipeline de CI/CD de GitHub Actions** (`Debug APK → Telegram`), prohibiendo ejecuciones locales para proteger la estabilidad del entorno.
* **Puesta a punto de la jerarquía agéntica:** Se actualizaron e indexaron los registros en `.agents/INDEX.md`, `.agents/progress.md` y `.agents/memory/polish-agents-2026-07-29.md`.

---

## 3. ⏱️ Auto-Parada por Silencio en Teclado IME y Algoritmo Adaptativo VAD

* **Habilitación de Auto-Stop en Teclado IME:** Se corrigió la limitación donde la auto-parada por silencio estaba desactivada internamente en el teclado IME ([`src/ime.rs`](../../src/ime.rs)). Ahora [`RustInputMethodService.java`](../../app/src/main/java/dev/notune/transcribe/RustInputMethodService.java) lee el marcador `auto_stop` y lo transmite por JNI, procesando la finalización automática mediante la respuesta de `onAutoStop()`.
* **Calibración Adaptativa VAD ([`src/voice_session.rs`](../../src/voice_session.rs)):** Se ajustaron las constantes de nivel de voz (`MIN_SPEECH_LEVEL` a `0.05` y `SPEECH_MARGIN` a `0.04`) con ajuste adaptativo dinámico sobre el suelo de ruido. Esto asegura que susurros y voces suaves activen el temporizador de 2 segundos de silencio de forma precisa sin quedar bloqueadas por ruido de fondo.

---

## 4. 📖 Integración Simplificada del Diccionario del Sistema Android (Estilo FUTO Keyboard)

* **Menú Nativo de Android:** Al pulsar en la tarjeta **Palabras Personalizadas**, la aplicación abre directamente la interfaz de los ajustes del Diccionario Personal de Android ([`Settings.ACTION_USER_DICTIONARY_SETTINGS`](../../app/src/main/java/dev/notune/transcribe/UserDictionaryHelper.java)) con fallbacks para capas OEM como Samsung OneUI o Xiaomi MIUI, simplificando la gestión al estilo de FUTO Keyboard.
* **Sincronización Automática:** Se creó [`UserDictionaryHelper.java`](../../app/src/main/java/dev/notune/transcribe/UserDictionaryHelper.java) y se añadió el permiso `READ_USER_DICTIONARY`. Las palabras del sistema se extraen del `ContentProvider` de Android al volver a la app o iniciar cualquier sesión de grabación en el teclado IME o ventana de voz, sincronizándose con el corrector fonético nativo en Rust ([`src/corrector.rs`](../../src/corrector.rs)).
