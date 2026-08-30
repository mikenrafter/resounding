package dev.thedocruby.resounding.debug.math;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded drop-oldest-on-overflow queue backing live/capture ray caps.
 */
public final class RingBuffer<T> {

    private final int capacity;
    private final Object[] buffer;
    private int head;
    private int size;

    public RingBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    public void offer(T element) {
        if (capacity == 0) {
            return;
        }
        if (size < capacity) {
            buffer[(head + size) % capacity] = element;
            size++;
        } else {
            buffer[head] = element;
            head = (head + 1) % capacity;
        }
    }

    @SuppressWarnings("unchecked")
    public List<T> asList() {
        List<T> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add((T) buffer[(head + i) % capacity]);
        }
        return List.copyOf(result);
    }

    public int size() {
        return size;
    }

    public void clear() {
        head = 0;
        size = 0;
        java.util.Arrays.fill(buffer, null);
    }

    public int capacity() {
        return capacity;
    }
}
