# Spec: Imitar Handy — Corrección difusa de palabras personalizadas

## Context

Android app `OfflineVoiceInput` (package `dev.notune.transcribe`). Java + Rust (JNI).  
targetSdk 35, minSdk 26, compileSdk 35.

**Problema**: El sistema actual de "Custom Words / Dictionary" usa reemplazo exacto con
regex (`error=corrección`) aplicado después de la transcripción. Es rígido, poco intuitivo,
y no se comporta como Handy (la app de referencia), que usa un algoritmo de matching difuso
(Levenshtein + Soundex) sobre una lista plana de palabras correctas.

---

## Cómo funciona Handy (`cjpais/Handy`)

Archivo de referencia: `src-tauri/src/audio_toolkit/text.rs` en el repo de Handy.

### Algoritmo de corrección (`apply_custom_words`)

1. **Matching difuso (Levenshtein + Soundex)**:
   - Calcula distancia de Levenshtein normalizada por longitud entre cada palabra
     del texto transcrito y cada palabra personalizada.
   - Calcula similitud fonética con **Soundex** (algoritmo de codificación fonética).
   - Score combinado: si hay match fonético, `levenshtein_score * 0.3` (boost 3x).
     Si no, solo el score de Levenshtein.
   - El match se acepta si `combined_score < threshold` (default `0.18`).

2. **N-gram matching (1-3 palabras)**:
   - Itera las palabras del texto en ventanas de 1, 2 y 3 palabras (greedy, longest first).
   - Para cada n-grama, junta las palabras sin espacios ni puntuación y las compara
     contra las palabras personalizadas (también normalizadas).
   - Esto permite corregir artefactos como `"Charge B"` → `"ChargeBee"`,
     `"Chat G P T"` → `"ChatGPT"`, `"Open AI"` → `"OpenAI"`.

3. **Expansión de ampersand**:
   - Palabras con `&` (ej. `"R&D"`) generan una clave de match extra con `" and "`
     (ej. `"randd"`), permitiendo matchear tanto `"R&D"` como `"R and D"`.

4. **Preservación de caso**:
   - Si el original era TODO MAYÚSCULAS → el reemplazo va en mayúsculas.
   - Si el original tenía Primera Mayúscula → el reemplazo también.
   - Si el original era todo minúsculas → el reemplazo en minúsculas.

5. **Preservación de puntuación**:
   - Extrae prefijo y sufijo de puntuación del original y los reinserta alrededor
     del reemplazo.

6. **Umbral configurable**:
   - `word_correction_threshold: f64` (default `0.18`) en settings.
   - Valores más bajos = más estricto (menos falsos positivos).
   - Se expone en la UI de Debug settings.

### Pipeline de post-procesado de texto

Handy aplica dos pasos secuenciales después de la transcripción:

1. `apply_custom_words(text, custom_words, threshold)` — corrección difusa
2. `filter_transcription_output(text, lang, custom_filler_words)` — limpieza:
   - Elimina filler words por idioma (uh, um, eh, etc.)
   - Colapsa tartamudeos (3+ repeticiones: `"wh wh wh wh"` → `"wh"`)
   - Limpia espacios múltiples

### Almacenamiento

- `custom_words: Vec<String>` — lista plana en `settings_store.json`.
- Sin formato `error=corrección`. Solo palabras correctas.
- Sin múltiples diccionarios. Una sola lista.
- Sin enable/disable por palabra o diccionario.

---

## Cómo funciona nuestra app actualmente

### Algoritmo de corrección (`SettingsManager.applyDictionary`)

```java
// Reemplazo exacto con regex, solo para líneas con "="
processed = processed.replaceAll("(?i)\\b" + Pattern.quote(key) + "\\b", value);
```

- Solo match exacto (case-insensitive, word-boundary).
- Requiere formato `error=corrección` (ej. `paraquid=Parakeet`).
- Sin matching difuso, sin n-gramas, sin umbral.

### Doble uso de palabras

- Líneas con `=`: reemplazo directo vía regex.
- Líneas sin `=`: se inyectan como `[Context/Hints]` en el prompt del LLM
  (PostProcessor), pero solo si AI post-processing está activo.
- Las palabras sin `=` nunca se usan para corrección directa.

### Almacenamiento

- `DictionaryManager.java` — múltiples diccionarios con enable/disable.
- `Dictionary.java` — modelo: `id (UUID), name, List<String> words, enabled`.
- Persistencia en `dictionaries.json`.
- Migración desde SharedPreferences (`custom_hotwords` key).

### Puntos de aplicación

| Entry point | applyDictionary | AI post-processing |
|---|---|---|
| IME keyboard | Sí | Sí (si activo) |
| RecognizeActivity | Sí | No |
| TranscribeFileActivity | Sí | No |

---

## Análisis de gaps

| Aspecto | Handy | Nosotros | Gap |
|---------|-------|----------|-----|
| Algoritmo | Levenshtein + Soundex difuso | Regex exacto | **Crítico** |
| Formato entrada | Lista plana de palabras | `error=corrección` | **Crítico** |
| Umbral | Configurable (0.18) | No existe | **Alto** |
| N-gramas | 1-3 palabras | Solo palabra individual | **Alto** |
| Preservación mayúsculas | Sí | No | **Medio** |
| Preservación puntuación | Sí | No | **Medio** |
| Expansión ampersand | Sí | No | **Bajo** |
| UI | Lista única simple | Diccionarios múltiples | **Mejora** |
| Filler words + stutter | Sí (filter_transcription_output) | No | **Medio** |
| Context hints para LLM | No (post-processing es separado) | Sí (mezclado con diccionario) | **Diseño** |

---

## Implementation Plan

### Step 1 — Crear `WordCorrector.java` (nuevo)

**Archivo nuevo**: `app/src/main/java/dev/notune/transcribe/WordCorrector.java`

Implementar el algoritmo de matching difuso en Java puro (sin dependencias externas):

```java
public class WordCorrector {
    private final List<String> customWords;
    private final double threshold;

    public WordCorrector(List<String> customWords, double threshold) { ... }

    public String applyCustomWords(String text) {
        // 1. Normalizar custom words (sin puntuación, lowercase)
        // 2. Tokenizar texto en palabras
        // 3. Para cada posición, probar n-gramas de 3, 2, 1 palabras
        // 4. Para cada n-grama, calcular mejor match con:
        //    a. Distancia de Levenshtein normalizada
        //    b. Soundex (implementación propia o de Apache Commons Codec)
        //    c. Score combinado = phoneticMatch ? levenshtein * 0.3 : levenshtein
        // 5. Si score < threshold, reemplazar preservando caso y puntuación
        // 6. También generar claves expandidas para palabras con "&"
        return correctedText;
    }
}
```

**Algoritmos a implementar**:
| Algoritmo | Complejidad | Notas |
|---|---|---|
| Levenshtein distance | O(n*m) | Trivial, ~15 líneas |
| Soundex | O(n) | Algoritmo estándar, ~20 líneas |
| N-gram matching | O(words * n * customWords) | Con optimización de early-exit por longitud |

**Soundex** es un algoritmo de 4 caracteres que codifica fonéticamente una palabra.
Ejemplos: `"Handy"` → `H530`, `"Handi"` → `H530` (mismo código = match fonético).

### Step 2 — Añadir umbral a SettingsManager

**Archivo**: `app/src/main/java/dev/notune/transcribe/SettingsManager.java`

- Añadir constante `KEY_WORD_CORRECTION_THRESHOLD = "word_correction_threshold"`.
- Añadir métodos `getWordCorrectionThreshold()` (default `0.18`) y `setWordCorrectionThreshold(double)`.
- Persistir en SharedPreferences como float/double.

### Step 3 — Refactorizar `applyDictionary()` → delegar a WordCorrector

**Archivo**: `app/src/main/java/dev/notune/transcribe/SettingsManager.java`

```java
public String applyDictionary(String text) {
    List<String> words = new DictionaryManager(prefs_context).getActiveWordsList();
    // getActiveWordsList() devuelve lista plana (sin formato "key=value")
    if (words == null || words.isEmpty()) return text;

    double threshold = getWordCorrectionThreshold();
    WordCorrector corrector = new WordCorrector(words, threshold);
    return corrector.applyCustomWords(text);
}
```

### Step 4 — Migrar formato de palabras

**Archivo**: `app/src/main/java/dev/notune/transcribe/DictionaryManager.java`

- Añadir método `getActiveWordsList(): List<String>` que devuelva todas las palabras
  de diccionarios activos como lista plana (ignorando el formato `=`).
- Para migración: si una palabra contiene `=`, extraer solo el lado derecho (la
  corrección) al construir la lista plana.
- Mantener `getActiveWords()` (con `=`) para compatibilidad temporal con PostProcessor.

### Step 5 — Actualizar UI: añadir slider de umbral

**Archivos**:
- `app/src/main/res/layout/activity_main.xml` — añadir `SeekBar` + label en la
  sección "Custom Words".
- `app/src/main/java/dev/notune/transcribe/MainActivity.java` — bind del slider.
- `app/src/main/res/values/strings.xml` — strings para el label del umbral.

Rango: `0.0` a `1.0`, step `0.01`, default `0.18`.
Labels: "Strict" (0.0) — "Lenient" (1.0).

### Step 6 — Actualizar UI de edición de palabras

**Archivos**:
- `app/src/main/res/layout/activity_dictionary_edit.xml` — cambiar hint text.
- `app/src/main/java/dev/notune/transcribe/DictionaryEditActivity.java` — actualizar
  hint: quitar referencia a `error=corrección`.
- `app/src/main/res/values/strings.xml` — actualizar `desc_word_input`.

Nuevo hint: `"Enter words you want to be recognized correctly (e.g. Parakeet, ChatGPT)"`.

### Step 7 — Repensar los "context hints" para el LLM

**Archivo**: `app/src/main/java/dev/notune/transcribe/PostProcessor.java`

Opción A (recomendada): Mantener los context hints pero alimentarlos desde las mismas
palabras planas (sin distinguir `=`). Todas las palabras son tanto correcciones como
hints.

Opción B: Eliminar los context hints del diccionario (Handy no los tiene). El
post-processing del LLM ya recibe el texto ya corregido por WordCorrector.

**Decisión**: Opción A. Alimentar el LLM con la lista plana de palabras como contexto,
igual que ahora pero sin la distinción `=`.

```java
// PostProcessor.java - simplificado
List<String> hints = dictionaryManager.getActiveWordsList();
// Todas las palabras son hints (ya se aplicaron como corrección antes)
```

### Step 8 — (Opcional) Implementar filter_transcription_output

**Archivo nuevo o extensión de WordCorrector**: filtro de filler words + stutter.

- Mapeo de filler words por idioma (`en` → `uh, um, ah...`, `es` → `ehm, mmm...`).
- Collapse de stutter: 3+ repeticiones de la misma palabra → 1 instancia.
- Limpieza de espacios múltiples.
- Este paso se aplicaría ANTES de `applyCustomWords` para no interferir con
  las palabras personalizadas.

### Step 9 — (Opcional) Unificar aplicación en los 3 entry points

Actualmente `RecognizeActivity` y `TranscribeFileActivity` no ejecutan AI
post-processing. Con el nuevo sistema, el pipeline sería uniforme:

1. `filterTranscriptionOutput(text)` — limpiar filler words + stutter
2. `applyDictionary(text)` → `WordCorrector.applyCustomWords(text)`
3. (Opcional) `PostProcessor.process(text)` — AI post-processing

---

## Archivos a modificar/crear (resumen)

| Archivo | Cambio |
|---|---|
| `app/.../transcribe/WordCorrector.java` | **NUEVO** — Algoritmo de matching difuso |
| `app/.../transcribe/SettingsManager.java` | Step 2, 3 — Umbral + delegar a WordCorrector |
| `app/.../transcribe/DictionaryManager.java` | Step 4 — `getActiveWordsList()` sin formato `=` |
| `app/.../transcribe/PostProcessor.java` | Step 7 — Simplificar hints |
| `app/.../transcribe/MainActivity.java` | Step 5 — Slider de umbral |
| `app/.../transcribe/DictionaryEditActivity.java` | Step 6 — Actualizar hint |
| `app/src/main/res/layout/activity_main.xml` | Step 5 — Slider UI |
| `app/src/main/res/layout/activity_dictionary_edit.xml` | Step 6 — Actualizar hint |
| `app/src/main/res/values/strings.xml` | Steps 5, 6 — Nuevos strings |

**Dependencias nuevas**: Ninguna. Todo se implementa en Java puro.

---

## Verificación

Después de implementar:

1. Añadir palabras personalizadas: `"Parakeet"`, `"ChatGPT"`, `"ChargeBee"`, `"R&D"`
2. Dictar frases con errores comunes: `"paraquid"`, `"chat g p t"`, `"charge b"`, `"R and D"`
3. Verificar que el texto se corrige a las palabras correctas.
4. Ajustar el umbral y verificar que:
   - Umbral bajo (0.05): solo corrige matches muy cercanos.
   - Umbral alto (0.5): corrige más agresivamente (posibles falsos positivos).
5. Verificar preservación de mayúsculas: `"PARAQUID"` → `"PARAKEET"`.
6. Verificar que los diccionarios con enable/disable siguen funcionando.
7. Verificar que el AI post-processing recibe las palabras como hints.
