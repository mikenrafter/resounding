package dev.thedocruby.resounding.debug.math;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drop-oldest bounded queue semantics, with no Minecraft on the classpath.
 */
class RingBufferTest {

    @Test
    void overflowDropsOldestElementFirst() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        buffer.offer("a");
        buffer.offer("b");
        buffer.offer("c");
        buffer.offer("d");

        assertEquals(3, buffer.size());
        assertEquals(List.of("b", "c", "d"), buffer.asList());
    }

    @Test
    void capacityZeroNeverStoresAnything() {
        RingBuffer<Integer> buffer = new RingBuffer<>(0);

        buffer.offer(1);
        buffer.offer(2);

        assertEquals(0, buffer.capacity());
        assertEquals(0, buffer.size());
        assertTrue(buffer.asList().isEmpty());
    }

    @Test
    void capacityOneKeepsOnlyTheLatestElement() {
        RingBuffer<Integer> buffer = new RingBuffer<>(1);

        buffer.offer(10);
        buffer.offer(20);

        assertEquals(1, buffer.capacity());
        assertEquals(1, buffer.size());
        assertEquals(List.of(20), buffer.asList());
    }

    @Test
    void iterationOrderMatchesInsertionOrder() {
        RingBuffer<String> buffer = new RingBuffer<>(4);
        buffer.offer("first");
        buffer.offer("second");
        buffer.offer("third");

        assertEquals(List.of("first", "second", "third"), buffer.asList());
    }
}
