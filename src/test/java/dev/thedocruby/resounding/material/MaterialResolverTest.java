package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MaterialResolver flattens, refines and bakes material definitions into runtime Materials. Pure.
 */
class MaterialResolverTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    /** A fully self-contained (no solute references) definition, for baking arithmetic tests. */
    private static RawMaterialDef standalone(double density, double melt, double boil, double temperature,
                                              double swave, double lwave, double granularity, Ident solvent) {
        return new RawMaterialDef(1.0, solvent, List.of(), List.of(), false,
                granularity, melt, boil, temperature, density, swave, lwave);
    }

    @Test
    void shellsProducesOneShellPerBlockIncludingUntagged() {
        BlockIndex index = BlockIndex.builder()
                .block(id("mod:tagged")).tagged(id("mod:tagged"), id("mod:tag1")).tagged(id("mod:tagged"), id("mod:tag2"))
                .block(id("mod:untagged"))
                .build();

        Map<Ident, RawMaterialDef> shells = MaterialResolver.shells(index);

        assertEquals(index.blocks(), shells.keySet(), "every block, including untagged ones, must get a shell");

        RawMaterialDef tagged = shells.get(id("mod:tagged"));
        assertEquals(2, tagged.solute().size());
        assertTrue(tagged.solute().containsAll(List.of(id("mod:tag1"), id("mod:tag2"))));
        assertEquals(0.0, tagged.composition().get(0), "the first solute must be the Override-mode base");

        RawMaterialDef untagged = shells.get(id("mod:untagged"));
        assertTrue(untagged.solute().isEmpty(), "an untagged block's shell has no solutes to inherit from");
    }

    @Test
    void resolveBakesImpedancePermeationAndStatePerTheDocumentedFormulas() {
        // Hand-computed via the exact formulas documented on MaterialResolver.resolve:
        //   state      = phaseState(temperature, melt°C, boil°C)
        //   impedance  = lerp(state, lwave, swave) * density
        //   permeation = clamp((1 - reflection(impedance, solvent))^(granularity * (1 + state)), 0, 1)
        Ident solventId = id("mod:solvent");
        Ident matId = id("mod:stone");

        // solvent: lwave == swave, so its impedance is independent of its own state
        RawMaterialDef solvent = standalone(1.0, 0.0, 1.0, 287.15, 50.0, 50.0, 1.0, null);
        // 287.15 K ≈ 14°C, between 0°C melt and 100°C boil → state = 0.86
        RawMaterialDef material = standalone(10.0, 0.0, 100.0, 287.15, 300.0, 100.0, 2.0, solventId);

        Map<Ident, RawMaterialDef> defs = new LinkedHashMap<>();
        defs.put(solventId, solvent);
        defs.put(matId, material);

        MaterialResolver.Baked baked = MaterialResolver.resolve(defs);

        double state = 0.86;
        double velocity = 100.0 + state * (300.0 - 100.0); // lerp(state, lwave, swave)
        double impedance = velocity * 10.0;
        double solventImpedance = 50.0;
        double reflection = Math.pow((impedance - solventImpedance) / (impedance + solventImpedance), 2);
        double permeation = Acoustics.clamp(Math.pow(1 - reflection, 2.0 * (1 + state)), 0.0, 1.0);

        Material baked1 = baked.materials().get(matId);
        assertNotNull(baked1, "the material must bake; diagnostics: " + baked.diagnostics());
        assertEquals(impedance, baked1.impedance(), 1e-9);
        assertEquals(permeation, baked1.permeation(), 1e-9);
        assertEquals(state, baked1.state(), 1e-9);
    }

    @Test
    void infiniteGranularityYieldsZeroPermeationInsteadOfNaN() {
        // Java's Math.pow(1.0, POSITIVE_INFINITY) is NaN, not 1 or 0 - and reflection is exactly 0
        // (base 1.0) whenever a material's impedance exactly equals its solvent's. Infinite
        // granularity must be special-cased to permeation == 0, never NaN.
        Ident solventId = id("mod:solvent");
        Ident matId = id("mod:opaque");

        RawMaterialDef solvent = standalone(10.0, 0.0, 1.0, 0.5, 100.0, 100.0, 1.0, null);
        RawMaterialDef material = standalone(10.0, 0.0, 1.0, 0.5, 100.0, 100.0, Double.POSITIVE_INFINITY, solventId);

        Map<Ident, RawMaterialDef> defs = new LinkedHashMap<>();
        defs.put(solventId, solvent);
        defs.put(matId, material);

        MaterialResolver.Baked baked = MaterialResolver.resolve(defs);

        Material result = baked.materials().get(matId);
        assertNotNull(result, "diagnostics: " + baked.diagnostics());
        assertEquals(0.0, result.permeation(), "infinite granularity must yield exactly 0 permeation, never NaN");
    }

    @Test
    void incompleteDefinitionsAreDroppedWithADiagnosticRatherThanBakedIntoNonsense() {
        Ident completeId = id("mod:complete");
        Ident brokenId = id("mod:broken");

        RawMaterialDef complete = standalone(10.0, 0.0, 100.0, 50.0, 300.0, 100.0, 1.0, null);
        RawMaterialDef broken = new RawMaterialDef(
                1.0, null, List.of(), List.of(), false,
                1.0, 0.0, 100.0, 50.0, null /* density missing */, 300.0, 100.0);

        Map<Ident, RawMaterialDef> defs = new LinkedHashMap<>();
        defs.put(completeId, complete);
        defs.put(brokenId, broken);

        MaterialResolver.Baked baked = MaterialResolver.resolve(defs);

        assertTrue(baked.materials().containsKey(completeId));
        assertFalse(baked.materials().containsKey(brokenId), "an incomplete definition must not be baked");
        assertTrue(baked.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.Incomplete),
                "dropping an incomplete definition must be reported as Diagnostic.Incomplete, not a bare log line");
    }

    @Test
    void soluteCycleProducesACycleDiagnosticNeverNullNeverAnException() {
        Ident aId = id("mod:a");
        Ident bId = id("mod:b");
        RawMaterialDef a = new RawMaterialDef(null, null, List.of(bId), List.of(1.0), null,
                null, null, null, null, null, null, null);
        RawMaterialDef b = new RawMaterialDef(null, null, List.of(aId), List.of(1.0), null,
                null, null, null, null, null, null, null);

        Map<Ident, RawMaterialDef> defs = new LinkedHashMap<>();
        defs.put(aId, a);
        defs.put(bId, b);

        MaterialResolver.Baked baked = assertDoesNotThrow(() -> MaterialResolver.resolve(defs));

        assertFalse(baked.materials().containsKey(aId));
        assertFalse(baked.materials().containsKey(bId));
        assertTrue(baked.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.Cycle));
    }

    @Test
    void missingSoluteProducesAMissingReferenceDiagnosticNeverNullNeverAnException() {
        Ident matId = id("mod:has_bad_solute");
        RawMaterialDef def = new RawMaterialDef(null, null, List.of(id("mod:does_not_exist")), List.of(1.0), null,
                null, null, null, null, null, null, null);

        MaterialResolver.Baked baked = assertDoesNotThrow(() -> MaterialResolver.resolve(Map.of(matId, def)));

        assertFalse(baked.materials().containsKey(matId));
        assertTrue(baked.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.MissingReference));
    }

    @Test
    void resolveDoesNotMutateItsInputDefinitions() {
        Ident matId = id("mod:stone");
        RawMaterialDef material = standalone(10.0, 0.0, 100.0, 50.0, 300.0, 100.0, 1.0, null);
        Map<Ident, RawMaterialDef> defs = new LinkedHashMap<>();
        defs.put(matId, material);
        Map<Ident, RawMaterialDef> snapshot = Map.copyOf(defs);

        MaterialResolver.resolve(defs);

        assertEquals(snapshot, defs, "resolve must not mutate its input map while iterating it");
    }
}
