package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.data.JsonAdapter;
import dev.thedocruby.resounding.data.Layer;
import dev.thedocruby.resounding.data.LayeredSource;
import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Diagnostic;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.Resolution;
import dev.thedocruby.resounding.tag.TagResolver;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for material bake impedance (S-07, Phase 1A).
 *
 * <p>Drives the shipped default data through the full pipeline and asserts baked impedances lie in
 * physically plausible ranges for representative blocks.
 */
class MaterialBakeRegressionTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = MaterialBakeRegressionTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> vanillaBlockIds() throws Exception {
        return resource("vanilla-block-ids.txt").lines().filter(s -> !s.isBlank()).toList();
    }

    private record Run(
            Resolution tags,
            MaterialResolver.Baked baked,
            Map<Ident, RawMaterialDef> mergedMaterials,
            int blocks
    ) {}

    /** Runs index -> resolve tags -> shells -> merge -> bake, exactly as the mod's adapter does. */
    private static Run pipeline() throws Exception {
        Layer tagLayer = JsonAdapter.parseTags("defaults", resource("resounding.tags.json"));
        Layer materialLayer = JsonAdapter.parseMaterials("defaults", resource("resounding.materials.json"));
        assertNoErrors(tagLayer.diagnostics(), "parsing tags.json");
        assertNoErrors(materialLayer.diagnostics(), "parsing materials.json");

        List<String> ids = vanillaBlockIds();
        BlockIndex.Builder builder = BlockIndex.builder();
        ids.forEach(blockId -> builder.block(Ident.parse(blockId)));
        BlockIndex index = builder.build();

        Resolution tags = TagResolver.resolve(tagLayer.tags(), index);

        BlockIndex.Builder resolvedBuilder = BlockIndex.builder();
        tags.byBlock().forEach((block, blockTags) -> {
            resolvedBuilder.block(block);
            blockTags.forEach(tag -> resolvedBuilder.tagged(block, tag));
        });
        BlockIndex resolved = resolvedBuilder.build();
        Map<Ident, RawMaterialDef> shells = MaterialResolver.shells(
                resolved, materialLayer.materials().keySet());

        Layer merged = LayeredSource.merge(
                new Layer("shells", Map.of(), shells, List.of()),
                List.of(materialLayer));

        return new Run(tags, MaterialResolver.resolve(merged.materials()), merged.materials(), ids.size());
    }

    private static Material requireMaterial(MaterialResolver.Baked baked, Ident block) {
        Material material = baked.materials().get(block);
        assertNotNull(material, block + " must bake to a material; diagnostics: " + baked.diagnostics());
        return material;
    }

    @Test
    void shippedStoneImpedanceInPhysicalRange() throws Exception {
        Run run = pipeline();
        Ident stoneId = id("minecraft:stone");
        Material stone = requireMaterial(run.baked(), stoneId);
        RawMaterialDef raw = run.mergedMaterials().get(stoneId);

        assertTrue(stone.impedance() >= 1e6 && stone.impedance() <= 1e8,
                "stone impedance must lie in [1e6, 1e8] Rayl (physical stone ~1e7), was "
                        + stone.impedance()
                        + "; merged solvent=" + (raw == null ? null : raw.solvent())
                        + " gran=" + (raw == null ? null : raw.granularity()));
        assertTrue(stone.permeation() > 0.0,
                "stone baked permeation must be positive; was " + stone.permeation()
                        + " (solvent=" + (raw == null ? null : raw.solvent()) + ")");
        assertTrue(stone.granularity() > 0 && stone.granularity() <= 10,
                "stone granularity must stay a small tuning value, not molar-weight blowup; was "
                        + stone.granularity());
        assertTrue(stone.permeation() > 0.5,
                "stone with pseudo-solvent should bake permeation well above zero; was "
                        + stone.permeation());
    }

    @Test
    void shippedWoolImpedanceInPhysicalRange() throws Exception {
        Run run = pipeline();
        Material wool = requireMaterial(run.baked(), id("minecraft:white_wool"));

        assertNotEquals(0.0, wool.impedance(), "wool impedance must not be zero");
        assertTrue(wool.impedance() >= 1e3 && wool.impedance() <= 1e5,
                "wool impedance must lie in [1e3, 1e5] Rayl, was " + wool.impedance());
    }

    @Test
    void stoneAndWoolReflectDifferently() throws Exception {
        Run run = pipeline();
        Material stone = requireMaterial(run.baked(), id("minecraft:stone"));
        Material wool = requireMaterial(run.baked(), id("minecraft:white_wool"));
        Material air = requireMaterial(run.baked(), id("minecraft:air"));

        double airImpedance = air.impedance();
        double stoneReflection = Acoustics.reflection(stone.impedance(), airImpedance);
        double woolReflection = Acoustics.reflection(wool.impedance(), airImpedance);

        assertTrue(stoneReflection > woolReflection + 0.01,
                "stone must reflect more strongly than wool against air (~" + airImpedance + " Rayl); "
                        + "stone reflection=" + stoneReflection + ", wool reflection=" + woolReflection);
    }

    @Test
    void zeroImpedanceEntriesBelowFivePercent() throws Exception {
        Run run = pipeline();

        long total = run.baked().materials().size();
        long zeroCount = run.baked().materials().values().stream()
                .filter(m -> m.impedance() == 0.0)
                .count();

        double fraction = (double) zeroCount / total;
        assertTrue(fraction < 0.05,
                "fewer than 5% of baked materials may have zero impedance; got "
                        + zeroCount + "/" + total + " (" + (fraction * 100) + "%)");
    }

    @Test
    void airMaterialPresentAndNear415() throws Exception {
        Run run = pipeline();
        Material air = requireMaterial(run.baked(), id("minecraft:air"));

        assertTrue(air.impedance() >= 300.0 && air.impedance() <= 600.0,
                "air impedance must lie in [300, 600] Rayl (~415), was " + air.impedance());
    }

    private static void assertNoErrors(List<Diagnostic> diagnostics, String stage) {
        List<Diagnostic> errors = diagnostics.stream()
                .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
                .toList();
        assertTrue(errors.isEmpty(), stage + " raised errors: " + errors);
    }
}
