# Análisis de viabilidad P1.4/P1.5 — 2026-08-04

**Tema:** clasificación de los checklists P1.4 (subtítulos en hardware) y P1.5
(smoke del postprocesado con proveedor real) según qué pasos se pueden ejecutar
desde el entorno de trabajo actual (sin dispositivo físico interactivo) y cuáles
quedan irreductiblemente para dispositivo.

**Contexto:** el Guantelete sigue ABIERTO. `assembleDebug`/`lintDebug` ya están
validados en CI (runs `30859369221`/`30859370506`, fix `aa08a08`). El harness
JVM (P1.3) cubre payload/errores/fallback/timeout por seam. Este documento
clasifica el resto de P1.4/P1.5 para saber qué se puede avanzar sin hardware.

## Entorno verificado (2026-08-04, comandos ejecutados)

- Host **ARM64 con userspace Android** (`env` con `BOOTCLASSPATH=/apex/…`,
  `ANDROID_ROOT=/system`) — el dispositivo móvil del usuario.
- **Sin KVM** (`/dev/kvm` ausente), **sin `emulator`**, **sin `system-images`**,
  **sin AVDs** (`~/.android/avd` vacío) → emulador local inviable (TCG-only).
- `adb` 1.0.41 presente pero **sin dispositivos reales**: solo entrada fantasma
  `emulator-5554 offline` (sin puertos 5554–5584 escuchando ni proceso qemu).
- `sdkmanager` disponible en `/opt/android-sdk` (instalar emulador + imagen
  seguiría siendo TCG → no práctico).
- Red disponible (HTTP 200 a `api.github.com`); sin servidor LLM local
  escuchando en puertos comunes.
- Regla de la sesión: no compilar en el móvil; los gates compile/lint ya los
  cubre CI.

## P1.4 — Subtítulos en hardware: 0 pasos ejecutables desde aquí

Todos los escenarios (stop/restart, revocación desde la notificación, overlay
eliminado, `AudioRecord` liberado, cero callbacks tras `cleanupNative`, matriz
Android 10–15 + ROM OEM) dependen de audio real vía MediaProjection, overlay
`SYSTEM_ALERT_WINDOW`, notificación, proceso `:ime` y UI.

**Mitigaciones posibles desde aquí (no sustituyen la validación):**

1. **Auditoría estática dirigida** de los paths de teardown que el smoke
   ejercitaría: `MediaProjection.Callback.onStop/onError` → teardown; acción
   "Stop" de la notificación → `stopSelf` → `cleanupNative` → worker drena y
   suelta GlobalRef (P0.2); `removeOverlay` anula `mSubtitleText`; `AudioRecord`
   liberado en todos los caminos. Etiqueta: *auditoría estática*.
2. **Andamiaje de tests** (`androidTest` con UIAutomator, o Robolectric en JVM
   para lifecycle parcial de Service/overlay) para ejecutar luego en
   CI/dispositivo. **Decisión pendiente**: Robolectric es una dependencia nueva
   considerable (descarga `android-all`) y choca con la convención de harness
   JVM puro de AGENTS.md — evaluar antes de añadirla.
3. Gates compile/lint ya verdes en CI (runs `30859369221`/`30859370506`).

## P1.5 — Postprocesado con proveedor real: clasificación por escenario

| # | Escenario | Clasificación |
|---|---|---|
| 1 | Éxito: texto refinado entregado **una sola vez** | ✅ Cubierto en JVM (`singleNonStreamingRequest…`, `exactlyOneFinalDeliveryPerRequest`). La parte "el editor recibe" → dispositivo |
| 2 | Fallback ante **DNS fail** (URL inalcanzable) | 🟡 **Ampliable desde aquí** (pendiente): host inexistente real (`.invalid`) → `UnknownHostException` → `onError` → texto crudo |
| 3 | Fallback ante HTTP 4xx/5xx | ✅ Cubierto en JVM (`httpErrorReportsApiError`, 500) |
| 4 | Fallback ante JSON inválido / respuesta vacía | ✅ Cubierto en JVM (`malformedJsonReportsParseError`, `emptyResultTextReportsError`) |
| 5 | **Timeout real de read** > 60 s + latencia start→fallback | 🟡 Mecanismo ✅ por seam (`stalledResponseHitsReadTimeout…`, valores escalados). **Wall-clock 60 s y latencia medida** → solo-dispositivo |
| 6 | **Connect timeout** ~30 s con IP no enrutable | 🟡 **Ampliable desde aquí** (pendiente): seam con connect timeout corto contra IP TEST-NET (`192.0.2.1`) → `SocketTimeoutException`/host-unreachable → `onError`. Wall-clock 30 s → solo-dispositivo |
| 7 | Toggle-off en vuelo: broadcast `CANCEL_PP` al `:ime`, IME nunca en "Refining…" | ✅ Semántica en JVM (`toggleOffDuringFlightDeliversRawTranscript`). **Broadcast cross-proceso e IME no bloqueado** → solo-dispositivo |
| 8 | Superficies concurrentes (dictado + fetch de modelos) | ✅ Base en JVM (`CallRegistryTest`: cancelar A no cancela B). Concurrencia real entre procesos → solo-dispositivo |
| 9 | Cancelación por superficie / cierre en vuelo | ✅ Base en JVM (CallRegistry + guards Java). Rotación real → solo-dispositivo |
| 10 | 10+ dictados sin fugas (logcat GC, respuestas OkHttp cerradas) | ❌ Solo-dispositivo |
| 11 | Rotación/cierre durante el vuelo → 0 callbacks tardíos | ❌ Solo-dispositivo |
| 12 | Latencia end-to-end (tap-stop → texto final) | ❌ Solo-dispositivo |

**Balance:** 7 escenarios ya cubiertos o con base en JVM; **2 ampliables desde
aquí** (DNS fail real + connect timeout por seam, ambos ejercitan mecanismos
reales de red/OkHttp en la JVM); 5 quedan para dispositivo (wall-clock 30 s/60 s,
TLS real, broadcast `:ime`, superficies reales, fugas, latencia end-to-end).

## Plan para mañana (2026-08-05)

1. Añadir los **2 tests JVM pendientes** a `PostProcessorTest` (DNS fail con
   host `.invalid`; connect timeout por seam contra `192.0.2.1` con client
   escalado) y ejecutar `./gradlew testDebugUnitTest`.
2. Opcional: auditoría estática dirigida de P1.4 (paths de teardown) con
   evidencia documentada.
3. Opcional: evaluar Robolectric respetando AGENTS.md (deps mínimas, harness
   JVM puro) antes de decidir.
4. Smoke real en dispositivo: checklist §P1.5 completo + P1.4
   (start/stop/start, revocación MediaProjection, ROM OEM) conservando
   evidencia (versionCode/versionName, ABI, API, ROM, logcat con TAG
   `PostProcessor`/`OfflineVoiceInput`, tiempos por escenario, proveedor y
   modelo LLM).
