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
 * written JSON blob. The "builtin" prompt (which comes from
 * {@code R.string.label_prompt}) is virtual and never written to disk.
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

    /** All user-saved prompts (excluding the always-present builtin). */
    public synchronized List<Prompt> getUserPrompts() {
        ensureLoaded();
        return new ArrayList<>(prompts);
    }

    /** Lookup by id; returns the virtual builtin for {@link Prompt#BUILTIN_ID}. */
    public synchronized Prompt getById(String id) {
        if (Prompt.BUILTIN_ID.equals(id)) {
            return buildBuiltin();
        }
        ensureLoaded();
        for (Prompt p : prompts) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    /** The always-present builtin prompt (read-only virtual entry). */
    public synchronized Prompt getBuiltin() {
        return buildBuiltin();
    }

    /** Combined list: builtin first, then user prompts in insertion order. */
    public synchronized List<Prompt> getAllWithBuiltin() {
        List<Prompt> all = new ArrayList<>();
        all.add(buildBuiltin());
        ensureLoaded();
        all.addAll(prompts);
        return all;
    }

    public synchronized void add(Prompt newPrompt) {
        ensureLoaded();
        if (Prompt.BUILTIN_ID.equals(newPrompt.getId())) {
            throw new IllegalArgumentException("Cannot persist builtin prompt");
        }
        if (newPrompt.getId() == null || newPrompt.getId().isEmpty()) {
            newPrompt.setId(UUID.randomUUID().toString());
        }
        prompts.add(newPrompt);
        save();
    }

    public synchronized void update(Prompt updated) {
        ensureLoaded();
        if (Prompt.BUILTIN_ID.equals(updated.getId())) {
            throw new IllegalArgumentException("Cannot edit builtin prompt");
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

    public synchronized void delete(String id) {
        ensureLoaded();
        if (Prompt.BUILTIN_ID.equals(id)) {
            throw new IllegalArgumentException("Cannot delete builtin prompt");
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
        if (Prompt.BUILTIN_ID.equals(id)) {
            return context.getString(R.string.label_prompt);
        }
        Prompt p = getById(id);
        return p != null ? p.getBody() : context.getString(R.string.label_prompt);
    }

    /** Display label for the active prompt (the user-visible name). */
    public synchronized String getActivePromptName() {
        String id = getActiveId();
        if (Prompt.BUILTIN_ID.equals(id)) {
            return context.getString(R.string.name_builtin_prompt);
        }
        Prompt p = getById(id);
        return p != null ? p.getName() : context.getString(R.string.name_builtin_prompt);
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

    /** Serialize a single prompt to pretty JSON. The id is omitted. */
    public synchronized String exportToJson(String id) throws JSONException {
        ensureLoaded();
        if (Prompt.BUILTIN_ID.equals(id)) {
            throw new IllegalArgumentException("Cannot export builtin prompt");
        }
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
            JSONArray arr = root.optJSONArray("prompts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    Prompt p = Prompt.fromJson(obj);
                    // Defensive: skip if id collides with builtin (shouldn't happen, but guard).
                    if (Prompt.BUILTIN_ID.equals(p.getId())) continue;
                    // Skip duplicates (older writes could collide).
                    boolean dup = false;
                    for (Prompt existing : prompts) {
                        if (existing.getId().equals(p.getId())) { dup = true; break; }
                    }
                    if (!dup) prompts.add(p);
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
            for (Prompt p : prompts) {
                arr.put(p.toJson());
            }
            root.put("prompts", arr);
            root.put("schema_version", 1);
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
