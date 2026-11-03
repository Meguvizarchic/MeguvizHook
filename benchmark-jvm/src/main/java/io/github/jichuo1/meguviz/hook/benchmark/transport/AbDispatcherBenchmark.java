package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.TraceBatchDispatcher;

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

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Round-2 same-run A/B of TraceBatchDispatcher.submit + get: round-1 copy against the current
 * class. executor=inline runs the task inside execute; deferred keeps it and runs it on the same
 * thread after submit returns (no thread hand-off, so the dispatcher's own cost is visible).
 * outcome=empty finds no pending record; accepted captures one record per operation first (the
 * same capture cost for both implementations). Host JVM only; not ART evidence.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
public class AbDispatcherBenchmark {
    @Param({"round1", "current"})
    public String impl;

    @Param({"inline", "deferred"})
    public String executor;

    @Param({"empty", "accepted"})
    public String outcome;

    private boolean round1;
    private boolean deferred;
    private boolean capture;
    private CallbackExceptionTracker tracker;
    private TraceFixtures.Producer producer;
    private final CallbackExceptionTracker.BatchTransport transport = events -> events.size();
    private Runnable kept;
    private Executor run;

    @Setup(Level.Trial)
    public void setUp() {
        round1 = "round1".equals(impl);
        deferred = "deferred".equals(executor);
        capture = "accepted".equals(outcome);
        tracker = new CallbackExceptionTracker("bench-dispatch", 16);
        producer = new TraceFixtures.Producer(tracker, "bench.dispatch.hook",
                new TraceFixtures.SyntheticFailure("dispatch", 2));
        run = deferred ? task -> kept = task : Runnable::run;
    }

    @Benchmark
    public CallbackExceptionTracker.BatchAttempt submitAndGet() throws Exception {
        if (capture) producer.capture();
        if (round1) {
            Round1Transport.Attempt attempt = Round1Transport.submit(run, tracker, transport, 4);
            if (deferred) runKept();
            return attempt.get();
        }
        TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(run, tracker, transport, 4);
        if (deferred) runKept();
        return attempt.get();
    }

    private void runKept() {
        Runnable task = kept;
        kept = null;
        task.run();
    }
}
