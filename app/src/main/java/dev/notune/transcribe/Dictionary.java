package dev.notune.transcribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Dictionary {
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
        obj.put("id", id);
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

    public static Dictionary fromJson(JSONObject obj) throws JSONException {
        String id = obj.optString("id", UUID.randomUUID().toString());
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
