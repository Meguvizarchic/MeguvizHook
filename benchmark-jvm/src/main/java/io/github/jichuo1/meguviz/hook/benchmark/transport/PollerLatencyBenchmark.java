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

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * End-to-end capture -> transport-accepted latency through TraceBatchPoller on a real
 * single-thread ScheduledThreadPoolExecutor. Each sampled operation captures {@code burst}
 * events on the benchmark thread (standing in for a hook thread) and spins until the in-memory
 * transport has accepted all of them. An untimed per-invocation setup parks for a random fraction
 * of the poll interval so arrivals are not phase-locked to poll ticks.
 *
 * <p>config = pollMillis/waitMillis/triggerCount; 10/15/16 is a wait that is not a multiple of the
 * poll interval, so a single record below the trigger is quantized to the next tick (about 20 ms).
 * wake=signal wires the tracker's opt-in
 * CaptureSignal to TraceBatchPoller.requestPoll (one coalesced executor submission on the
 * capturing thread); wake=none is the default periodic polling. expiry=deadline adds the opt-in
 * one-shot evaluation at observedAt + wait (TraceBatchPoller.WaitExpiry.DEADLINE). Host JVM only: no Binder, ART,
 * device scheduler or power management is represented, and the spin-wait adds no wake-up latency.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
public class PollerLatencyBenchmark {
    @Param({"2/0/1", "10/0/1", "5/20/16", "10/15/16"})
    public String config;

    @Param({"1", "16"})
    public int burst;

    @Param({"none", "signal"})
    public String wake;

    /** poll = WaitExpiry.NEXT_POLL (default), deadline = WaitExpiry.DEADLINE. */
    @Param({"poll", "deadline"})
    public String expiry;

    private ScheduledThreadPoolExecutor executor;
    private CallbackExceptionTracker tracker;
    private TraceFixtures.Producer producer;
    private TraceBatchPoller poller;
    private final AtomicLong delivered = new AtomicLong();
    private long expected;
    private long pollNanos;

    @Setup(Level.Trial)
    public void setUp() {
        String[] parts = config.split("/");
        long pollMillis = Long.parseLong(parts[0]);
        long waitMillis = Long.parseLong(parts[1]);
        int trigger = Integer.parseInt(parts[2]);
        pollNanos = TimeUnit.MILLISECONDS.toNanos(pollMillis);
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "bench-trace-poller");
            thread.setDaemon(true);
            return thread;
        });
        AtomicReference<TraceBatchPoller> target = new AtomicReference<TraceBatchPoller>();
        tracker = "signal".equals(wake)
                ? new CallbackExceptionTracker("bench-latency", 4096, () -> {
                    TraceBatchPoller current = target.get();
                    if (current != null) current.requestPoll();
                })
                : new CallbackExceptionTracker("bench-latency", 4096);
        producer = new TraceFixtures.Producer(tracker, "bench.latency.hook",
                new TraceFixtures.SyntheticFailure("latency", 4));
        CallbackExceptionTracker.BatchTransport transport = this::accept;
        poller = TraceBatchPoller.startDraining(executor, tracker, transport, trigger, 64, 4,
                pollMillis, waitMillis, TimeUnit.MILLISECONDS, System::nanoTime,
                "deadline".equals(expiry) ? TraceBatchPoller.WaitExpiry.DEADLINE : TraceBatchPoller.WaitExpiry.NEXT_POLL);
        target.set(poller);
    }

    private int accept(List<CallbackExceptionTracker.Event> events) {
        delivered.addAndGet(events.size());
        return events.size();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        poller.close();
        executor.shutdownNow();
        if (poller.lastFailure() != null) throw new IllegalStateException(poller.lastFailure());
    }

    @Setup(Level.Invocation)
    public void randomPhase() {
        LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(pollNanos));
    }

    @Benchmark
    public long captureToAccepted() {
        for (int i = 0; i < burst; i++) producer.capture();
        expected += burst;
        long start = System.nanoTime();
        while (delivered.get() < expected) {
            Thread.onSpinWait();
            if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(10)) {
                throw new IllegalStateException("not delivered: " + poller.state() + " " + poller.lastFailure());
            }
        }
        return expected;
    }
}
