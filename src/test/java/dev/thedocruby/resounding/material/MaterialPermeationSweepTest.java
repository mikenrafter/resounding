package dev.thedocruby.resounding.material;

import dev.thedocruby.resounding.data.JsonAdapter;
import dev.thedocruby.resounding.data.Layer;
import dev.thedocruby.resounding.data.LayeredSource;
import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.Resolution;
import dev.thedocruby.resounding.tag.TagResolver;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

class MaterialPermeationSweepTest {

	@Test
	void printRepresentativeBakedPermeation() throws Exception {
		var baked = bakeAll();
		String[] watch = {
				"wool", "white_wool", "orange_wool",
				"leaves", "oak_leaves", "azalea_leaves",
				"dirt", "mud", "rooted_dirt", "farmland", "mycelium",
				"sand", "red_sand", "gravel", "snow", "snow_block", "powder_snow",
				"moss_block", "moss_carpet", "sponge", "wet_sponge",
				"hay_block", "white_carpet", "white_bed", "cobweb",
				"sculk", "sculk_vein", "sculk_catalyst", "sculk_shrieker", "sculk_sensor",
				"soul_sand", "soul_soil", "netherrack", "glowstone",
				"stone", "white_wool", "oak_planks", "white_concrete_powder"
		};
		for (String path : watch) {
			Material m = baked.materials().get(Ident.parse("minecraft:" + path));
			if (m == null) {
				System.out.printf("%-22s (missing)%n", path);
				continue;
			}
			System.out.printf(
					"%-22s perm=%.4f gran=%.3f Z=%.2e state=%.3f%n",
					path, m.permeation(), m.granularity(), m.impedance(), m.state()
			);
		}
		System.out.println("--- lowest 30 permeation ---");
		baked.materials().entrySet().stream()
				.filter(e -> e.getKey().namespace().equals("minecraft"))
				.sorted(Comparator.comparingDouble(e -> e.getValue().permeation()))
				.limit(30)
				.forEach(e -> System.out.printf(
						"%-35s perm=%.4f gran=%.3f%n",
						e.getKey().path(), e.getValue().permeation(), e.getValue().granularity()
				));
	}

	static MaterialResolver.Baked bakeAll() throws Exception {
		String tags = resource("resounding.tags.json");
		String mats = resource("resounding.materials.json");
		Layer tagLayer = JsonAdapter.parseTags("defaults", tags);
		Layer materialLayer = JsonAdapter.parseMaterials("defaults", mats);
		List<String> ids = resource("vanilla-block-ids.txt").lines().filter(s -> !s.isBlank()).toList();
		BlockIndex.Builder builder = BlockIndex.builder();
		ids.forEach(blockId -> builder.block(Ident.parse(blockId)));
		Resolution tagsResolved = TagResolver.resolve(tagLayer.tags(), builder.build());
		BlockIndex.Builder resolvedBuilder = BlockIndex.builder();
		tagsResolved.byBlock().forEach((block, blockTags) -> {
			resolvedBuilder.block(block);
			blockTags.forEach(tag -> resolvedBuilder.tagged(block, tag));
		});
		Map<Ident, dev.thedocruby.resounding.material.RawMaterialDef> shells = MaterialResolver.shells(
				resolvedBuilder.build(), materialLayer.materials().keySet());
		Layer merged = LayeredSource.merge(
				new Layer("shells", Map.of(), shells, List.of()),
				List.of(materialLayer));
		return MaterialResolver.resolve(merged.materials());
	}

	private static String resource(String name) throws Exception {
		try (InputStream in = MaterialPermeationSweepTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) throw new IllegalStateException("missing " + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
