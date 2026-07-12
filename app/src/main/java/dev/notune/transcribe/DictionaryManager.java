package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final String FILE_NAME = "dictionaries.json";
    private static final String KEY_HOTWORDS = "custom_hotwords";
    private static final String PREFS_NAME = "transcribe_settings";

    private final Context context;
    private final File dictionariesFile;
    private List<Dictionary> dictionaries;

    public DictionaryManager(Context context) {
        this.context = context.getApplicationContext();
        this.dictionariesFile = new File(this.context.getFilesDir(), FILE_NAME);
        this.dictionaries = load();
        migrateFromPreferences();
    }

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
        } catch (JSONException | IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Dictionary d : dictionaries) {
                arr.put(d.toJson());
            }
            root.put("dictionaries", arr);

            try (FileOutputStream fos = new FileOutputStream(dictionariesFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos)) {
                writer.write(root.toString());
            }
        } catch (JSONException | IOException e) {
            e.printStackTrace();
        }
    }

    private void migrateFromPreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> oldWords = prefs.getStringSet(KEY_HOTWORDS, null);
        if (oldWords != null && !oldWords.isEmpty()) {
            List<String> words = new ArrayList<>(oldWords);
            Dictionary defaultDict = new Dictionary("Default");
            defaultDict.setWords(words);
            dictionaries.add(defaultDict);
            save();
            prefs.edit().remove(KEY_HOTWORDS).apply();
        }
    }

    public List<Dictionary> getAll() {
        return new ArrayList<>(dictionaries);
    }

    public Set<String> getActiveWords() {
        Set<String> allWords = new HashSet<>();
        for (Dictionary d : dictionaries) {
            if (d.isEnabled() && d.getWords() != null) {
                allWords.addAll(d.getWords());
            }
        }
        return allWords;
    }

    public List<String> getActiveWordsList() {
        List<String> allWords = new ArrayList<>();
        for (Dictionary d : dictionaries) {
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
        for (Dictionary d : dictionaries) {
            if (d.getId().equals(id)) return d;
        }
        return null;
    }

    public void addDictionary(Dictionary dictionary) {
        dictionaries.add(dictionary);
        save();
    }

    public void updateDictionary(Dictionary updated) {
        for (int i = 0; i < dictionaries.size(); i++) {
            if (dictionaries.get(i).getId().equals(updated.getId())) {
                dictionaries.set(i, updated);
                break;
            }
        }
        save();
    }

    public void deleteDictionary(String id) {
        dictionaries.removeIf(d -> d.getId().equals(id));
        save();
    }

    public void addWord(String dictId, String word) {
        Dictionary d = getById(dictId);
        if (d != null && !d.getWords().contains(word)) {
            d.getWords().add(word);
            save();
        }
    }

    public void removeWord(String dictId, String word) {
        Dictionary d = getById(dictId);
        if (d != null) {
            d.getWords().remove(word);
            save();
        }
    }

    public void updateWord(String dictId, String oldWord, String newWord) {
        Dictionary d = getById(dictId);
        if (d != null) {
            int idx = d.getWords().indexOf(oldWord);
            if (idx >= 0) {
                d.getWords().set(idx, newWord);
                save();
            }
        }
    }

    public void exportDictionary(String id, OutputStream outputStream) throws JSONException, IOException {
        Dictionary d = getById(id);
        if (d == null) return;
        JSONObject json = d.toJson();
        // Remove internal id from export
        json.remove("id");
        OutputStreamWriter writer = new OutputStreamWriter(outputStream);
        writer.write(json.toString(2));
        writer.close();
    }

    public String importDictionary(InputStream inputStream) throws JSONException, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();

        Dictionary imported = Dictionary.fromJson(new JSONObject(sb.toString()));

        // Auto-rename if name conflicts
        String baseName = imported.getName();
        String finalName = baseName;
        int counter = 1;
        while (nameExists(finalName)) {
            finalName = baseName + " (" + counter + ")";
            counter++;
        }
        imported.setName(finalName);

        // Assign new UUID to avoid collisions
        imported.setId(java.util.UUID.randomUUID().toString());

        dictionaries.add(imported);
        save();
        return finalName;
    }

    private boolean nameExists(String name) {
        for (Dictionary d : dictionaries) {
            if (d.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
