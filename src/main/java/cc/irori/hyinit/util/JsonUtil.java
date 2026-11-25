package cc.irori.hyinit.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public static String toPrettyJson(Object value, boolean serializeNulls) {
        StringWriter out = new StringWriter();
        try (JsonWriter writer = new JsonWriter(out)) {
            writer.setIndent("  ");
            writer.setHtmlSafe(true);
            writer.setSerializeNulls(serializeNulls);
            write(writer, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void write(JsonWriter writer, Object value) throws IOException {
        switch (value) {
            case null -> writer.nullValue();
            case String text -> writer.value(text);
            case Boolean flag -> writer.value(flag);
            case Number number -> writer.value(number);
            case Map<?, ?> map -> {
                writer.beginObject();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    writer.name(String.valueOf(entry.getKey()));
                    write(writer, entry.getValue());
                }
                writer.endObject();
            }
            case Iterable<?> list -> {
                writer.beginArray();
                for (Object element : list) {
                    write(writer, element);
                }
                writer.endArray();
            }
            default ->
                throw new IllegalArgumentException(
                        "Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    public static JsonObject getObject(JsonObject root, String key) {
        if (!root.has(key)) return null;
        JsonElement element = root.get(key);
        if (element != null && element.isJsonObject()) return element.getAsJsonObject();
        return null;
    }
}
