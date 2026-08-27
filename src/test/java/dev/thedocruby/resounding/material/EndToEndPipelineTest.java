package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.data.LayeredSource;
import dev.thedocruby.resounding.data.Layer;
import dev.thedocruby.resounding.fixture.FakeBlockRegistry;
import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.Resolution;
import dev.thedocruby.resounding.tag.TagResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the whole pipeline - index, resolve tags, generate shells, merge with authored materials,
 * resolve materials - the way the real mod would, using FakeBlockRegistry.vanillaSample() so it can
 * run without a game. Every block must end up with a Material, including the untagged ones, and
 * nothing along the way should rise to an ERROR diagnostic.
 */
class EndToEndPipelineTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    /** A standalone, fully-specified material definition (no solute references). */
    private static RawMaterialDef standalone(double density, double swave, double lwave) {
        return new RawMaterialDef(1.0, null, List.of(), List.of(), false,
                1.0 /* granularity */, 100.0 /* melt */, 200.0 /* boil */, null /* temperature: ambient default */,
                density, swave, lwave);
    }

    @Test
    void everyBlockGetsAMaterialAndNoDiagnosticIsAnError() {
        FakeBlockRegistry registry = FakeBlockRegistry.vanillaSample();
        BlockIndex index = registry.index();

        // Stage 1: resolve tags. No pack-authored tag definitions are needed here - the fixture
        // already wires block -> tag associations into the index the way the real block registry
        // scan would.
        Resolution tagResolution = TagResolver.resolve(Map.of(), index);
        assertNoErrors(tagResolution.diagnostics(), "tag resolution");

        // Stage 2: generate shells, one per block.
        Map<Ident, RawMaterialDef> shells = MaterialResolver.shells(index);

        // Stage 3: a small hand-written "materials" layer authoring the physical properties for
        // every tag a vanillaSample() block carries, plus the two untagged blocks directly.
        Map<Ident, RawMaterialDef> authored = Map.ofEntries(
                Map.entry(id("minecraft:mineable/pickaxe"), standalone(2700.0, 3500.0, 5000.0)),
                Map.entry(id("minecraft:base_stone_overworld"), standalone(2600.0, 3400.0, 4900.0)),
                Map.entry(id("minecraft:planks"), standalone(600.0, 1200.0, 1500.0)),
                Map.entry(id("minecraft:mineable/axe"), standalone(650.0, 1250.0, 1550.0)),
                Map.entry(id("minecraft:wool"), standalone(100.0, 50.0, 60.0)),
                Map.entry(id("minecraft:air"), standalone(1.2, 340.0, 340.0)),
                Map.entry(id("minecraft:barrier"), standalone(1.2, 340.0, 340.0))
        );

        Layer shellsLayer = new Layer("shells", Map.of(), shells, List.of());
        Layer authoredLayer = new Layer("test-defaults", Map.of(), authored, List.of());
        Layer merged = LayeredSource.merge(shellsLayer, List.of(authoredLayer));
        assertNoErrors(merged.diagnostics(), "layer merge");

        // Stage 4: bake.
        MaterialResolver.Baked baked = MaterialResolver.resolve(merged.materials());
        assertNoErrors(baked.diagnostics(), "material resolution");

        for (Ident block : index.blocks()) {
            assertTrue(baked.materials().containsKey(block),
                    "block " + block + " must have a Material; diagnostics: " + baked.diagnostics());
        }
    }

    private static void assertNoErrors(List<Diagnostic> diagnostics, String stage) {
        boolean hasErrors = diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        assertFalse(hasErrors, stage + " must not raise an ERROR diagnostic: " + diagnostics);
    }
}
