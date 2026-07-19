package dev.notune.transcribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Dictionary {
    /**
     * Magic id used by {@link DictionaryManager} to identify the always-present
     * "My words" default dictionary. Like {@link Prompt#BUILTIN_ID}, it is a
     * sentinel that lets callers address the default slot without persisting a
     * stable user UUID.
     *
     * <p>Persisted overrides of the default use this id in
     * {@link DictionaryManager}'s schema-v2 {@code default_override} JSON slot;
     * never resurrected from user-supplied JSON: see
     * {@link #fromJson(JSONObject)}.
     */
    public static final String DEFAULT_ID = "__default__";

    private String id;
    private String name;
    private List<String> words;
    private boolean enabled;

    public Dictionary(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.words = new ArrayList<>();
        this.enabled = true;
    }

    public Dictionary(String id, String name, List<String> words, boolean enabled) {
        this.id = id;
        this.name = name;
        this.words = words;
        this.enabled = enabled;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getWords() { return words; }
    public void setWords(List<String> words) { this.words = words; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWordCount() { return words != null ? words.size() : 0; }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        // Strip the magic DEFAULT_ID when serializing the default slot —
        // parallel to the security property in Dictionary.fromJson(). Even
        // though DictionaryManager.save() and exportDictionary() both strip
        // defensively, centralizing the strip here prevents any future caller
        // (an export variant, an instrumentation fixture, a debug dump) from
        // leaking the magic id into JSON. fromJson would then replace it
        // with a random UUID, silently corrupting the override slot.
        obj.put("id", isDefault() ? "" : (id == null ? "" : id));
        obj.put("name", name);
        obj.put("enabled", enabled);
        JSONArray arr = new JSONArray();
        if (words != null) {
            for (String w : words) {
                arr.put(w);
            }
        }
        obj.put("words", arr);
        return obj;
    }

    /**
     * True iff this is the always-present "My words" default entry whose id
     * is the magic {@link #DEFAULT_ID} sentinel. Used by the UI to swap in a
     * virtual-row layout (with Reset / no Delete) and by the repository to
     * short-circuit name-conflict checks against the override slot.
     */
    public boolean isDefault() {
        return DEFAULT_ID.equals(id);
    }

    public static Dictionary fromJson(JSONObject obj) throws JSONException {
        String id = obj.optString("id", UUID.randomUUID().toString());
        // Never resurrect the magic default id from JSON. We only persist
        // user-friendly exports whose id field is stripped, but guards are
        // cheap; round-tripping a custom-hotwords migration that happens to
        // carry the id would otherwise hijack the default slot on the next
        // launch.
        if (DEFAULT_ID.equals(id)) {
            id = UUID.randomUUID().toString();
        }
        String name = obj.getString("name");
        boolean enabled = obj.optBoolean("enabled", true);
        List<String> words = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("words");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                words.add(arr.getString(i));
            }
        }
        return new Dictionary(id, name, words, enabled);
    }
}
