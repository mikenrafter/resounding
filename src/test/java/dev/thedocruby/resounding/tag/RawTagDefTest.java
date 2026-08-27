package dev.thedocruby.resounding.tag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RawTagDef replaces RawTag, whose four fields were @Nullable arrays that forced every consumer to
 * null-check and left Utils.granularFilter one stored null away from an NPE. Nulls must be
 * normalized away at construction so downstream code can never observe one.
 */
class RawTagDefTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    @Test
    void nullsNormalizeToEmptyCollections() {
        RawTagDef def = new RawTagDef(null, null, null, null, false);
        assertEquals(List.of(), def.patterns());
        assertEquals(Set.of(), def.blocks());
        assertEquals(List.of(), def.tagPatterns());
        assertEquals(Set.of(), def.tags());
    }

    @Test
    void mutatingASourceCollectionAfterConstructionDoesNotAffectTheRecord() {
        List<String> patterns = new ArrayList<>(List.of("mod:.*"));
        Set<Ident> blocks = new HashSet<>(Set.of(id("mod:a")));

        RawTagDef def = new RawTagDef(patterns, blocks, null, null, false);

        // mutate the caller's collections after the fact
        patterns.add("mod:more.*");
        blocks.add(id("mod:b"));

        assertEquals(1, def.patterns().size(), "RawTagDef must hold a defensive copy, not a live view");
        assertEquals(Set.of(id("mod:a")), def.blocks());
    }

    @Test
    void mergeUnderUnionsAllFourCollections() {
        RawTagDef lower = new RawTagDef(
                List.of("p1"), Set.of(id("mod:a")), List.of("t1"), Set.of(id("mod:tagA")), false);
        RawTagDef higher = new RawTagDef(
                List.of("p2"), Set.of(id("mod:b")), List.of("t2"), Set.of(id("mod:tagB")), false);

        RawTagDef merged = lower.mergeUnder(higher);

        assertEquals(Set.of("p1", "p2"), Set.copyOf(merged.patterns()));
        assertEquals(Set.of(id("mod:a"), id("mod:b")), merged.blocks());
        assertEquals(Set.of("t1", "t2"), Set.copyOf(merged.tagPatterns()));
        assertEquals(Set.of(id("mod:tagA"), id("mod:tagB")), merged.tags());
    }

    @Test
    void mergeUnderWithHigherReplaceDiscardsTheLowerLayerEntirely() {
        RawTagDef lower = new RawTagDef(
                List.of("p1"), Set.of(id("mod:a")), List.of("t1"), Set.of(id("mod:tagA")), false);
        RawTagDef higher = new RawTagDef(
                List.of("p2"), Set.of(id("mod:b")), List.of("t2"), Set.of(id("mod:tagB")), true);

        RawTagDef merged = lower.mergeUnder(higher);

        assertEquals(List.of("p2"), merged.patterns(), "lower's patterns must be discarded when higher replaces");
        assertEquals(Set.of(id("mod:b")), merged.blocks(), "lower's blocks must be discarded when higher replaces");
        assertEquals(List.of("t2"), merged.tagPatterns());
        assertEquals(Set.of(id("mod:tagB")), merged.tags());
    }

    @Test
    void mergeUnderDoesNotMutateEitherOperand() {
        RawTagDef lower = new RawTagDef(
                List.of("p1"), Set.of(id("mod:a")), List.of(), Set.of(), false);
        RawTagDef higher = new RawTagDef(
                List.of("p2"), Set.of(id("mod:b")), List.of(), Set.of(), false);

        lower.mergeUnder(higher);

        assertEquals(List.of("p1"), lower.patterns());
        assertEquals(Set.of(id("mod:a")), lower.blocks());
        assertEquals(List.of("p2"), higher.patterns());
        assertEquals(Set.of(id("mod:b")), higher.blocks());
    }

    @Test
    void emptyDefinitionIsRecognizedAsEmpty() {
        assertTrue(RawTagDef.empty().isEmpty());
        assertFalse(new RawTagDef(null, Set.of(id("mod:a")), null, null, false).isEmpty());
    }
}
