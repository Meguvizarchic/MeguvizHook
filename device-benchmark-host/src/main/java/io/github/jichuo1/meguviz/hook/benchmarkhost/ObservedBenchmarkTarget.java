package io.github.jichuo1.meguviz.hook.benchmarkhost;

import java.util.concurrent.atomic.AtomicLong;

/** Separate, explicitly instrumented targets; never loaded by the unobserved workload. */
public final class ObservedBenchmarkTarget {
    // Only the separately selected observed targets use these counters. Indexes 0/1/2/3 mean
    // before, after, original, and calls on a thread other than the sampling thread. For B05,
    // before and after mean around entry and exit after normal proceed, not separate callbacks.
    // B06 likewise counts around entry, normal exit and original for each of four nested calls.
    private static final AtomicLong[] OBSERVATIONS = {
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()
    };
    private static volatile Thread observationOwner;

    private ObservedBenchmarkTarget() { }

    /** Called once by the worker before configuring the observed-mode hook. */
    public static void prepareObservation() {
        observationOwner = Thread.currentThread();
        resetObservations();
    }

    /** The module obtains this same array once during setup, never by reflection in the timed loop. */
    public static AtomicLong[] observationCounters() {
        if (observationOwner != Thread.currentThread()) {
            throw new IllegalStateException("observation is not owned by this thread");
        }
        return OBSERVATIONS.clone();
    }

    public static void resetObservations() {
        if (observationOwner != Thread.currentThread()) {
            throw new IllegalStateException("observation is not owned by this thread");
        }
        for (AtomicLong count : OBSERVATIONS) count.set(0L);
    }

    public static long[] observationSnapshot() {
        if (observationOwner != Thread.currentThread()) {
            throw new IllegalStateException("observation is not owned by this thread");
        }
        return new long[]{OBSERVATIONS[0].get(), OBSERVATIONS[1].get(),
                OBSERVATIONS[2].get(), OBSERVATIONS[3].get()};
    }

    public static long observedNoArgs() {
        if (Thread.currentThread() != observationOwner) OBSERVATIONS[3].incrementAndGet();
        OBSERVATIONS[2].incrementAndGet();
        return 42L;
    }

    public static int observedAdd(int left, int right) {
        if (Thread.currentThread() != observationOwner) OBSERVATIONS[3].incrementAndGet();
        OBSERVATIONS[2].incrementAndGet();
        return left + right;
    }

    public static int observedMutableAdd(int left, int right) {
        if (Thread.currentThread() != observationOwner) OBSERVATIONS[3].incrementAndGet();
        OBSERVATIONS[2].incrementAndGet();
        return left + right;
    }

    public static long observedAroundValue() {
        if (Thread.currentThread() != observationOwner) OBSERVATIONS[3].incrementAndGet();
        OBSERVATIONS[2].incrementAndGet();
        return 42L;
    }

    public static int observedReentrant(int depth) {
        if (depth < 0 || depth > 3) throw new IllegalArgumentException("invalid reentrant depth");
        if (Thread.currentThread() != observationOwner) OBSERVATIONS[3].incrementAndGet();
        OBSERVATIONS[2].incrementAndGet();
        return depth == 0 ? 1 : 1 + observedReentrant(depth - 1);
    }

    public static long observedSnapshot(int[] box, int delta) {
        if (Thread.currentThread() != observationOwner) OBSERVATIONS[3].incrementAndGet();
        OBSERVATIONS[2].incrementAndGet();
        box[0] += delta;
        return 42L;
    }

    public static long loopObservedB01(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += observedNoArgs();
        return sum;
    }

    public static long loopObservedB02(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += observedAdd(3, 4);
        return sum;
    }

    public static long loopObservedB03(int iterations) {
        int[] box = new int[]{0};
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += observedSnapshot(box, 1);
        return box[0] == iterations ? sum : Long.MIN_VALUE;
    }

    public static long loopObservedB04(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += observedMutableAdd(3, 4);
        return sum;
    }

    public static long loopObservedB05(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += observedAroundValue();
        return sum;
    }

    public static long loopObservedB06(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += observedReentrant(3);
        return sum;
    }
}
