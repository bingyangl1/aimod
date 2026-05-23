package com.aimod.ai.llm;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ActionDescriptor {
    private final String type;
    private final JsonObject params;
    public ActionDescriptor(String type, JsonObject params) {
        this.type = type != null ? type : "";
        this.params = params != null ? params : new JsonObject();
    }
    public String getType() { return type; }
    public JsonObject getParams() { return params; }
    public String getString(String key, String def) {
        if (params.has(key) && !params.get(key).isJsonNull()) return params.get(key).getAsString();
        return def;
    }
    public int getInt(String key, int def) {
        if (params.has(key) && !params.get(key).isJsonNull()) {
            try { return params.get(key).getAsInt(); } catch (Exception e) { return def; }
        }
        return def;
    }
    public double getDouble(String key, double def) {
        if (params.has(key) && !params.get(key).isJsonNull()) {
            try { return params.get(key).getAsDouble(); } catch (Exception e) { return def; }
        }
        return def;
    }
    public Map<String, Integer> getItemsMap(String arrayKey) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!params.has(arrayKey) || !params.get(arrayKey).isJsonArray()) return result;
        for (int i = 0; i < params.getAsJsonArray(arrayKey).size(); i++) {
            JsonObject itemObj = params.getAsJsonArray(arrayKey).get(i).getAsJsonObject();
            String itemId = "";
            if (itemObj.has("item_id") && !itemObj.get("item_id").isJsonNull()) itemId = itemObj.get("item_id").getAsString();
            else if (itemObj.has("item") && !itemObj.get("item").isJsonNull()) itemId = itemObj.get("item").getAsString();
            int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 1;
            if (!itemId.isBlank()) result.merge(itemId, count, Integer::sum);
        }
        return result;
    }
    @Override public String toString() { return "ActionDescriptor{type=" + type + ", params=" + params + "}"; }
}