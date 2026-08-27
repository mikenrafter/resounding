package dev.thedocruby.resounding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the fast lane is real before anything depends on it.
 *
 * The point of Lane 1 is that the tagging pipeline runs without Minecraft. A test suite
 * that merely passes proves nothing about that, so this asserts the negative directly:
 * Minecraft must be absent from the classpath. If someone later "fixes" a compile error
 * by putting Minecraft back, this fails and says why.
 */
class HarnessSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "resounding.lane", matches = "1",
            disabledReason = "Lane 2 runs under Loom, where Minecraft is legitimately on the test classpath")
    void minecraftIsNotOnTheClasspath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("net.minecraft.block.Block"),
                "Lane 1 must run without Minecraft; something has leaked it onto the classpath");
    }

    @Test
    void junitIsWired() {
        assertTrue(true, "placeholder proving the launcher actually executes tests");
    }
}
