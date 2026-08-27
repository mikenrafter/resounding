package dev.thedocruby.resounding.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.thedocruby.resounding.material.RawMaterialDef;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.RawTagDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place Gson is allowed to appear.
 *
 * <p>Everything under {@code tag/} and {@code material/} takes plain Java values, which is what lets
 * the pipeline be exercised without a JSON library or a game. Parsing lives here, and so does
 * failure: malformed input becomes a {@link dev.thedocruby.resounding.tag.Diagnostic.ParseError} on
 * the returned {@link Layer}, never an exception. The previous code let
 * {@code JsonSyntaxException} propagate out of resource-pack reading, which is to say out of chunk
 * loading — one malformed pack crashed the world.
 */
public final class JsonAdapter {

    private JsonAdapter() {}

    /**
     * Parses a {@code resounding.tags.json} document.
     *
     * <p>Each entry maps a tag id to an object with any of {@code patterns}, {@code blocks},
     * {@code tagPatterns}, {@code tags}, {@code replace}.
     *
     * @param name source label used in diagnostics
     * @return a layer holding whatever parsed, plus diagnostics for whatever did not; entries that
     *         fail individually do not discard the rest of the document
     */
    public static Layer parseTags(String name, String json) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (json == null) {
            diagnostics.add(new Diagnostic.ParseError(name, "JSON content is null"));
            return new Layer(name, Map.of(), Map.of(), diagnostics);
        }

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (Exception e) {
            diagnostics.add(new Diagnostic.ParseError(name, e.getMessage()));
            return new Layer(name, Map.of(), Map.of(), diagnostics);
        }

        if (rootElement == null || !rootElement.isJsonObject()) {
            diagnostics.add(new Diagnostic.ParseError(name, "Root JSON element is not an object"));
            return new Layer(name, Map.of(), Map.of(), diagnostics);
        }

        JsonObject root = rootElement.getAsJsonObject();
        Map<Ident, RawTagDef> tags = new LinkedHashMap<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            JsonElement val = entry.getValue();

            Ident tagId;
            try {
                tagId = Ident.parse(key);
            } catch (IllegalArgumentException e) {
                diagnostics.add(new Diagnostic.ParseError(name, "Invalid tag identifier '" + key + "': " + e.getMessage()));
                continue;
            }

            if (!val.isJsonObject()) {
                diagnostics.add(new Diagnostic.ParseError(name, "Entry for tag '" + key + "' is not a JSON object"));
                continue;
            }

            try {
                JsonObject obj = val.getAsJsonObject();
                List<String> patterns = getStringList(obj, "patterns");
                Set<Ident> blocks = getIdentSet(obj, "blocks", name, diagnostics);
                List<String> tagPatterns = getStringList(obj, "tagPatterns");
                Set<Ident> includedTags = getIdentSet(obj, "tags", name, diagnostics);
                boolean replace = obj.has("replace") && obj.get("replace").isJsonPrimitive() && obj.get("replace").getAsBoolean();

                tags.put(tagId, new RawTagDef(patterns, blocks, tagPatterns, includedTags, replace));
            } catch (Exception e) {
                diagnostics.add(new Diagnostic.ParseError(name, "Error parsing tag '" + key + "': " + e.getMessage()));
            }
        }

        return new Layer(name, tags, Map.of(), diagnostics);
    }

    /**
     * Parses a {@code resounding.materials.json} document.
     *
     * <p>Supports nested {@code children}: a child is emitted as its own material with the parent
     * prepended as its first solute at composition 0, so it inherits the parent's properties as
     * defaults it may selectively override.
     *
     * @param name source label used in diagnostics
     */
    public static Layer parseMaterials(String name, String json) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (json == null) {
            diagnostics.add(new Diagnostic.ParseError(name, "JSON content is null"));
            return new Layer(name, Map.of(), Map.of(), diagnostics);
        }

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (Exception e) {
            diagnostics.add(new Diagnostic.ParseError(name, e.getMessage()));
            return new Layer(name, Map.of(), Map.of(), diagnostics);
        }

        if (rootElement == null || !rootElement.isJsonObject()) {
            diagnostics.add(new Diagnostic.ParseError(name, "Root JSON element is not an object"));
            return new Layer(name, Map.of(), Map.of(), diagnostics);
        }

        JsonObject root = rootElement.getAsJsonObject();
        Map<Ident, RawMaterialDef> materials = new LinkedHashMap<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            JsonElement val = entry.getValue();

            Ident matId;
            try {
                matId = Ident.parse(key);
            } catch (IllegalArgumentException e) {
                diagnostics.add(new Diagnostic.ParseError(name, "Invalid material identifier '" + key + "': " + e.getMessage()));
                continue;
            }

            if (!val.isJsonObject()) {
                diagnostics.add(new Diagnostic.ParseError(name, "Entry for material '" + key + "' is not a JSON object"));
                continue;
            }

            try {
                parseMaterialEntry(name, matId, val.getAsJsonObject(), null, materials, diagnostics);
            } catch (Exception e) {
                diagnostics.add(new Diagnostic.ParseError(name, "Error parsing material '" + key + "': " + e.getMessage()));
            }
        }

        return new Layer(name, Map.of(), materials, diagnostics);
    }

    private static void parseMaterialEntry(
            String sourceName,
            Ident id,
            JsonObject obj,
            Ident parentId,
            Map<Ident, RawMaterialDef> outMaterials,
            List<Diagnostic> diagnostics
    ) {
        Double weight = getDouble(obj, "weight");

        Ident solvent = null;
        if (obj.has("solvent") && obj.get("solvent").isJsonPrimitive()) {
            try {
                solvent = Ident.parse(obj.get("solvent").getAsString());
            } catch (IllegalArgumentException e) {
                diagnostics.add(new Diagnostic.ParseError(sourceName, "Invalid solvent identifier '" + obj.get("solvent").getAsString() + "': " + e.getMessage()));
            }
        }

        List<Ident> solute = getIdentList(obj, "solute", sourceName, diagnostics);
        List<Double> composition = getDoubleList(obj, "composition");

        if (parentId != null) {
            if (solute == null) {
                solute = new ArrayList<>();
            } else {
                solute = new ArrayList<>(solute);
            }
            solute.add(0, parentId);

            if (composition == null) {
                composition = new ArrayList<>();
            } else {
                composition = new ArrayList<>(composition);
            }
            composition.add(0, 0.0);
        }

        Boolean ratio = getBoolean(obj, "ratio");
        Double granularity = getDouble(obj, "granularity");
        Double melt = getDouble(obj, "melt");
        Double boil = getDouble(obj, "boil");
        Double temperature = getDouble(obj, "temperature", "temp");
        Double density = getDouble(obj, "density");
        Double swave = getDouble(obj, "swave", "solid");
        Double lwave = getDouble(obj, "lwave", "fluid");

        RawMaterialDef matDef = new RawMaterialDef(
                weight, solvent, solute, composition, ratio,
                granularity, melt, boil, temperature, density, swave, lwave
        );
        outMaterials.put(id, matDef);

        if (obj.has("children") && obj.get("children").isJsonObject()) {
            JsonObject childrenObj = obj.get("children").getAsJsonObject();
            for (Map.Entry<String, JsonElement> childEntry : childrenObj.entrySet()) {
                String childKey = childEntry.getKey();
                JsonElement childVal = childEntry.getValue();

                Ident childId;
                try {
                    childId = Ident.parse(childKey);
                } catch (IllegalArgumentException e) {
                    diagnostics.add(new Diagnostic.ParseError(sourceName, "Invalid child identifier '" + childKey + "': " + e.getMessage()));
                    continue;
                }

                if (!childVal.isJsonObject()) {
                    diagnostics.add(new Diagnostic.ParseError(sourceName, "Child entry '" + childKey + "' is not a JSON object"));
                    continue;
                }

                try {
                    parseMaterialEntry(sourceName, childId, childVal.getAsJsonObject(), id, outMaterials, diagnostics);
                } catch (Exception e) {
                    diagnostics.add(new Diagnostic.ParseError(sourceName, "Error parsing child '" + childKey + "': " + e.getMessage()));
                }
            }
        }
    }

    private static List<String> getStringList(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            JsonArray arr = obj.get(key).getAsJsonArray();
            List<String> list = new ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    list.add(el.getAsString());
                }
            }
            return list;
        }
        return null;
    }

    private static Set<Ident> getIdentSet(JsonObject obj, String key, String sourceName, List<Diagnostic> diagnostics) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            JsonArray arr = obj.get(key).getAsJsonArray();
            Set<Ident> set = new LinkedHashSet<>();
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    String str = el.getAsString();
                    try {
                        set.add(Ident.parse(str));
                    } catch (IllegalArgumentException e) {
                        diagnostics.add(new Diagnostic.ParseError(sourceName, "Invalid identifier '" + str + "' in " + key + ": " + e.getMessage()));
                    }
                }
            }
            return set;
        }
        return null;
    }

    private static List<Ident> getIdentList(JsonObject obj, String key, String sourceName, List<Diagnostic> diagnostics) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            JsonArray arr = obj.get(key).getAsJsonArray();
            List<Ident> list = new ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    String str = el.getAsString();
                    try {
                        list.add(Ident.parse(str));
                    } catch (IllegalArgumentException e) {
                        diagnostics.add(new Diagnostic.ParseError(sourceName, "Invalid identifier '" + str + "' in " + key + ": " + e.getMessage()));
                    }
                }
            }
            return list;
        }
        return null;
    }

    private static List<Double> getDoubleList(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            JsonArray arr = obj.get(key).getAsJsonArray();
            List<Double> list = new ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    try {
                        list.add(el.getAsDouble());
                    } catch (Exception ignored) {
                    }
                }
            }
            return list;
        }
        return null;
    }

    private static Double getDouble(JsonObject obj, String key) {
        if (obj.has(key)) {
            JsonElement el = obj.get(key);
            if (el.isJsonPrimitive()) {
                try {
                    return el.getAsDouble();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static Double getDouble(JsonObject obj, String key, String alternateKey) {
        Double val = getDouble(obj, key);
        if (val != null) {
            return val;
        }
        return getDouble(obj, alternateKey);
    }

    private static Boolean getBoolean(JsonObject obj, String key) {
        if (obj.has(key)) {
            JsonElement el = obj.get(key);
            if (el.isJsonPrimitive()) {
                try {
                    return el.getAsBoolean();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
