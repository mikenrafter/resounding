package dev.thedocruby.resounding.debug.math;

import java.util.Collections;
import java.util.List;

/**
 * Bounded drop-oldest-on-overflow queue backing live/capture ray caps.
 */
public final class RingBuffer<T> {

    private final int capacity;

    public RingBuffer(int capacity) {
        this.capacity = capacity;
    }

    public void offer(T element) {
        // intentionally wrong: drops nothing, stores nothing
    }

    public List<T> asList() {
        return Collections.emptyList();
    }

    public int size() {
        return 0;
    }

    public int capacity() {
        return capacity;
    }
}
