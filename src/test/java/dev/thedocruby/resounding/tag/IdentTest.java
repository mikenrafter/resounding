package dev.thedocruby.resounding.tag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ident is the single key domain for the whole pipeline (see class javadoc): a namespaced id that
 * replaces three incompatible string key forms the old code mixed in one {@code HashMap<String, ?>}.
 */
class IdentTest {

    @Test
    void parseReadsExplicitNamespaceAndPath() {
        Ident id = Ident.parse("minecraft:stone");
        assertEquals("minecraft", id.namespace());
        assertEquals("stone", id.path());
    }

    @Test
    void parseDefaultsNamespaceWhenAbsent() {
        Ident id = Ident.parse("stone");
        assertEquals(Ident.DEFAULT_NAMESPACE, id.namespace());
        assertEquals("stone", id.path());
    }

    @Test
    void toStringRoundTripsThroughParse() {
        Ident original = Ident.parse("modid:some/nested_path");
        Ident roundTripped = Ident.parse(original.toString());
        assertEquals(original, roundTripped, "toString() must round-trip through parse()");
        assertEquals("modid:some/nested_path", original.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "minecraft:", ":stone", "a:b:c"})
    void parseRejectsMalformedInput(String malformed) {
        assertThrows(IllegalArgumentException.class, () -> Ident.parse(malformed),
                "malformed identifier '" + malformed + "' must not silently parse");
    }

    @Test
    void constructorRejectsEmptyParts() {
        assertThrows(IllegalArgumentException.class, () -> new Ident("", "stone"));
        assertThrows(IllegalArgumentException.class, () -> new Ident("minecraft", ""));
    }

    @Test
    void constructorRejectsSeparatorInsideAPart() {
        // a translation-key-shaped string smuggled into the namespace slot, the exact confusion
        // Ident exists to make impossible (see class javadoc)
        assertThrows(IllegalArgumentException.class, () -> new Ident("mine:craft", "stone"));
        assertThrows(IllegalArgumentException.class, () -> new Ident("minecraft", "sto:ne"));
    }

    @Test
    void equalityAndHashingWorkAsAMapKey() {
        Ident a = Ident.parse("minecraft:stone");
        Ident b = Ident.parse("minecraft:stone");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        Map<Ident, String> map = new HashMap<>();
        map.put(a, "found it");
        assertEquals("found it", map.get(b), "a separately-parsed equal Ident must hit the same map entry");
    }

    @Test
    void compareToIsConsistentWithEqualsAndAntisymmetric() {
        Ident a = Ident.parse("minecraft:apple");
        Ident b = Ident.parse("minecraft:banana");
        Ident aAgain = Ident.parse("minecraft:apple");

        assertEquals(0, a.compareTo(aAgain), "equal idents must compare as 0");
        assertEquals(0, aAgain.compareTo(a));

        int cmp = a.compareTo(b);
        assertNotEquals(0, cmp, "distinct idents must not compare as equal");
        assertTrue(Integer.signum(cmp) == -Integer.signum(b.compareTo(a)), "compareTo must be antisymmetric");
    }

    @Test
    void compareToOrdersByNamespaceThenPath() {
        // Assumed ordering: namespace dominates, path breaks ties within a namespace.
        // This mirrors the record's own field order and vanilla Identifier behaviour;
        // flagged as an assumption in the report since the javadoc doesn't state it.
        Ident a1 = Ident.parse("a:zzz");
        Ident b1 = Ident.parse("b:aaa");
        assertTrue(a1.compareTo(b1) < 0, "namespace 'a' must sort before namespace 'b' regardless of path");

        Ident aX = Ident.parse("a:x");
        Ident aY = Ident.parse("a:y");
        assertTrue(aX.compareTo(aY) < 0, "within the same namespace, path must break the tie");
    }

    @Test
    void sortingIsStableAcrossRepeatedRuns() {
        List<Ident> ids = List.of(
                Ident.parse("b:c"), Ident.parse("a:z"), Ident.parse("a:a"), Ident.parse("c:a"));
        List<Ident> sortedOnce = ids.stream().sorted().toList();
        List<Ident> sortedTwice = ids.stream().sorted().toList();
        assertEquals(sortedOnce, sortedTwice, "sorting the same input twice must produce the same order");
    }
}
