package dev.thedocruby.resounding.debug.math;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Octree depth-to-color table, with no Minecraft on the classpath.
 */
class DepthColorTest {

    // Locked-in palette: five distinct ARGB colors, one per valid node size.
    private static final int COLOR_16 = 0xFFFF0000;
    private static final int COLOR_8 = 0xFF00FF00;
    private static final int COLOR_4 = 0xFF0000FF;
    private static final int COLOR_2 = 0xFFFFFF00;
    private static final int COLOR_1 = 0xFFFF00FF;

    @Test
    void validSizesMapToDistinctFixedColors() {
        assertAll(
                () -> assertEquals(COLOR_16, DepthColor.colorForSize(16)),
                () -> assertEquals(COLOR_8, DepthColor.colorForSize(8)),
                () -> assertEquals(COLOR_4, DepthColor.colorForSize(4)),
                () -> assertEquals(COLOR_2, DepthColor.colorForSize(2)),
                () -> assertEquals(COLOR_1, DepthColor.colorForSize(1)));

        Set<Integer> colors = new HashSet<>();
        colors.add(DepthColor.colorForSize(16));
        colors.add(DepthColor.colorForSize(8));
        colors.add(DepthColor.colorForSize(4));
        colors.add(DepthColor.colorForSize(2));
        colors.add(DepthColor.colorForSize(1));
        assertEquals(5, colors.size(), "each valid size must produce a unique color");
    }

    @Test
    void unexpectedSizeThrowsIllegalArgumentException() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> DepthColor.colorForSize(3));
        assertTrue(thrown.getMessage().contains("3"));
    }
}
