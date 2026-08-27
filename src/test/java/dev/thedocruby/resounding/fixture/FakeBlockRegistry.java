package dev.thedocruby.resounding.fixture;

import dev.thedocruby.resounding.tag.BlockIndex;
import dev.thedocruby.resounding.tag.Ident;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stands in for {@code Registries.BLOCK} so the pipeline can be driven end to end without a game.
 *
 * <p>This is the fixture that makes "test these components individually" possible: the real
 * registry needs a running Minecraft, which is why none of this code had ever been executed.
 *
 * <p>Insertion order is preserved so failures are reproducible rather than hash-order dependent.
 */
public final class FakeBlockRegistry {

    private final Map<Ident, Set<Ident>> blocks = new LinkedHashMap<>();

    /** Registers a block with the tags the game would report for it. */
    public FakeBlockRegistry block(String id, String... tags) {
        Set<Ident> set = new LinkedHashSet<>();
        for (String tag : tags) {
            set.add(Ident.parse(tag));
        }
        blocks.put(Ident.parse(id), set);
        return this;
    }

    /** Builds the index exactly as the real adapter would from the block registry. */
    public BlockIndex index() {
        BlockIndex.Builder builder = BlockIndex.builder();
        blocks.forEach((block, tags) -> {
            builder.block(block);
            tags.forEach(tag -> builder.tagged(block, tag));
        });
        return builder.build();
    }

    /** A small vanilla-shaped world: a few stones, a couple of woods, and one untagged oddity. */
    public static FakeBlockRegistry vanillaSample() {
        return new FakeBlockRegistry()
                .block("minecraft:stone", "minecraft:mineable/pickaxe", "minecraft:base_stone_overworld")
                .block("minecraft:cobblestone", "minecraft:mineable/pickaxe")
                .block("minecraft:deepslate", "minecraft:mineable/pickaxe", "minecraft:base_stone_overworld")
                .block("minecraft:oak_planks", "minecraft:planks", "minecraft:mineable/axe")
                .block("minecraft:spruce_planks", "minecraft:planks", "minecraft:mineable/axe")
                .block("minecraft:white_wool", "minecraft:wool")
                .block("minecraft:air")
                .block("minecraft:barrier");
    }
}
