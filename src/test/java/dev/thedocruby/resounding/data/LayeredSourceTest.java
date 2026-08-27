package dev.thedocruby.resounding.data;

import dev.thedocruby.resounding.material.RawMaterialDef;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.RawTagDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LayeredSource collapses ordered layers (generated shells, mod defaults, resource packs) into
 * one set of definitions. Tags union across layers; materials overlay per field. A generated shell
 * must never replace an authored material - the previous behaviour ran putAll(shells) last and
 * silently overwrote every colliding authored material with an empty skeleton.
 */
class LayeredSourceTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    private static Layer layerOf(String name, Map<Ident, RawTagDef> tags, Map<Ident, RawMaterialDef> materials) {
        return new Layer(name, tags, materials, List.of());
    }

    @Test
    void sameTagIdInTwoLayersUnions() {
        Ident tagId = id("mod:tag1");
        Layer lower = layerOf("lower", Map.of(tagId, new RawTagDef(null, Set.of(id("mod:a")), null, null, false)), Map.of());
        Layer higher = layerOf("higher", Map.of(tagId, new RawTagDef(null, Set.of(id("mod:b")), null, null, false)), Map.of());

        Layer merged = LayeredSource.merge(List.of(lower, higher));

        assertEquals(Set.of(id("mod:a"), id("mod:b")), merged.tags().get(tagId).blocks());
    }

    @Test
    void higherLayerReplaceTrueDiscardsTheLowerLayerAndEmitsShadowed() {
        Ident tagId = id("mod:tag1");
        Layer lower = layerOf("lower", Map.of(tagId, new RawTagDef(null, Set.of(id("mod:a")), null, null, false)), Map.of());
        Layer higher = layerOf("higher", Map.of(tagId, new RawTagDef(null, Set.of(id("mod:c")), null, null, true)), Map.of());

        Layer merged = LayeredSource.merge(List.of(lower, higher));

        assertEquals(Set.of(id("mod:c")), merged.tags().get(tagId).blocks(),
                "replace: true must discard the lower layer's blocks entirely");
        assertTrue(merged.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.Shadowed));
    }

    @Test
    void materialsOverlayPerFieldAcrossLayers() {
        Ident matId = id("mod:stone");
        RawMaterialDef lowerDef = new RawMaterialDef(null, null, null, null, null,
                null, 100.0, null, null, 10.0, null, null);
        RawMaterialDef higherDef = new RawMaterialDef(null, null, null, null, null,
                null, 200.0, null, null, null, null, null);
        Layer lower = layerOf("lower", Map.of(), Map.of(matId, lowerDef));
        Layer higher = layerOf("higher", Map.of(), Map.of(matId, higherDef));

        Layer merged = LayeredSource.merge(List.of(lower, higher));

        RawMaterialDef result = merged.materials().get(matId);
        assertEquals(200.0, result.melt(), "higher layer's melt must win");
        assertEquals(10.0, result.density(), "lower layer's density must survive since higher left it null");
    }

    @Test
    void generatedShellNeverReplacesAnAuthoredMaterial() {
        // The regression: shell generation used to run putAll(shells) LAST, unconditionally
        // clobbering any authored material with the same id. Per-field overlay in the documented
        // order (shells lowest, authored higher) must preserve the authored physical properties
        // while still letting the block inherit the shell's tag-based solute linkage where the
        // authored layer left solute unset.
        Ident blockId = id("mod:stone");
        Ident tagId = id("mod:stones");

        RawMaterialDef shell = new RawMaterialDef(1.0, null, List.of(tagId), List.of(0.0), false,
                null, null, null, null, null, null, null);
        RawMaterialDef authored = new RawMaterialDef(null, null, null, null, null,
                1.0, 100.0, 200.0, 150.0, 2600.0, 3500.0, 5000.0);

        Layer shellsLayer = layerOf("shells", Map.of(), Map.of(blockId, shell));
        Layer authoredLayer = layerOf("defaults", Map.of(), Map.of(blockId, authored));

        Layer merged = LayeredSource.merge(shellsLayer, List.of(authoredLayer));

        RawMaterialDef result = merged.materials().get(blockId);
        assertEquals(2600.0, result.density(), "the authored physical property must survive, not be wiped by the shell");
        assertEquals(100.0, result.melt());
        assertEquals(List.of(tagId), result.solute(),
                "since the authored layer left solute unset, it must inherit the shell's tag linkage");
    }
}
