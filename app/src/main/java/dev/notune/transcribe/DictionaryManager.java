package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DictionaryManager {
    private static final String TAG = "DictionaryManager";
    private static final String FILE_NAME = "dictionaries.json";
    private static final String KEY_HOTWORDS = "custom_hotwords";
    private static final String PREFS_NAME = "transcribe_settings";

    private final Context context;
    private final File dictionariesFile;
    private List<Dictionary> dictionaries;
    private boolean loaded = false;

    public DictionaryManager(Context context) {
        this.context = context.getApplicationContext();
        this.dictionariesFile = new File(this.context.getFilesDir(), FILE_NAME);
        this.dictionaries = new ArrayList<>();
    }

    private void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            this.dictionaries = load();
            migrateFromPreferences();
        }
    }

    /**
     * Loads dictionaries from {@code files/dictionaries.json}. The schema-v2
     * layout (introduced with the editable default) keeps the user
     * dictionaries in {@code "dictionaries"} and slots the persisted
     * default-dictionary override into {@code "default_override"}. v1
     * files (no {@code default_override}) still parse cleanly because
     * {@link JSONObject#optJSONObject(String)} returns {@code null} and we
     * fall through to the virtual default.
     *
     * <p>The override is constructed manually with
     * {@link Dictionary#DEFAULT_ID} rather than via {@link Dictionary#fromJson}
     * because {@code fromJson} strips {@code DEFAULT_ID} (a security property:
     * never resurrect the magic id from JSON). The user-dictionaries array
     * still goes through {@code fromJson}, which is fine because the override
     * is never written there.
     */
    private List<Dictionary> load() {
        List<Dictionary> list = new ArrayList<>();
        if (!dictionariesFile.exists()) return list;

        try (FileInputStream fis = new FileInputStream(dictionariesFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("dictionaries");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(Dictionary.fromJson(arr.getJSONObject(i)));
                }
            }
            JSONObject override = root.optJSONObject("default_override");
            if (override != null) {
                String name = override.optString("name",
                        context.getString(R.string.name_default_dictionary));
                boolean enabled = override.optBoolean("enabled", true);
                List<String> words = new ArrayList<>();
                JSONArray wordsArr = override.optJSONArray("words");
                if (wordsArr != null) {
                    for (int i = 0; i < wordsArr.length(); i++) {
                        String w = wordsArr.optString(i, null);
                        if (w != null && !w.isEmpty()) words.add(w);
                    }
                }
                if (!name.isEmpty()) {
                    list.add(new Dictionary(Dictionary.DEFAULT_ID, name, words, enabled));
                }
            }
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to load dictionaries", e);
        }
        return list;
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            JSONObject overrideJson = null;
            for (Dictionary d : dictionaries) {
                if (d.isDefault()) {
                    // Persist the default override in its own top-level
                    // slot — never mixed into the user-dictionaries array —
                    // so the magic id survives the round-trip
                    // (Dictionary.fromJson strips DEFAULT_ID on read).
                    JSONObject o = d.toJson();
                    o.remove("id");
                    overrideJson = o;
                    continue;
                }
                arr.put(d.toJson());
            }
            root.put("dictionaries", arr);
            if (overrideJson != null) {
                root.put("default_override", overrideJson);
            }
            root.put("schema_version", 2);

            try (FileOutputStream fos = new FileOutputStream(dictionariesFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos)) {
                writer.write(root.toString());
            }
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to save dictionaries", e);
        }
    }

    /**
     * Migrates the legacy {@code custom_hotwords} StringSet into the new
     * {@code default_override} slot rather than creating a "Default" user
     * dictionary. This aligns with the user's original intent — "my words"
     * was always the implicit default — and avoids two parallel slots
     * (one user-created "Default" + one virtual default).
     *
     * <p>Idempotent: only fires once because the prefs key is removed.
     */
    private void migrateFromPreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> oldWords = prefs.getStringSet(KEY_HOTWORDS, null);
        if (oldWords != null && !oldWords.isEmpty()) {
            List<String> words = new ArrayList<>(oldWords);
            Dictionary defaultDict = new Dictionary(
                    Dictionary.DEFAULT_ID,
                    context.getString(R.string.name_default_dictionary),
                    words,
                    true);
            dictionaries.add(defaultDict);
            save();
            prefs.edit().remove(KEY_HOTWORDS).apply();
        }
    }

    /**
     * All user-saved dictionaries plus any default override (id =
     * {@link Dictionary#DEFAULT_ID}). Excludes the virtual-only default when
     * no override exists — callers that want a flat user-only list should
     * {@code filter(d -&gt; !d.isDefault())}.
     */
    public List<Dictionary> getAll() {
        ensureLoaded();
        return new ArrayList<>(dictionaries);
    }

    /**
     * Always-present default "My words" entry. Returns the persisted override
     * if present, otherwise the resource-backed virtual fallback
     * ({@code R.string.name_default_dictionary} with empty words + enabled).
     */
    public Dictionary getDefault() {
        ensureLoaded();
        for (Dictionary d : dictionaries) {
            if (d.isDefault()) return d;
        }
        return new Dictionary(
                Dictionary.DEFAULT_ID,
                context.getString(R.string.name_default_dictionary),
                new ArrayList<>(),
                true);
    }

    /**
     * True if the user has saved a default override (id =
     * {@link Dictionary#DEFAULT_ID}) to disk. UI uses this to render the
     * "My words (customized)" subtitle and offer the "Reset to default"
     * affordance.
     */
    public boolean isDefaultOverridden() {
        ensureLoaded();
        for (Dictionary d : dictionaries) {
            if (d.isDefault()) return true;
        }
        return false;
    }

    public Set<String> getActiveWords() {
        ensureLoaded();
        Set<String> allWords = new HashSet<>();
        for (Dictionary d : dictionaries) {
            if (d.isEnabled() && d.getWords() != null) {
                allWords.addAll(d.getWords());
            }
        }
        return allWords;
    }

    public List<String> getActiveWordsList() {
        ensureLoaded();
        // Iterate so user dictionaries come BEFORE the default — WordCorrector
        // only ever short-circuits on the first match by exact word string,
        // and the default ships empty, so for non-empty word collisions the
        // last-collected entry "wins" via ArrayList.order; in practice both
        // sides contain user-curated content and dedupe handles duplicates.
        // The default is appended last as a stable fallback when no user
        // dictionary covers a particular word.
        List<Dictionary> ordered = new ArrayList<>();
        Dictionary def = null;
        for (Dictionary d : dictionaries) {
            if (d.isDefault()) def = d;
            else ordered.add(d);
        }
        if (def == null) def = getDefault();
        ordered.add(def);

        List<String> allWords = new ArrayList<>();
        for (Dictionary d : ordered) {
            if (d.isEnabled() && d.getWords() != null) {
                for (String word : d.getWords()) {
                    int eqIdx = word.indexOf('=');
                    if (eqIdx >= 0) {
                        allWords.add(word.substring(eqIdx + 1));
                    } else {
                        allWords.add(word);
                    }
                }
            }
        }
        return allWords;
    }

    public Dictionary getById(String id) {
        ensureLoaded();
        if (Dictionary.DEFAULT_ID.equals(id)) return getDefault();
        for (Dictionary d : dictionaries) {
            if (d.getId().equals(id)) return d;
        }
        return null;
    }

    /**
     * Add a new user dictionary. The {@link Dictionary#DEFAULT_ID} slot is
     * never created via this path — it is reserved for the persisted override
     * driven by {@link #updateDictionary(Dictionary)} from the editor flow.
     * If a caller accidentally supplies the magic id, swap it for a random
     * UUID so the user dict pool never collides with the override.
     */
    public void addDictionary(Dictionary dictionary) {
        ensureLoaded();
        if (Dictionary.DEFAULT_ID.equals(dictionary.getId())) {
            dictionary.setId(java.util.UUID.randomUUID().toString());
        }
        dictionaries.add(dictionary);
        save();
    }

    /**
     * Persist user edits to a dictionary. For the default slot, this is the
     * "save override" path: the editor flow calls
     * {@link DictionaryManager#updateDictionary} with the
     * {@link Dictionary#DEFAULT_ID} id; we upsert the entry so the override
     * round-trips cleanly through the v2 schema's {@code default_override}
     * slot. For user dictionaries this is the existing replace-by-id path.
     */
    public void updateDictionary(Dictionary updated) {
        ensureLoaded();
        if (updated.isDefault()) {
            for (int i = 0; i < dictionaries.size(); i++) {
                if (dictionaries.get(i).isDefault()) {
                    dictionaries.set(i, updated);
                    save();
                    return;
                }
            }
            dictionaries.add(updated);
            save();
            return;
        }
        for (int i = 0; i < dictionaries.size(); i++) {
            if (dictionaries.get(i).getId().equals(updated.getId())) {
                dictionaries.set(i, updated);
                break;
            }
        }
        save();
    }

    /**
     * Delete a user dictionary, or — if id is {@link Dictionary#DEFAULT_ID} —
     * treat the call as a "reset to default": remove the persisted override
     * from disk so future {@link #getDefault()} reads fall back to the
     * virtual entry backed by {@code R.string.name_default_dictionary}.
     */
    public void deleteDictionary(String id) {
        ensureLoaded();
        if (Dictionary.DEFAULT_ID.equals(id)) {
            boolean removed = dictionaries.removeIf(d -> d.isDefault());
            if (removed) save();
            return;
        }
        dictionaries.removeIf(d -> d.getId().equals(id));
        save();
    }

    public void addWord(String dictId, String word) {
        ensureLoaded();
        if (Dictionary.DEFAULT_ID.equals(dictId)) {
            // DEFAULT_ID may point at the in-memory virtual fallback when no
            // override is on disk yet. Mutating the virtual instance is
            // pointless unless we promote it to an override first; route
            // through updateDictionary, which upserts into `dictionaries`
            // so the next save() actually writes the default_override slot.
            Dictionary def = getDefault();
            if (!def.getWords().contains(word)) {
                def.getWords().add(word);
                updateDictionary(def);
            }
            return;
        }
        Dictionary d = getById(dictId);
        if (d != null && !d.getWords().contains(word)) {
            d.getWords().add(word);
            save();
        }
    }

    public void removeWord(String dictId, String word) {
        ensureLoaded();
        if (Dictionary.DEFAULT_ID.equals(dictId)) {
            Dictionary def = getDefault();
            if (def.getWords().contains(word)) {
                def.getWords().remove(word);
                updateDictionary(def);
            }
            return;
        }
        Dictionary d = getById(dictId);
        if (d != null) {
            d.getWords().remove(word);
            save();
        }
    }

    public void updateWord(String dictId, String oldWord, String newWord) {
        ensureLoaded();
        if (Dictionary.DEFAULT_ID.equals(dictId)) {
            Dictionary def = getDefault();
            int idx = def.getWords().indexOf(oldWord);
            if (idx >= 0) {
                def.getWords().set(idx, newWord);
                updateDictionary(def);
            }
            return;
        }
        Dictionary d = getById(dictId);
        if (d != null) {
            int idx = d.getWords().indexOf(oldWord);
            if (idx >= 0) {
                d.getWords().set(idx, newWord);
                save();
            }
        }
    }

    /**
     * Serialize a single dictionary to pretty JSON. The {@code id} is
     * stripped on output — parallel to {@code Prompt.exportToJson} — so
     * importing the same JSON via {@link #importDictionary} yields a fresh
     * user dictionary (the magic {@link Dictionary#DEFAULT_ID} cannot be
     * resurrected from JSON).
     */
    public void exportDictionary(String id, OutputStream outputStream) throws JSONException, IOException {
        ensureLoaded();
        Dictionary d = getById(id);
        if (d == null) return;
        JSONObject json = d.toJson();
        json.remove("id");
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        writer.write(json.toString(2));
        writer.close();
    }

    public String importDictionary(InputStream inputStream) throws JSONException, IOException {
        ensureLoaded();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();

        Dictionary imported = Dictionary.fromJson(new JSONObject(sb.toString()));

        // Auto-rename if name conflicts (against both user dicts AND the
        // virtual default's name, so a JSON titled "My words" doesn't
        // silently collide with the override slot).
        String baseName = imported.getName();
        String finalName = baseName;
        int counter = 1;
        while (nameExists(finalName)) {
            finalName = baseName + " (" + counter + ")";
            counter++;
        }
        imported.setName(finalName);

        // Assign new UUID. fromJson() also strips DEFAULT_ID if it slipped
        // into the import, but we re-assign to be explicit: imported
        // dictionaries always join the user pool, never the override slot.
        imported.setId(java.util.UUID.randomUUID().toString());

        dictionaries.add(imported);
        save();
        return finalName;
    }

    private boolean nameExists(String name) {
        if (name == null) return true;
        String defaultName = context.getString(R.string.name_default_dictionary);
        if (defaultName.equalsIgnoreCase(name)) return true;
        for (Dictionary d : dictionaries) {
            if (d.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
