package dev.thedocruby.resounding.data;

import dev.thedocruby.resounding.material.Material;
import dev.thedocruby.resounding.tag.Ident;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaterialCacheTest {

    private static Ident id(String s) {
        return Ident.parse(s);
    }

    @Test
    void roundTripsThroughWriteAndRead() throws Exception {
        Map<Ident, Material> original = Map.of(
                id("minecraft:stone"), new Material(1.5e7, 0.98, -0.69),
                id("minecraft:air"), new Material(412.0, 1.0, 0.0));

        StringWriter out = new StringWriter();
        MaterialCache.write(out, original);

        assertEquals(original, MaterialCache.read(new StringReader(out.toString())));
    }

    @Test
    void writesEntriesSortedSoTheFileDoesNotChurnBetweenLaunches() throws Exception {
        StringWriter out = new StringWriter();
        MaterialCache.write(out, Map.of(
                id("minecraft:zebra"), new Material(1.0, 1.0, 1.0),
                id("minecraft:apple"), new Material(1.0, 1.0, 1.0),
                id("minecraft:mango"), new Material(1.0, 1.0, 1.0)));

        String json = out.toString();
        assertTrue(json.indexOf("apple") < json.indexOf("mango"), json);
        assertTrue(json.indexOf("mango") < json.indexOf("zebra"), json);
    }

    @Test
    void missingFieldsFallBackInsteadOfThrowing() {
        // The regression: recall() read fields with (double) getOrDefault("impedance", 350), which
        // throws ClassCastException on an absent key because the 350 default boxes to Integer.
        Map<Ident, Material> read = assertDoesNotThrow(() ->
                MaterialCache.read(new StringReader("{ \"minecraft:stone\": { } }")));

        Material stone = read.get(id("minecraft:stone"));
        assertNotNull(stone);
        assertEquals(412.0, stone.impedance());
        assertEquals(1.0, stone.permeation());
    }

    @Test
    void malformedDocumentYieldsAnEmptyMapRatherThanThrowing() {
        // A corrupt cache must be survivable - it can always be regenerated.
        assertEquals(Map.of(), assertDoesNotThrow(() -> MaterialCache.read(new StringReader("{ not json"))));
        assertEquals(Map.of(), assertDoesNotThrow(() -> MaterialCache.read(new StringReader(""))));
        assertEquals(Map.of(), assertDoesNotThrow(() -> MaterialCache.read(new StringReader("[1,2,3]"))));
    }

    @Test
    void oneMalformedIdDoesNotDiscardTheRestOfTheDocument() {
        Map<Ident, Material> read = MaterialCache.read(new StringReader(
                "{ \"minecraft:stone\": {\"impedance\": 5.0}, \"bad::id\": {\"impedance\": 9.0} }"));

        assertEquals(List.of(id("minecraft:stone")), List.copyOf(read.keySet()));
        assertEquals(5.0, read.get(id("minecraft:stone")).impedance());
    }
}
