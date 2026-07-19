package dev.notune.transcribe;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * One post-processing prompt (a named, edited body used by
 * {@link PostProcessor}). Mirrors the {@link Dictionary} shape so the
 * repository layer can persist them with a 1:1 port while also
 * supporting a non-persistent "builtin" entry that comes from
 * {@code R.string.label_prompt}.
 *
 * <p>{@link #BUILTIN_ID} is a magic string used by
 * {@link PromptsRepository} to identify the uneditable, in-R.string
 * default. It is never persisted.
 */
public class Prompt {
    /** Magic id for the always-available built-in default prompt. */
    public static final String BUILTIN_ID = "__builtin__";

    private String id;
    private String name;
    private String body;
    private long updatedAt;

    public Prompt(String id, String name, String body, long updatedAt) {
        this.id = id;
        this.name = name;
        this.body = body;
        this.updatedAt = updatedAt;
    }

    /** Build a brand-new randomized prompt. */
    public static Prompt createNew(String name, String body) {
        return new Prompt(UUID.randomUUID().toString(), name, body,
                System.currentTimeMillis());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** True iff this prompt is the always-available built-in default. */
    public boolean isBuiltin() {
        return BUILTIN_ID.equals(id);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id == null ? "" : id);
        obj.put("name", name == null ? "" : name);
        obj.put("body", body == null ? "" : body);
        obj.put("updatedAt", updatedAt);
        return obj;
    }

    public static Prompt fromJson(JSONObject obj) throws JSONException {
        String id = obj.optString("id", UUID.randomUUID().toString());
        String name = obj.optString("name", "Unnamed");
        String body = obj.optString("body", "");
        long updatedAt = obj.optLong("updatedAt", System.currentTimeMillis());
        // Never resurrect the magic builtin id from JSON — we only persist user prompts.
        if (BUILTIN_ID.equals(id)) {
            id = UUID.randomUUID().toString();
        }
        return new Prompt(id, name, body, updatedAt);
    }
}
