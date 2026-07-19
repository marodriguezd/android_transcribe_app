package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists user-saved post-processing prompts in {@code files/prompts.json}
 * via {@link AtomicFile} so a crash or power loss cannot leave a half-
 * written JSON blob.
 *
 * <p>The "builtin" prompt is the {@link Prompt#BUILTIN_ID} slot. It has two
 * modes:
 * <ul>
 *   <li><b>Virtual</b> (default): the body is read live from
 *       {@code R.string.label_prompt} and the name from
 *       {@code R.string.name_builtin_prompt}. Nothing is written to disk —
 *       upgrades to the bundled default naturally propagate.</li>
 *   <li><b>Overridden</b>: once the user edits the builtin via
 *       {@link PostProcessPromptEditActivity}, an override entry with
 *       {@code id="__builtin__"} is persisted to {@code prompts.json}. All
 *       read paths then prefer the override. {@link #isBuiltinOverridden()}
 *       surfaces this to the UI so the row can render "App default
 *       (customized)" and a "Reset to default" affordance appears in the
 *       editor and overflow menu.</li>
 * </ul>
 *
 * <p>{@link #delete(String)} with {@link Prompt#BUILTIN_ID} is the inverse:
 * it removes the override entry from disk so subsequent reads fall back to
 * the resource-backed virtual builtin. It is <i>not</i> a "this prompt is
 * gone" semantic — the builtin slot always exists.
 *
 * <p>Persisted alongside by {@link #KEY_ACTIVE_PROMPT_ID} is the active
 * prompt UUID. The active id defaults to {@link Prompt#BUILTIN_ID} if
 * the user has never selected a saved prompt, the prefs entry is missing,
 * or the previously active prompt was deleted (we auto-fall back to
 * builtin in that case).
 *
 * <p>Migration: on first load after an upgrade from a release that only
 * had {@code KEY_SYSTEM_PROMPT}, the legacy body is moved into a fresh
 * prompt named "Default (migrated)" via
 * {@link R.string#name_migrated_prompt} and made active, then the
 * legacy prefs key is removed.
 */
public class PromptsRepository {
    private static final String TAG = "PromptsRepository";
    private static final String FILE_NAME = "prompts.json";
    private static final String PREFS_NAME = "transcribe_settings";
    private static final String KEY_LEGACY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_ACTIVE_PROMPT_ID = "active_prompt_id";

    private final Context context;
    private final AtomicFile file;
    private final SharedPreferences prefs;
    private final List<Prompt> prompts = new ArrayList<>();
    private boolean loaded;

    public PromptsRepository(Context context) {
        this.context = context.getApplicationContext();
        this.file = new AtomicFile(new File(this.context.getFilesDir(), FILE_NAME));
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // -------- public API --------

    /**
     * All user-saved prompts plus any builtin override (identified by
     * {@link Prompt#BUILTIN_ID}). Excludes the virtual-only builtin when no
     * override exists — callers that want a flat user-only list should keep
     * using the helper.
     */
    public synchronized List<Prompt> getUserPrompts() {
        ensureLoaded();
        return new ArrayList<>(prompts);
    }

    /**
     * True if the user has saved a builtin override (id =
     * {@link Prompt#BUILTIN_ID}) to disk. UI uses this to render the
     * "App default (customized)" subtitle and offer the "Reset to default"
     * affordance.
     */
    public synchronized boolean isBuiltinOverridden() {
        ensureLoaded();
        for (Prompt p : prompts) {
            if (Prompt.BUILTIN_ID.equals(p.getId())) return true;
        }
        return false;
    }

    /**
     * Lookup by id. For {@link Prompt#BUILTIN_ID}, returns the persisted
     * override if present, otherwise the resource-backed virtual builtin.
     */
    public synchronized Prompt getById(String id) {
        if (Prompt.BUILTIN_ID.equals(id)) {
            return getBuiltin();
        }
        ensureLoaded();
        for (Prompt p : prompts) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    /**
     * The always-present builtin prompt. Returns the persisted override if
     * present, otherwise the resource-backed virtual fallback from
     * {@code R.string.label_prompt} / {@code R.string.name_builtin_prompt}.
     */
    public synchronized Prompt getBuiltin() {
        ensureLoaded();
        for (Prompt p : prompts) {
            if (Prompt.BUILTIN_ID.equals(p.getId())) return p;
        }
        return buildBuiltin();
    }

    /**
     * Combined list: builtin first, then user prompts in insertion order.
     * If the builtin is overridden, the overridden entry from
     * {@link #prompts} is omitted here because {@link #getBuiltin()} already
     * surfaces it — avoiding a duplicate row in the editor UI.
     */
    public synchronized List<Prompt> getAllWithBuiltin() {
        List<Prompt> all = new ArrayList<>();
        all.add(getBuiltin());
        ensureLoaded();
        for (Prompt p : prompts) {
            if (Prompt.BUILTIN_ID.equals(p.getId())) continue;
            all.add(p);
        }
        return all;
    }

    public synchronized void add(Prompt newPrompt) {
        ensureLoaded();
        if (newPrompt.getId() == null || newPrompt.getId().isEmpty()) {
            newPrompt.setId(UUID.randomUUID().toString());
        }
        if (Prompt.BUILTIN_ID.equals(newPrompt.getId())) {
            // Upsert: replace the existing builtin override if any,
            // otherwise append. The "virtual builtin if no override" path
            // never reaches this branch — callers from the editor flow
            // arrive here only after the user has saved an edited body,
            // so we want to persist it.
            for (int i = 0; i < prompts.size(); i++) {
                if (Prompt.BUILTIN_ID.equals(prompts.get(i).getId())) {
                    newPrompt.setUpdatedAt(System.currentTimeMillis());
                    prompts.set(i, newPrompt);
                    save();
                    return;
                }
            }
            newPrompt.setUpdatedAt(System.currentTimeMillis());
            prompts.add(newPrompt);
            save();
            return;
        }
        prompts.add(newPrompt);
        save();
    }

    public synchronized void update(Prompt updated) {
        ensureLoaded();
        if (Prompt.BUILTIN_ID.equals(updated.getId())) {
            // Upsert builtin override: replace existing entry if any,
            // otherwise append (e.g. user edits the virtual builtin for the
            // first time — there is no prior entry on disk).
            updated.setUpdatedAt(System.currentTimeMillis());
            for (int i = 0; i < prompts.size(); i++) {
                if (Prompt.BUILTIN_ID.equals(prompts.get(i).getId())) {
                    prompts.set(i, updated);
                    save();
                    return;
                }
            }
            prompts.add(updated);
            save();
            return;
        }
        for (int i = 0; i < prompts.size(); i++) {
            if (prompts.get(i).getId().equals(updated.getId())) {
                updated.setUpdatedAt(System.currentTimeMillis());
                prompts.set(i, updated);
                save();
                return;
            }
        }
    }

    /**
     * Delete a user prompt, or — if {@code id == {@link Prompt#BUILTIN_ID}} —
     * clear the persisted builtin override (a "reset to default" action).
     * The virtual builtin slot is never removed; it always exists in
     * {@link #getBuiltin()} via the resource fallback.
     */
    public synchronized void delete(String id) {
        ensureLoaded();
        if (Prompt.BUILTIN_ID.equals(id)) {
            // Reset to the resource-backed default: drop the override entry.
            boolean removed = prompts.removeIf(p -> Prompt.BUILTIN_ID.equals(p.getId()));
            if (removed) {
                save();
                // Active prompt stays at BUILTIN_ID (no fallback needed —
                // it still maps to the virtual builtin).
            }
            return;
        }
        boolean removed = prompts.removeIf(p -> p.getId().equals(id));
        if (removed) {
            save();
            // If the deleted prompt was active, fall back to builtin.
            if (id.equals(prefs.getString(KEY_ACTIVE_PROMPT_ID, null))) {
                prefs.edit().putString(KEY_ACTIVE_PROMPT_ID, Prompt.BUILTIN_ID).apply();
            }
        }
    }

    /** Returns the active id; defaults to builtin if unset or stale. */
    public synchronized String getActiveId() {
        ensureLoaded();
        String id = prefs.getString(KEY_ACTIVE_PROMPT_ID, null);
        if (id == null) return Prompt.BUILTIN_ID;
        if (Prompt.BUILTIN_ID.equals(id)) return id;
        for (Prompt p : prompts) {
            if (p.getId().equals(id)) return id;
        }
        // Active prompt was deleted or never existed; revert to builtin.
        prefs.edit().putString(KEY_ACTIVE_PROMPT_ID, Prompt.BUILTIN_ID).apply();
        return Prompt.BUILTIN_ID;
    }

    /** Switch the active prompt. Pass {@code null} or {@link Prompt#BUILTIN_ID} for builtin. */
    public synchronized void setActiveId(String id) {
        ensureLoaded();
        String resolved = id;
        if (resolved == null) resolved = Prompt.BUILTIN_ID;
        // If the requested id is not a known user prompt, treat as builtin.
        if (!Prompt.BUILTIN_ID.equals(resolved)) {
            boolean known = false;
            for (Prompt p : prompts) {
                if (p.getId().equals(resolved)) { known = true; break; }
            }
            if (!known) resolved = Prompt.BUILTIN_ID;
        }
        prefs.edit().putString(KEY_ACTIVE_PROMPT_ID, resolved).apply();
    }

    /**
     * Hot-path helper for {@link PostProcessor}: returns the body text
     * to feed the LLM. Reads from in-memory cache after first load
     * (so it is safe to call on every transcription without disk I/O).
     */
    public synchronized String getActivePromptBody() {
        String id = getActiveId();
        Prompt p = getById(id);
        if (p != null) return p.getBody();
        // Stale active id (e.g. user prompt was deleted) — fall back to
        // the resource-backed virtual builtin.
        return context.getString(R.string.label_prompt);
    }

    /** Display label for the active prompt (the user-visible name). */
    public synchronized String getActivePromptName() {
        String id = getActiveId();
        Prompt p = getById(id);
        if (p != null) return p.getName();
        return context.getString(R.string.name_builtin_prompt);
    }

    // -------- import / export --------

    /**
     * Import a single prompt (no {@code prompts[]} wrapper required).
     * Renames on name collision. Rejects prompts with empty body.
     *
     * @throws JSONException if the JSON is malformed
     * @throws IllegalArgumentException if the imported prompt body is empty
     */
    public synchronized String importFromJson(String json) throws JSONException {
        ensureLoaded();
        JSONObject root = new JSONObject(json);
        Prompt imported = Prompt.fromJson(root);
        // Validate body is not empty — an empty-body prompt would send
        // a bare ${output} placeholder to the LLM.
        if (imported.getBody() == null || imported.getBody().trim().isEmpty()) {
            throw new IllegalArgumentException("Imported prompt body cannot be empty");
        }
        String baseName = imported.getName();
        if (baseName == null || baseName.trim().isEmpty()) {
            baseName = context.getString(R.string.name_prompt_default);
            imported.setName(baseName);
        }
        String finalName = baseName;
        int counter = 1;
        while (nameConflicts(finalName)) {
            finalName = baseName + " (" + counter + ")";
            counter++;
        }
        imported.setName(finalName);
        imported.setId(UUID.randomUUID().toString());
        imported.setUpdatedAt(System.currentTimeMillis());
        prompts.add(imported);
        save();
        return finalName;
    }

    /**
     * Serialize a single prompt to pretty JSON. The {@code id} is omitted.
     * The app-default (virtual) prompt is exported as an ordinary body-only
     * JSON template: importing it via {@link #importFromJson(String)} creates
     * a normal user prompt with a fresh UUID, so changes to the built-in
     * never leak into the live {@link R.string#label_prompt} source of truth.
     */
    public synchronized String exportToJson(String id) throws JSONException {
        ensureLoaded();
        Prompt p = getById(id);
        if (p == null) {
            throw new IllegalArgumentException("Prompt not found: " + id);
        }
        JSONObject obj = p.toJson();
        obj.remove("id");
        return obj.toString(2);
    }

    // -------- internals --------

    private boolean nameConflicts(String name) {
        if (name == null) return true;
        String builtinName = context.getString(R.string.name_builtin_prompt);
        if (builtinName.equalsIgnoreCase(name)) return true;
        for (Prompt p : prompts) {
            if (p.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private Prompt buildBuiltin() {
        return new Prompt(Prompt.BUILTIN_ID,
                context.getString(R.string.name_builtin_prompt),
                context.getString(R.string.label_prompt),
                0L);
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        migrateFromPreferences();
        loadFromDisk();
    }

    /**
     * One-shot migration: a single legacy {@code system_prompt} string
     * becomes a prompt named "Default (migrated)" and is auto-active.
     * Then the legacy key is removed. Idempotent: only fires once.
     */
    private void migrateFromPreferences() {
        try {
            String legacy = prefs.getString(KEY_LEGACY_SYSTEM_PROMPT, null);
            if (legacy == null || legacy.trim().isEmpty()) {
                return;
            }
            Log.i(TAG, "Migrating legacy system_prompt into prompts.json");
            Prompt migrated = Prompt.createNew(
                    context.getString(R.string.name_migrated_prompt), legacy);
            prompts.add(migrated);
            prefs.edit().putString(KEY_ACTIVE_PROMPT_ID, migrated.getId()).apply();
            prefs.edit().remove(KEY_LEGACY_SYSTEM_PROMPT).apply();
            save();
        } catch (Exception e) {
            Log.e(TAG, "Failed to migrate legacy system_prompt", e);
        }
    }

    private void loadFromDisk() {
        try (java.io.InputStream is = file.openRead();
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String json = sb.toString();
            if (json.isEmpty()) return;
            JSONObject root = new JSONObject(new JSONTokener(json));
            JSONArray arr = root.optJSONArray("prompts");                if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    Prompt p = Prompt.fromJson(obj);
                    // Skip duplicates (older writes could collide).
                    boolean dup = false;
                    for (Prompt existing : prompts) {
                        if (existing.getId().equals(p.getId())) { dup = true; break; }
                    }
                    if (!dup) prompts.add(p);
                }
            }
            // Read the builtin override from its dedicated slot. We construct
            // the Prompt directly (bypassing Prompt.fromJson()'s BUILTIN_ID
            // strip) so the magic id survives the round-trip. The "prompts[]"
            // path above never holds the override, so there's no conflict
            // with the strip behaviour.
            JSONObject override = root.optJSONObject("builtin_override");
            if (override != null) {
                String name = override.optString("name",
                        context.getString(R.string.name_builtin_prompt));
                String body = override.optString("body",
                        context.getString(R.string.label_prompt));
                long updatedAt = override.optLong("updatedAt", 0L);
                if (!name.isEmpty() && !body.isEmpty()) {
                    prompts.add(new Prompt(Prompt.BUILTIN_ID, name, body, updatedAt));
                }
            }
        } catch (FileNotFoundException notFound) {
            // First launch — OK.
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load prompts.json; starting empty", e);
        }
    }

    private void save() {
        FileOutputStream fos = null;
        try {
            fos = file.startWrite();
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            // The builtin override is persisted in its own top-level slot
            // rather than mixed into the prompts[] array, because
            // Prompt.fromJson() strips the magic BUILTIN_ID on read (a
            // security property: never resurrect the magic id from JSON).
            // Writing here and reading back via a dedicated path keeps the
            // override id intact across reloads.
            JSONObject builtinJson = null;
            for (Prompt p : prompts) {
                if (Prompt.BUILTIN_ID.equals(p.getId())) {
                    builtinJson = p.toJson();
                    builtinJson.remove("id");
                    continue;
                }
                arr.put(p.toJson());
            }
            root.put("prompts", arr);
            if (builtinJson != null) {
                root.put("builtin_override", builtinJson);
            }
            root.put("schema_version", 2);
            fos.write(root.toString().getBytes("UTF-8"));
            file.finishWrite(fos);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to save prompts.json", e);
            if (fos != null) {
                file.failWrite(fos);
            }
        }
    }
}
