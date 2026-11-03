package io.github.jichuo1.meguviz.hook.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * In-flight lease counter shared by every channel of one logical registration. It is only reported
 * (as {@code CloseReport.inFlightAtClose}); admission stays linearized by the caller's state
 * re-read after {@link #enter()}, and close never waits on this count.
 *
 * <p>Uncontended, a lease is one atomic add on a single shared word (the inherited value), exactly
 * the cost of the former plain {@code AtomicInteger}. The first increment that observes another
 * lease still in flight (another thread, or same-thread reentry) publishes a padded cell table once;
 * from then on each lease uses the cell selected by its thread, so threads on different cores stop
 * writing one cache line. A failed-CAS trigger was measured and rejected: the read-then-CAS step
 * made the uncontended path slower than a single atomic add.
 *
 * <p>Every lease decrements the location it incremented: the {@link #enter()} token names it, and
 * the token-free {@link #exitUntracked()} of the split tryEnter/exit protocol re-derives it from the
 * current thread. The only possible mismatch is a lease that entered on the shared word before the
 * table existed and exits on its thread's cell. {@link #sum()} reads the table and its cells before
 * the shared word, so such a lease is never seen as departed without having arrived. Hence the sum
 * is never negative, equals the number of open leases when no lease enters or exits during the
 * read, and otherwise counts every lease open for the whole read plus possibly some that entered or
 * exited during it. It is not an atomic snapshot.
 */
final class InFlightCounter extends AtomicInteger {
    private static final long serialVersionUID = 1L;

    /** Token of a lease held on the shared word. */
    static final int SHARED = 0;

    /** Cells are 32 ints (128 bytes) apart, like the default {@code @Contended} padding. */
    private static final int STRIDE = 32;
    /** Power of two of at least twice the processors seen at class initialization, capped at 64. */
    private static final int CELLS = cellCount(Runtime.getRuntime().availableProcessors());
    private static final int SHIFT = 32 - Integer.numberOfTrailingZeros(CELLS);

    // Published once, never replaced, so a thread's cell is stable for the counter's lifetime.
    private transient volatile AtomicIntegerArray cells;

    static int cellCount(int processors) {
        int wanted = Math.max(2, Math.min(processors, 32)) * 2;
        return Integer.highestOneBit(wanted - 1) << 1;
    }

    /** Opens one lease and returns the token that {@link #exit(int)} must receive. */
    int enter() {
        AtomicIntegerArray striped = cells;
        if (striped == null) {
            // The prior value tells whether another lease is open; only then pay for the table.
            // This lease itself stays on the shared word.
            if (getAndIncrement() != 0) inflate();
            return SHARED;
        }
        int slot = slot();
        striped.getAndIncrement(slot);
        return slot;
    }

    /** Closes the lease opened by the {@link #enter()} call that returned {@code token}. */
    void exit(int token) {
        if (token == SHARED) {
            getAndDecrement();
        } else {
            cells.getAndDecrement(token);
        }
    }

    /**
     * Closes a lease of the split tryEnter/exit protocol, which has no token: the shared word while
     * no table exists, otherwise the current thread's cell (see the class comment for why a lease
     * opened on the shared word just before inflation is still accounted for correctly).
     */
    void exitUntracked() {
        AtomicIntegerArray striped = cells;
        if (striped == null) {
            getAndDecrement();
        } else {
            striped.getAndDecrement(slot());
        }
    }

    /** Open leases as defined in the class comment, saturated to {@code [0, Integer.MAX_VALUE]}. */
    int sum() {
        long total = rawSum();
        // Thread.getId is overridable; a subclass returning varying ids could move a lease between
        // cells. Quiescent totals stay exact even then; only such a transient (or a wrapped word,
        // which cannot happen with real leases) reaches the bounds.
        return total < 0 ? 0 : total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** Unsaturated sum in the read order the class comment relies on: table, cells, shared word. */
    long rawSum() {
        AtomicIntegerArray striped = cells;
        long total = 0;
        if (striped != null) {
            for (int cell = 1; cell <= CELLS; cell++) total += striped.get(cell * STRIDE);
        }
        return total + get();
    }

    /** Whether the cell table was published; for tests and diagnostics. */
    boolean inflated() {
        return cells != null;
    }

    private synchronized void inflate() {
        if (cells == null) {
            // Index 0 and the tail stay unused padding around the CELLS live cells.
            cells = new AtomicIntegerArray((CELLS + 1) * STRIDE);
        }
    }

    @SuppressWarnings("deprecation") // threadId() is Java 19+, above this library's Java 8 / Android floor.
    private static int slot() {
        long id = Thread.currentThread().getId();
        // Fibonacci hashing spreads consecutive thread ids (typical of pools) over distinct cells.
        int hash = (int) (id ^ (id >>> 32)) * 0x9E3779B9;
        return ((hash >>> SHIFT) + 1) * STRIDE;
    }
}
