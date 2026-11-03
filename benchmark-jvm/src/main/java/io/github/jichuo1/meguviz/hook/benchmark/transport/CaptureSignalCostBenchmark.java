package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.TraceBatchPoller;

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
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cost on the capturing (hook) thread of the failure path: capture + optional CaptureSignal ->
 * TraceBatchPoller.requestPoll, followed by an audited discard to keep the queue bounded. The
 * poller never sends (count trigger and observed wait are out of reach), so the executor only
 * runs requested evaluations. Host JVM only; not ART evidence.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class CaptureSignalCostBenchmark {
    @Param({"none", "signal"})
    public String wake;

    private ScheduledThreadPoolExecutor executor;
    private CallbackExceptionTracker tracker;
    private TraceFixtures.Producer producer;
    private TraceBatchPoller poller;

    @Setup(Level.Trial)
    public void setUp() {
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "bench-signal-poller");
            thread.setDaemon(true);
            return thread;
        });
        AtomicReference<TraceBatchPoller> target = new AtomicReference<TraceBatchPoller>();
        tracker = "signal".equals(wake)
                ? new CallbackExceptionTracker("bench-signal", 1024, () -> {
                    TraceBatchPoller current = target.get();
                    if (current != null) current.requestPoll();
                })
                : new CallbackExceptionTracker("bench-signal", 1024);
        producer = new TraceFixtures.Producer(tracker, "bench.signal.hook",
                new TraceFixtures.SyntheticFailure("signal", 4));
        poller = TraceBatchPoller.start(executor, tracker, events -> events.size(), 1 << 20, 32,
                1, 1, TimeUnit.DAYS);
        target.set(poller);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        poller.close();
        executor.shutdownNow();
    }

    @Benchmark
    public Object captureAndDiscard() {
        producer.capture();
        return tracker.discardNext();
    }
}
