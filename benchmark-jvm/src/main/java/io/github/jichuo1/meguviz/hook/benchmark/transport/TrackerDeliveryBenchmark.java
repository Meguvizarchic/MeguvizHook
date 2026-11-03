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
 * Queue snapshot and acknowledged-prefix removal in CallbackExceptionTracker with N pending
 * events and an in-memory transport. The *All benchmarks refill N events in an untimed
 * per-invocation setup (JMH Level.Invocation timestamp overhead applies to small N).
 * See {@link TrackerSteadyStateBenchmark} for a variant without per-invocation setup.
 * Host JVM only; not ART evidence.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class TrackerDeliveryBenchmark {
    @Param({"1", "16", "128"})
    public int pending;

    private CallbackExceptionTracker tracker;
    private TraceFixtures.Producer producer;
    private CallbackExceptionTracker.BatchTransport acceptAll;
    private long sink;

    @Setup(Level.Trial)
    public void setUp() {
        tracker = new CallbackExceptionTracker("bench-tracker", 1024);
        producer = new TraceFixtures.Producer(tracker, "bench.tracker.hook",
                new TraceFixtures.SyntheticFailure("bench", 4));
        acceptAll = this::all;
    }

    private int all(List<CallbackExceptionTracker.Event> events) {
        sink += events.get(events.size() - 1).timeNanos();
        return events.size();
    }

    @Setup(Level.Invocation)
    public void refill() {
        for (int i = tracker.pendingCount(); i < pending; i++) producer.capture();
    }

    @Benchmark
    public int tryDeliverAll() throws Exception {
        return tracker.tryDeliverBatch(acceptAll, pending).acceptedCount();
    }

    @Benchmark
    public int deliverBatchAll() throws Exception {
        return tracker.deliverBatch(acceptAll, pending);
    }
}
