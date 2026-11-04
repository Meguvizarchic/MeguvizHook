package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Design candidates for a contention-adaptive in-flight counter, measured standalone by
 * {@link InFlightCounterBenchmark}. Both start with one shared word and switch to padded per-thread
 * cells once they observe concurrency; the same lease always increments and decrements one location.
 */
final class StripedLeaseCandidates {
    private StripedLeaseCandidates() {
    }

    static final int STRIDE = 32;
    static final int CELLS = Integer.highestOneBit(Math.max(4, Runtime.getRuntime().availableProcessors() * 2 - 1) << 1);
    static final int SHIFT = 32 - Integer.numberOfTrailingZeros(CELLS);

    @SuppressWarnings("deprecation")
    static int slot() {
        long id = Thread.currentThread().getId();
        return (((int) (id ^ (id >>> 32)) * 0x9E3779B9 >>> SHIFT) + 1) * STRIDE;
    }

    /** Inflates on the first failed CAS of the shared word (Striped64 style). */
    static final class CasFirst extends AtomicInteger {
        private static final long serialVersionUID = 1L;
        private transient volatile AtomicIntegerArray cells;

        int enter() {
            AtomicIntegerArray striped = cells;
            if (striped == null) {
                int current = get();
                if (compareAndSet(current, current + 1)) return 0;
                striped = inflate();
            }
            int slot = slot();
            striped.getAndIncrement(slot);
            return slot;
        }

        void exit(int slot) {
            if (slot == 0) decrementAndGet();
            else cells.getAndDecrement(slot);
        }

        private synchronized AtomicIntegerArray inflate() {
            AtomicIntegerArray current = cells;
            if (current == null) cells = current = new AtomicIntegerArray((CELLS + 1) * STRIDE);
            return current;
        }
    }

    /**
     * Uses XADD on the shared word; an increment that observes another lease only marks the state as
     * pending, and the next lease publishes the table before it increments (the core's design).
     */
    static final class OverlapFirst extends AtomicInteger {
        private static final long serialVersionUID = 1L;
        private static final AtomicIntegerArray PENDING = new AtomicIntegerArray(0);
        private transient volatile AtomicIntegerArray cells;
        private transient volatile AtomicIntegerArray table;

        int enter() {
            AtomicIntegerArray striped = cells;
            if (striped == null) {
                if (getAndIncrement() != 0) cells = PENDING;
                return 0;
            }
            if (striped == PENDING) striped = inflate();
            int slot = slot();
            striped.getAndIncrement(slot);
            return slot;
        }

        void exit(int slot) {
            if (slot == 0) decrementAndGet();
            else table.getAndDecrement(slot);
        }

        private synchronized AtomicIntegerArray inflate() {
            AtomicIntegerArray current = table;
            if (current == null) table = current = new AtomicIntegerArray((CELLS + 1) * STRIDE);
            cells = current;
            return current;
        }
    }
}
