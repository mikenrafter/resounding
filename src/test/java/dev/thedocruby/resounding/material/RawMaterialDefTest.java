package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.tag.Ident;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RawMaterialDef replaces RawMaterial. solute/composition are normalized to the same length at
 * construction; the previous code defaulted a null composition array to {@code new Double[0]} and
 * indexed it against solute.length, so any material with solutes and no explicit composition -
 * the common case, composition being optional - threw ArrayIndexOutOfBoundsException.
 */
class RawMaterialDefTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    /** Every field needed to bake, per MaterialResolver's documented formulas. */
    private static RawMaterialDef complete() {
        return new RawMaterialDef(
                1.0, null, List.of(), List.of(), false,
                1.0,      // granularity
                100.0,    // melt
                200.0,    // boil
                150.0,    // temperature
                10.0,     // density
                50.0,     // swave
                80.0      // lwave
        );
    }

    @Test
    void soluteWithNoCompositionArrayNormalizesWithoutThrowing() {
        // The AIOOBE that fired on the common case: solutes present, composition omitted.
        RawMaterialDef def = assertDoesNotThrow(() -> new RawMaterialDef(
                null, null, List.of(id("mod:a"), id("mod:b")), null, null,
                null, null, null, null, null, null, null));

        assertEquals(2, def.solute().size());
        assertEquals(2, def.composition().size(), "composition must be padded out to solute's length");
    }

    @Test
    void compositionShorterThanSoluteNormalizesToSolutesLength() {
        RawMaterialDef def = new RawMaterialDef(
                null, null, List.of(id("mod:a"), id("mod:b"), id("mod:c")), List.of(5.0), null,
                null, null, null, null, null, null, null);

        assertEquals(3, def.composition().size());
        assertEquals(5.0, def.composition().get(0));
        assertNull(def.composition().get(1), "missing entries are left null, read as 1 downstream");
        assertNull(def.composition().get(2));
    }

    @Test
    void compositionLongerThanSoluteNormalizesToSolutesLength() {
        RawMaterialDef def = new RawMaterialDef(
                null, null, List.of(id("mod:a")), List.of(1.0, 2.0, 3.0), null,
                null, null, null, null, null, null, null);

        assertEquals(1, def.solute().size());
        assertEquals(1, def.composition().size());
        assertEquals(1.0, def.composition().get(0));
    }

    @Test
    void overlayLetsANonNullFieldInTheHigherLayerWin() {
        RawMaterialDef lower = new RawMaterialDef(
                null, null, null, null, null, null, null, null, null, 10.0, null, null);
        RawMaterialDef higher = new RawMaterialDef(
                null, null, null, null, null, null, 200.0, null, null, null, null, null);

        RawMaterialDef merged = lower.overlay(higher);

        assertEquals(200.0, merged.melt(), "higher's non-null field must win");
        assertEquals(10.0, merged.density(), "a null field in higher must inherit from lower");
    }

    @Test
    void soluteAndCompositionReplaceAtomicallyAsAPair() {
        // Taking solute from one layer and composition from the other would desynchronize their
        // lengths - exactly the defect the normalizing constructor exists to prevent.
        RawMaterialDef lower = new RawMaterialDef(
                null, null, List.of(id("mod:a"), id("mod:b")), List.of(0.0, 1.0), null,
                null, null, null, null, null, null, null);
        RawMaterialDef higher = new RawMaterialDef(
                null, null, List.of(id("mod:c")), null, null,
                null, null, null, null, null, null, null);

        RawMaterialDef merged = lower.overlay(higher);

        assertEquals(List.of(id("mod:c")), merged.solute(), "higher's solute must win outright");
        assertEquals(1, merged.composition().size(),
                "composition must be higher's own (padded to higher's solute length), never lower's leftover length-2 array");
        assertNull(merged.composition().get(0));
    }

    @Test
    void isCompleteIsTrueWhenEveryPhysicalPropertyIsPresent() {
        assertTrue(complete().isComplete());
    }

    @Test
    void isCompleteIsFalseWhenDensityIsMissing() {
        RawMaterialDef c = complete();
        assertFalse(new RawMaterialDef(c.weight(), c.solvent(), c.solute(), c.composition(), c.ratio(),
                c.granularity(), c.melt(), c.boil(), c.temperature(), null, c.swave(), c.lwave()).isComplete());
    }

    @Test
    void isCompleteIsFalseWhenGranularityIsMissing() {
        RawMaterialDef c = complete();
        assertFalse(new RawMaterialDef(c.weight(), c.solvent(), c.solute(), c.composition(), c.ratio(),
                null, c.melt(), c.boil(), c.temperature(), c.density(), c.swave(), c.lwave()).isComplete());
    }

    @Test
    void isCompleteIsFalseWhenMeltIsMissing() {
        RawMaterialDef c = complete();
        assertFalse(new RawMaterialDef(c.weight(), c.solvent(), c.solute(), c.composition(), c.ratio(),
                c.granularity(), null, c.boil(), c.temperature(), c.density(), c.swave(), c.lwave()).isComplete());
    }

    @Test
    void isCompleteIsFalseWhenBoilIsMissing() {
        RawMaterialDef c = complete();
        assertFalse(new RawMaterialDef(c.weight(), c.solvent(), c.solute(), c.composition(), c.ratio(),
                c.granularity(), c.melt(), null, c.temperature(), c.density(), c.swave(), c.lwave()).isComplete());
    }

    @Test
    void isCompleteIsFalseWhenTemperatureIsMissing() {
        RawMaterialDef c = complete();
        assertFalse(new RawMaterialDef(c.weight(), c.solvent(), c.solute(), c.composition(), c.ratio(),
                c.granularity(), c.melt(), c.boil(), null, c.density(), c.swave(), c.lwave()).isComplete());
    }

    @Test
    void isCompleteIsFalseWhenSwaveIsMissing() {
        RawMaterialDef c = complete();
        assertFalse(new RawMaterialDef(c.weight(), c.solvent(), c.solute(), c.composition(), c.ratio(),
                c.granularity(), c.melt(), c.boil(), c.temperature(), c.density(), null, c.lwave()).isComplete());
    }

    @Test
    void isCompleteIsFalseWhenLwaveIsMissing() {
        RawMaterialDef c = complete();
        assertFalse(new RawMaterialDef(c.weight(), c.solvent(), c.solute(), c.composition(), c.ratio(),
                c.granularity(), c.melt(), c.boil(), c.temperature(), c.density(), c.swave(), null).isComplete());
    }
}
