package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Standalone comparison of the registration admission lease (state read, increment, state re-read,
 * decrement): a plain AtomicInteger (the round-1 core), a LongAdder, and the two adaptive designs of
 * {@link StripedLeaseCandidates} (inflate on a failed CAS, or on an increment that observes another
 * open lease; the core adopted the latter). One counter is shared by every thread, as in
 * CoreRegistration. The core's own counter is measured end to end by
 * {@link RegistrationContentionBenchmark}. Host JDK microbenchmark only, not ART evidence.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class InFlightCounterBenchmark {
    private volatile int state;
    private final AtomicInteger atomic = new AtomicInteger();
    private final LongAdder adder = new LongAdder();
    private final StripedLeaseCandidates.CasFirst casFirst = new StripedLeaseCandidates.CasFirst();
    private final StripedLeaseCandidates.OverlapFirst overlapFirst = new StripedLeaseCandidates.OverlapFirst();

    private boolean atomicLease() {
        if (state != 0) return false;
        atomic.incrementAndGet();
        try {
            return state == 0;
        } finally {
            atomic.decrementAndGet();
        }
    }

    private boolean adderLease() {
        if (state != 0) return false;
        adder.increment();
        try {
            return state == 0;
        } finally {
            adder.decrement();
        }
    }

    private boolean casFirstLease() {
        if (state != 0) return false;
        int lease = casFirst.enter();
        try {
            return state == 0;
        } finally {
            casFirst.exit(lease);
        }
    }

    private boolean overlapFirstLease() {
        if (state != 0) return false;
        int lease = overlapFirst.enter();
        try {
            return state == 0;
        } finally {
            overlapFirst.exit(lease);
        }
    }

    @Benchmark @Threads(1)
    public boolean atomicInteger1Thread() {
        return atomicLease();
    }

    @Benchmark @Threads(1)
    public boolean longAdder1Thread() {
        return adderLease();
    }

    @Benchmark @Threads(2)
    public boolean atomicInteger2Threads() {
        return atomicLease();
    }

    @Benchmark @Threads(2)
    public boolean longAdder2Threads() {
        return adderLease();
    }

    @Benchmark @Threads(4)
    public boolean atomicInteger4Threads() {
        return atomicLease();
    }

    @Benchmark @Threads(4)
    public boolean longAdder4Threads() {
        return adderLease();
    }

    @Benchmark @Threads(8)
    public boolean atomicInteger8Threads() {
        return atomicLease();
    }

    @Benchmark @Threads(8)
    public boolean longAdder8Threads() {
        return adderLease();
    }

    @Benchmark @Threads(1)
    public boolean casFirst1Thread() {
        return casFirstLease();
    }

    @Benchmark @Threads(1)
    public boolean overlapFirst1Thread() {
        return overlapFirstLease();
    }

    @Benchmark @Threads(2)
    public boolean casFirst2Threads() {
        return casFirstLease();
    }

    @Benchmark @Threads(2)
    public boolean overlapFirst2Threads() {
        return overlapFirstLease();
    }

    @Benchmark @Threads(4)
    public boolean casFirst4Threads() {
        return casFirstLease();
    }

    @Benchmark @Threads(4)
    public boolean overlapFirst4Threads() {
        return overlapFirstLease();
    }

    @Benchmark @Threads(8)
    public boolean casFirst8Threads() {
        return casFirstLease();
    }

    @Benchmark @Threads(8)
    public boolean overlapFirst8Threads() {
        return overlapFirstLease();
    }
}
