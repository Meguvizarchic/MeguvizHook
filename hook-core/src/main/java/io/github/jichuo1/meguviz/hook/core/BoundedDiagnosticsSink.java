package io.github.jichuo1.meguviz.hook.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed-capacity in-memory sink. Once the cap is reached, further events are only counted, so
 * diagnostics can never grow without bound inside a hooked process.
 */
public final class BoundedDiagnosticsSink implements DiagnosticsSink {
    private final int capacity;
    private final List<Event> events;
    private long dropped;

    public BoundedDiagnosticsSink(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.events = new ArrayList<Event>(capacity);
    }

    @Override
    public synchronized void record(Event event) {
        if (events.size() < capacity) {
            events.add(event);
        } else {
            dropped++;
        }
    }

    public synchronized List<Event> events() {
        return Collections.unmodifiableList(new ArrayList<Event>(events));
    }

    public synchronized long droppedCount() {
        return dropped;
    }
}
