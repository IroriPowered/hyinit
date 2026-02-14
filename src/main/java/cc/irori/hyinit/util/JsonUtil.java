package cc.irori.hyinit.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public final class JsonUtil {

    // Private constructor to prevent instantiation
    private JsonUtil() {}

    public static List<String> readStringOrStringArray(JsonElement element) {
        if (element == null || element.isJsonNull()) return List.of();

        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String v = element.getAsString();
            return v == null ? List.of() : List.of(v);
        }

        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            List<String> out = new ArrayList<>(arr.size());
            for (JsonElement it : arr) {
                if (it != null
                        && it.isJsonPrimitive()
                        && it.getAsJsonPrimitive().isString()) {
                    out.add(it.getAsString());
                }
            }
            return out;
        }

        return List.of();
    }

    public static JsonObject getObject(JsonObject root, String key) {
        if (!root.has(key)) return null;
        JsonElement element = root.get(key);
        if (element != null && element.isJsonObject()) return element.getAsJsonObject();
        return null;
    }
}
