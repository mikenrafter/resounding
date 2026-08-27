package dev.thedocruby.resounding.tag;

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
        throw new UnsupportedOperationException("P2");
    }

    public static RawTagDef empty() {
        throw new UnsupportedOperationException("P2");
    }

    /** True when this definition contributes nothing — used to drop no-op registry entries. */
    public boolean isEmpty() {
        throw new UnsupportedOperationException("P2");
    }

    /**
     * Layers {@code higher} on top of this definition.
     *
     * <p>Union of all four collections, unless {@code higher.replace()}, in which case {@code higher}
     * wins outright. This is Minecraft's own datapack model, so pack authors already know it, and it
     * preserves the documented idiom of a pack self-referencing a tag in order to extend it.
     *
     * @return the merged definition; never mutates either operand
     */
    public RawTagDef mergeUnder(RawTagDef higher) {
        throw new UnsupportedOperationException("P2");
    }
}
