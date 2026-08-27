package dev.thedocruby.resounding.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.tag.Ident;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and writes the baked material cache.
 *
 * <p>Kept here rather than in the registry so Gson stays confined to this package and so the codec
 * can be round-tripped in tests without Minecraft.
 *
 * <p>The previous implementation was unsound in three separate ways. It built its Gson type token
 * from a bare type variable, which erases to {@code Object} and worked only by accident — and which
 * Gson 2.11+ rejects outright. It then read fields with
 * {@code (double) value.getOrDefault("impedance", 350)}, which throws {@code ClassCastException}
 * whenever the key is absent, because the {@code 350} default is an {@code Integer}. And it left its
 * reader open. Here the defaults are doubles, absent keys fall back rather than throw, and callers
 * own the streams.
 */
public final class MaterialCache {

    private MaterialCache() {}

    /** Air-like defaults, used for any field a cache entry omits. */
    private static final double DEFAULT_IMPEDANCE = 412.0D;
    private static final double DEFAULT_PERMEATION = 1.0D;
    private static final double DEFAULT_STATE = 0.0D;

    /**
     * Reads a cache document.
     *
     * <p>Never throws on malformed content: an unreadable document yields an empty map and a
     * malformed entry is skipped, because a corrupt cache is always recoverable by regenerating.
     *
     * @return materials by id, sorted; empty if nothing usable could be read
     */
    public static Map<Ident, Material> read(Reader reader) {
        Map<Ident, Material> out = new TreeMap<>();
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            return out;
        }
        if (root == null || !root.isJsonObject()) return out;

        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            try {
                out.put(Ident.parse(entry.getKey()), new Material(
                        number(value, "impedance", DEFAULT_IMPEDANCE),
                        number(value, "permeation", DEFAULT_PERMEATION),
                        number(value, "state", DEFAULT_STATE)));
            } catch (IllegalArgumentException e) {
                // malformed id: skip this entry, keep the rest
            }
        }
        return out;
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsDouble();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Writes a cache document, ordered by id.
     *
     * <p>Sorted because an unordered map reorders the file between launches, churning it in diffs
     * and in version control for no reason.
     */
    public static void write(Writer writer, Map<Ident, Material> materials) throws IOException {
        Map<String, Material> sorted = new TreeMap<>();
        materials.forEach((ident, material) -> sorted.put(ident.toString(), material));
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        writer.write(gson.toJson(sorted));
    }
}
