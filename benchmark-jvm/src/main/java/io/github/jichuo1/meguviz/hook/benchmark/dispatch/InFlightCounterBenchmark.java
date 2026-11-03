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
 * decrement) with the current AtomicInteger and a LongAdder. Numbers only: the core keeps its
 * AtomicInteger semantics this round. One counter is shared by every thread, as in
 * CoreRegistration. Host JDK microbenchmark only, not ART evidence.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class InFlightCounterBenchmark {
    private volatile int state;
    private final AtomicInteger atomic = new AtomicInteger();
    private final LongAdder adder = new LongAdder();

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

    @Benchmark @Threads(1)
    public boolean atomicInteger1Thread() {
        return atomicLease();
    }

    @Benchmark @Threads(1)
    public boolean longAdder1Thread() {
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
}
