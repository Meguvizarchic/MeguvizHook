package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Keeps N events pending: each operation snapshots N, removes the one accepted head record and
 * captures one replacement (capture cost is inside the timed region). Host JVM only.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class TrackerSteadyStateBenchmark {
    @Param({"1", "16", "128"})
    public int pending;

    private CallbackExceptionTracker tracker;
    private TraceFixtures.Producer producer;
    private CallbackExceptionTracker.BatchTransport acceptOne;
    private long sink;

    @Setup(Level.Trial)
    public void setUp() {
        tracker = new CallbackExceptionTracker("bench-steady", 1024);
        producer = new TraceFixtures.Producer(tracker, "bench.steady.hook",
                new TraceFixtures.SyntheticFailure("bench", 4));
        acceptOne = this::one;
        for (int i = 0; i < pending; i++) producer.capture();
    }

    private int one(List<CallbackExceptionTracker.Event> events) {
        sink += events.get(events.size() - 1).timeNanos();
        return 1;
    }

    @Benchmark
    public int tryDeliverOneOfN() throws Exception {
        int accepted = tracker.tryDeliverBatch(acceptOne, pending).acceptedCount();
        producer.capture();
        return accepted;
    }

    /** Capture alone (the hook-thread failure path), for reference. Drains to stay bounded. */
    @Benchmark
    public int captureOnly() {
        producer.capture();
        return tracker.discardNext() == null ? 0 : 1;
    }
}
