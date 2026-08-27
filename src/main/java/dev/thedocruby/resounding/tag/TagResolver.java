package dev.thedocruby.resounding.tag;

import java.util.Map;

/**
 * Expands tag definitions into concrete block sets. Pure.
 *
 * <p>Expansion has three sources, unioned: blocks stated outright, blocks whose id matches one of
 * the tag's regexes, and the contents of other tags pulled in by id or by regex over tag ids.
 *
 * <p>This is the piece that was most wrong. The previous implementation
 * ({@code TagRegistry.flattenTags}) had its memoization lambda close over the enclosing
 * <em>loop variable</em> rather than the key being memoized, so when the memoizer recursed to
 * resolve a referenced tag, the body still operated on the outer tag's name — writing the reverse
 * index under the wrong tag entirely. The file's own comment warned against exactly this. It also
 * read a tag's own explicit block list back out of the input map <em>after</em> the memoizer had
 * removed the entry, so that list was unconditionally discarded.
 *
 * <p>Both bugs share a root cause: resolution was written as mutation of shared maps. Hence the
 * contract below.
 */
public final class TagResolver {

    private TagResolver() {}

    /**
     * Resolves definitions against an index of known blocks.
     *
     * <p><b>Purity is part of the contract, and is tested.</b> Neither argument is mutated, and
     * resolving the same inputs twice yields equal results. Nothing is memoized across calls.
     *
     * <p>Failures are reported, never thrown and never signalled by null:
     * <ul>
     *   <li>a reference cycle yields {@link Diagnostic.Cycle} and the participating tags resolve to
     *       the union of everything reachable without traversing the cycle, rather than to nothing;
     *   <li>a reference to an unknown tag yields {@link Diagnostic.MissingReference} and is skipped,
     *       preserving the "self-reference to extend an existing tag" idiom;
     *   <li>a regex that does not compile yields {@link Diagnostic.BadPattern} and is skipped.
     * </ul>
     *
     * <p>The returned {@link Resolution#byBlock()} includes every block in {@code index}, including
     * blocks that ended up with no tags.
     *
     * @param definitions already layered; see {@link RawTagDef#mergeUnder}
     * @param index       blocks known to exist, with any tags the game registry already assigns them
     */
    public static Resolution resolve(Map<Ident, RawTagDef> definitions, BlockIndex index) {
        throw new UnsupportedOperationException("P3");
    }
}
