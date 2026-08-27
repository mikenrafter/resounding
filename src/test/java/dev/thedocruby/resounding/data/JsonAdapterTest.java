package dev.thedocruby.resounding.data;

import dev.thedocruby.resounding.material.RawMaterialDef;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.RawTagDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonAdapter is the one place Gson is allowed to appear. Malformed input must become a
 * Diagnostic.ParseError on the returned Layer, never an escaping exception - the previous code let
 * JsonSyntaxException propagate out of resource-pack reading, i.e. out of chunk loading.
 */
class JsonAdapterTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    @Test
    void parseTagsReadsAllFiveFields() {
        String json = """
                {
                  "mod:tag1": {
                    "patterns": ["^mod:ore_.*$"],
                    "blocks": ["mod:special_block"],
                    "tagPatterns": ["^mod:other_.*$"],
                    "tags": ["mod:tag2"],
                    "replace": true
                  }
                }
                """;

        Layer layer = JsonAdapter.parseTags("test-source", json);

        RawTagDef def = layer.tags().get(id("mod:tag1"));
        assertNotNull(def, "diagnostics: " + layer.diagnostics());
        assertEquals(List.of("^mod:ore_.*$"), def.patterns());
        assertEquals(Set.of(id("mod:special_block")), def.blocks());
        assertEquals(List.of("^mod:other_.*$"), def.tagPatterns());
        assertEquals(Set.of(id("mod:tag2")), def.tags());
        assertTrue(def.replace());
    }

    @Test
    void parseMaterialsReadsPhysicalFieldsAndEmitsChildrenAsOwnMaterials() {
        String json = """
                {
                  "mod:stone": {
                    "density": 2600.0,
                    "melt": 1973.0,
                    "boil": 2503.0,
                    "granularity": 1.0,
                    "swave": 3500.0,
                    "lwave": 5000.0,
                    "children": {
                      "mod:cobble": {
                        "density": 2500.0
                      }
                    }
                  }
                }
                """;

        Layer layer = JsonAdapter.parseMaterials("test-source", json);

        RawMaterialDef stone = layer.materials().get(id("mod:stone"));
        assertNotNull(stone, "diagnostics: " + layer.diagnostics());
        assertEquals(2600.0, stone.density());
        assertEquals(1973.0, stone.melt());
        assertEquals(2503.0, stone.boil());
        assertEquals(1.0, stone.granularity());
        assertEquals(3500.0, stone.swave());
        assertEquals(5000.0, stone.lwave());

        RawMaterialDef cobble = layer.materials().get(id("mod:cobble"));
        assertNotNull(cobble, "a child must be emitted as its own material; diagnostics: " + layer.diagnostics());
        assertEquals(2500.0, cobble.density());
        assertEquals(List.of(id("mod:stone")), cobble.solute(),
                "the parent must be prepended as the child's first solute");
        assertEquals(0.0, cobble.composition().get(0),
                "the parent solute must be at composition 0 (Override mode)");
    }

    @Test
    void malformedTagsJsonProducesAParseErrorDiagnosticAndDoesNotThrow() {
        String malformed = "{ this is not valid json ";

        Layer layer = assertDoesNotThrow(() -> JsonAdapter.parseTags("bad-source", malformed));

        assertFalse(layer.diagnostics().isEmpty());
        assertTrue(layer.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.ParseError));
    }

    @Test
    void malformedMaterialsJsonProducesAParseErrorDiagnosticAndDoesNotThrow() {
        String malformed = "{ \"mod:stone\": [ unterminated ";

        Layer layer = assertDoesNotThrow(() -> JsonAdapter.parseMaterials("bad-source", malformed));

        assertFalse(layer.diagnostics().isEmpty());
        assertTrue(layer.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.ParseError));
    }

    @Test
    void oneBadEntryDoesNotDiscardTheRestOfTheDocument() {
        // "bad::id" is not a well-formed Ident (embedded extra separator); it must be skipped
        // with a diagnostic rather than discarding the whole document.
        String json = """
                {
                  "mod:good": { "blocks": ["mod:a"] },
                  "bad::id": { "blocks": ["mod:b"] }
                }
                """;

        Layer layer = assertDoesNotThrow(() -> JsonAdapter.parseTags("mixed", json));

        RawTagDef good = layer.tags().get(id("mod:good"));
        assertNotNull(good, "a sibling malformed entry must not discard a well-formed one; diagnostics: " + layer.diagnostics());
        assertEquals(Set.of(id("mod:a")), good.blocks());
        assertFalse(layer.diagnostics().isEmpty(), "the malformed entry must still be reported");
    }
}
