package dev.thedocruby.resounding.tag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A tag definition as authored, normalized and immutable.
 *
 * <p>Replaces {@code RawTag}, whose four fields were all {@code @Nullable} arrays. That forced every
 * consumer to null-check and left {@code Utils.granularFilter} one stored null away from an NPE
 * ({@code Arrays.stream(null)}). Here nulls are normalized away at construction, so downstream code
 * cannot encounter one.
 *
 * <p>Patterns are kept as source strings rather than compiled {@code Pattern}s for two reasons:
 * {@code Pattern} has no {@code equals}, which would silently break record equality and every test
 * that compares definitions; and compilation is a parse step that can fail on untrusted pack input,
 * so it belongs in {@link TagResolver} where the failure becomes a {@link Diagnostic.BadPattern}
 * instead of an escaping exception.
 *
 * @param patterns    regexes matched against block ids
 * @param blocks      block ids stated outright
 * @param tagPatterns regexes matched against other tag ids, whose contents are pulled in
 * @param tags        other tag ids whose contents are pulled in
 * @param replace     when true, this definition discards lower layers for the same id instead of
 *                    unioning with them — vanilla datapack semantics
 */
public record RawTagDef(
        List<String> patterns,
        Set<Ident> blocks,
        List<String> tagPatterns,
        Set<Ident> tags,
        boolean replace
) {
    /** Normalizes nulls to empty and takes defensive immutable copies. */
    public RawTagDef {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        blocks = blocks == null ? Set.of() : Set.copyOf(blocks);
        tagPatterns = tagPatterns == null ? List.of() : List.copyOf(tagPatterns);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public static RawTagDef empty() {
        return new RawTagDef(null, null, null, null, false);
    }

    /** True when this definition contributes nothing — used to drop no-op registry entries. */
    public boolean isEmpty() {
        return patterns.isEmpty() && blocks.isEmpty() && tagPatterns.isEmpty() && tags.isEmpty();
    }

    /**
     * Layers {@code higher} on top of this definition.
     *
     * <p>Union of all four collections, unless {@code higher.replace()}, in which case {@code higher}
     * wins outright. This is Minecraft's own datapack model, so pack authors already know it, and it
     * preserves the documented idiom of a pack self-referencing a tag in order to extend it.
     *
     * <p>The merged definition carries {@code higher.replace()}: the flag describes how this
     * definition combines with anything layered <em>below</em> it, and after merging that role
     * belongs to the higher operand.
     *
     * @return the merged definition; never mutates either operand
     */
    public RawTagDef mergeUnder(RawTagDef higher) {
        if (higher.replace()) {
            return new RawTagDef(
                    higher.patterns(), higher.blocks(), higher.tagPatterns(), higher.tags(), true);
        }
        return new RawTagDef(
                union(patterns, higher.patterns()),
                union(blocks, higher.blocks()),
                union(tagPatterns, higher.tagPatterns()),
                union(tags, higher.tags()),
                false);
    }

    private static <T> List<T> union(List<T> lower, List<T> higher) {
        Set<T> merged = new LinkedHashSet<>(lower);
        merged.addAll(higher);
        return List.copyOf(merged);
    }

    private static <T> Set<T> union(Set<T> lower, Set<T> higher) {
        Set<T> merged = new LinkedHashSet<>(lower);
        merged.addAll(higher);
        return Set.copyOf(merged);
    }
}
