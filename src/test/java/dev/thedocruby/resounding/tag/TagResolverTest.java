package dev.thedocruby.resounding.tag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TagResolver.resolve is "the piece that was most wrong" (see class javadoc): the old
 * TagRegistry.flattenTags closed its memoization lambda over the enclosing loop variable instead
 * of the key being memoized, misattributing the reverse index during recursion, and it read a
 * tag's own explicit block list back out of the input map after the memoizer had already removed
 * the entry, discarding it unconditionally.
 */
class TagResolverTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    private static RawTagDef def(List<String> patterns, Set<Ident> blocks, List<String> tagPatterns, Set<Ident> tags) {
        return new RawTagDef(patterns, blocks, tagPatterns, tags, false);
    }

    @Test
    void explicitlyListedBlocksEndUpInTheResolvedSet() {
        // The old code read this list back out of a map after the memoizer had removed the entry,
        // so it was unconditionally discarded. This is the single most direct regression test for that.
        BlockIndex index = BlockIndex.builder()
                .block(id("mod:a")).block(id("mod:b")).block(id("mod:c"))
                .build();
        RawTagDef stones = def(List.of(), Set.of(id("mod:a"), id("mod:b")), List.of(), Set.of());

        Resolution res = TagResolver.resolve(Map.of(id("mod:stones"), stones), index);

        assertEquals(Set.of(id("mod:a"), id("mod:b")), res.blocksOf(id("mod:stones")));
    }

    @Test
    void patternExpansionCollectsOnlyMatchingBlocks() {
        BlockIndex index = BlockIndex.builder()
                .block(id("mod:iron_ore")).block(id("mod:gold_ore")).block(id("mod:dirt"))
                .build();
        RawTagDef ores = def(List.of("^mod:.*_ore$"), Set.of(), List.of(), Set.of());

        Resolution res = TagResolver.resolve(Map.of(id("mod:ores"), ores), index);

        assertEquals(Set.of(id("mod:iron_ore"), id("mod:gold_ore")), res.blocksOf(id("mod:ores")));
    }

    @Test
    void tagReferencesAreAbsorbedTransitivelyThroughAChainOfThree() {
        BlockIndex index = BlockIndex.builder().block(id("mod:x")).build();
        Map<Ident, RawTagDef> defs = new LinkedHashMap<>();
        defs.put(id("mod:d"), def(List.of(), Set.of(id("mod:x")), List.of(), Set.of()));
        defs.put(id("mod:c"), def(List.of(), Set.of(), List.of(), Set.of(id("mod:d"))));
        defs.put(id("mod:b"), def(List.of(), Set.of(), List.of(), Set.of(id("mod:c"))));
        defs.put(id("mod:a"), def(List.of(), Set.of(), List.of(), Set.of(id("mod:b"))));

        Resolution res = TagResolver.resolve(defs, index);

        assertEquals(Set.of(id("mod:x")), res.blocksOf(id("mod:a")),
                "mod:a -> mod:b -> mod:c -> mod:d must transitively absorb mod:d's blocks");
    }

    @Test
    void tagPatternsPullInEveryMatchingTagsContents() {
        BlockIndex index = BlockIndex.builder()
                .block(id("mod:iron")).block(id("mod:gold")).block(id("mod:oak"))
                .build();
        Map<Ident, RawTagDef> defs = new LinkedHashMap<>();
        defs.put(id("mod:ore/iron"), def(List.of(), Set.of(id("mod:iron")), List.of(), Set.of()));
        defs.put(id("mod:ore/gold"), def(List.of(), Set.of(id("mod:gold")), List.of(), Set.of()));
        defs.put(id("mod:wood/oak"), def(List.of(), Set.of(id("mod:oak")), List.of(), Set.of()));
        defs.put(id("mod:combined"), def(List.of(), Set.of(), List.of("^mod:ore/.*$"), Set.of()));

        Resolution res = TagResolver.resolve(defs, index);

        assertEquals(Set.of(id("mod:iron"), id("mod:gold")), res.blocksOf(id("mod:combined")),
                "tagPatterns must pull in every tag matching the regex, and no others");
    }

    @Test
    void reverseIndexAttributesRecursivelyPulledBlocksToEveryReferencingTag() {
        // Historical bug: the memoization lambda closed over the enclosing loop variable, so when
        // resolving a reference recursively, the reverse index got written under whatever tag the
        // OUTER loop happened to be on, not the tag actually being resolved. With two independent
        // referencers (A and C) of the same tag (B), a wrong-tag attribution becomes observable:
        // byBlock(x) must contain exactly {A, B, C}, not some subset determined by iteration order.
        BlockIndex index = BlockIndex.builder().block(id("mod:x")).build();
        Map<Ident, RawTagDef> defs = new LinkedHashMap<>();
        defs.put(id("mod:a"), def(List.of(), Set.of(), List.of(), Set.of(id("mod:b"))));
        defs.put(id("mod:b"), def(List.of(), Set.of(id("mod:x")), List.of(), Set.of()));
        defs.put(id("mod:c"), def(List.of(), Set.of(), List.of(), Set.of(id("mod:b"))));

        Resolution res = TagResolver.resolve(defs, index);

        assertEquals(Set.of(id("mod:x")), res.blocksOf(id("mod:a")));
        assertEquals(Set.of(id("mod:x")), res.blocksOf(id("mod:b")));
        assertEquals(Set.of(id("mod:x")), res.blocksOf(id("mod:c")));
        assertEquals(Set.of(id("mod:a"), id("mod:b"), id("mod:c")), res.tagsOf(id("mod:x")),
                "mod:x is reachable from A, B and C; the reverse index must say so for all three, not just whichever tag the recursion's outer loop happened to be on");
    }

    @Test
    void resolveDoesNotMutateItsArguments() {
        BlockIndex index = BlockIndex.builder()
                .block(id("mod:a")).tagged(id("mod:a"), id("mod:existing"))
                .build();
        Map<Ident, RawTagDef> defs = new LinkedHashMap<>();
        defs.put(id("mod:t"), def(List.of("^mod:.*$"), Set.of(id("mod:a")), List.of(), Set.of()));
        Map<Ident, RawTagDef> defsSnapshot = Map.copyOf(defs);
        Set<Ident> blocksSnapshot = Set.copyOf(index.blocks());
        Set<Ident> tagsSnapshot = Set.copyOf(index.tags());

        TagResolver.resolve(defs, index);

        assertEquals(defsSnapshot, defs, "resolve must not mutate the definitions map");
        assertEquals(blocksSnapshot, index.blocks(), "resolve must not mutate the index");
        assertEquals(tagsSnapshot, index.tags(), "resolve must not mutate the index");
    }

    @Test
    void resolvingTheSameInputsTwiceYieldsEqualResults() {
        BlockIndex index = BlockIndex.builder().block(id("mod:a")).build();
        Map<Ident, RawTagDef> defs = Map.of(
                id("mod:t"), def(List.of(), Set.of(id("mod:a")), List.of(), Set.of()));

        Resolution first = TagResolver.resolve(defs, index);
        Resolution second = TagResolver.resolve(defs, index);

        assertEquals(first.byTag(), second.byTag());
        assertEquals(first.byBlock(), second.byBlock());
    }

    @Test
    void cycleYieldsCycleDiagnosticAndResolvesToWhatIsReachableWithoutTraversingIt() {
        BlockIndex index = BlockIndex.builder().block(id("mod:x")).block(id("mod:y")).build();
        Map<Ident, RawTagDef> defs = new LinkedHashMap<>();
        defs.put(id("mod:a"), def(List.of(), Set.of(id("mod:x")), List.of(), Set.of(id("mod:b"))));
        defs.put(id("mod:b"), def(List.of(), Set.of(id("mod:y")), List.of(), Set.of(id("mod:a"))));

        Resolution res = TagResolver.resolve(defs, index);

        assertTrue(res.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.Cycle),
                "a A->B->A reference cycle must be reported");
        // NOT empty and NOT null: whatever is reachable without traversing the cycle survives.
        assertEquals(Set.of(id("mod:x"), id("mod:y")), res.blocksOf(id("mod:a")));
        assertEquals(Set.of(id("mod:x"), id("mod:y")), res.blocksOf(id("mod:b")));
    }

    @Test
    void selfReferencingTagIdiomSurvivesAndProducesNoGarbage() {
        // The documented idiom for a pack to "extend an existing tag" is to reference itself.
        BlockIndex index = BlockIndex.builder().block(id("mod:x")).build();
        RawTagDef selfExtending = def(List.of(), Set.of(id("mod:x")), List.of(), Set.of(id("mod:a")));

        Resolution res = TagResolver.resolve(Map.of(id("mod:a"), selfExtending), index);

        assertEquals(Set.of(id("mod:x")), res.blocksOf(id("mod:a")));
    }

    @Test
    void missingTagReferenceYieldsWarnMissingReferenceAndTheRestOfTheTagStillResolves() {
        BlockIndex index = BlockIndex.builder().block(id("mod:x")).build();
        RawTagDef def = def(List.of(), Set.of(id("mod:x")), List.of(), Set.of(id("mod:does_not_exist")));

        Resolution res = TagResolver.resolve(Map.of(id("mod:a"), def), index);

        assertEquals(Set.of(id("mod:x")), res.blocksOf(id("mod:a")),
                "the rest of the tag must still resolve despite the missing reference");
        Diagnostic.MissingReference missing = res.diagnostics().stream()
                .filter(d -> d instanceof Diagnostic.MissingReference)
                .map(d -> (Diagnostic.MissingReference) d)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a MissingReference diagnostic"));
        assertEquals(id("mod:a"), missing.from());
        assertEquals(id("mod:does_not_exist"), missing.to());
        assertEquals(Diagnostic.Severity.WARN, missing.severity());
    }

    @Test
    void badPatternYieldsBadPatternDiagnosticAndNoExceptionEscapes() {
        BlockIndex index = BlockIndex.builder().block(id("mod:valid_block")).build();
        RawTagDef def = def(List.of("^mod:valid_.*$", "(unclosed"), Set.of(), List.of(), Set.of());

        Resolution res = assertDoesNotThrow(() -> TagResolver.resolve(Map.of(id("mod:a"), def), index),
                "a non-compiling regex must never let a PatternSyntaxException escape");

        assertEquals(Set.of(id("mod:valid_block")), res.blocksOf(id("mod:a")),
                "the valid pattern must still be applied despite the sibling bad one");
        assertTrue(res.diagnostics().stream().anyMatch(d -> d instanceof Diagnostic.BadPattern));
    }

    @Test
    void blockMatchedByBothExplicitListAndPatternAppearsExactlyOnce() {
        // Duplicates inflated the solute's blend weight downstream (see BlockIndex javadoc).
        BlockIndex index = BlockIndex.builder().block(id("mod:x")).build();
        RawTagDef def = def(List.of("^mod:x$"), Set.of(id("mod:x")), List.of(), Set.of());

        Resolution res = TagResolver.resolve(Map.of(id("mod:a"), def), index);

        assertEquals(1, res.blocksOf(id("mod:a")).size());
        assertEquals(1, res.tagsOf(id("mod:x")).size());
    }

    @Test
    void untaggedBlocksAppearInByBlockWithAnEmptySet() {
        BlockIndex index = BlockIndex.builder().block(id("mod:untagged")).build();

        Resolution res = TagResolver.resolve(Map.of(), index);

        assertTrue(res.byBlock().containsKey(id("mod:untagged")),
                "an untagged block must not fall out of the pipeline");
        assertEquals(Set.of(), res.byBlock().get(id("mod:untagged")));
        assertEquals(Set.of(), res.tagsOf(id("mod:untagged")));
    }

    @Test
    void blocksOfAndTagsOfReturnEmptyNeverNullForUnknownIds() {
        BlockIndex index = BlockIndex.builder().block(id("mod:a")).build();
        Resolution res = TagResolver.resolve(Map.of(), index);

        assertNotNull(res.blocksOf(id("mod:no_such_tag")));
        assertEquals(Set.of(), res.blocksOf(id("mod:no_such_tag")));
        assertNotNull(res.tagsOf(id("mod:no_such_block")));
        assertEquals(Set.of(), res.tagsOf(id("mod:no_such_block")));
    }
}
