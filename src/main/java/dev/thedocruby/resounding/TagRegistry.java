package dev.thedocruby.resounding;

import dev.thedocruby.resounding.tag.Ident;
import dev.thedocruby.resounding.tag.Resolution;

import java.util.Map;
import java.util.Set;

/**
 * Holds the resolved block/tag mapping for queries outside the material pipeline.
 *
 * <p>The previous {@code TagRegistry.tags} was assigned once by {@code Cache.generate} and read by
 * nothing at all, while the registry scan never appended scanned blocks to their tags, so those
 * tags resolved empty regardless. Both directions of the mapping are kept here now because
 * resolution computes both anyway, and the forward direction is what any future work — sound
 * classification by tag, for one — actually needs.
 */
public final class TagRegistry {

    private TagRegistry() {}

    private static volatile Resolution resolution =
            new Resolution(Map.of(), Map.of(), java.util.List.of());

    static void publish(Resolution next) {
        resolution = next;
    }

    public static Resolution resolution() {
        return resolution;
    }

    /** Blocks carrying a tag; empty, never null. */
    public static Set<Ident> blocksOf(Ident tag) {
        return resolution.blocksOf(tag);
    }

    /** Tags of a block; empty, never null. */
    public static Set<Ident> tagsOf(Ident block) {
        return resolution.tagsOf(block);
    }
}
