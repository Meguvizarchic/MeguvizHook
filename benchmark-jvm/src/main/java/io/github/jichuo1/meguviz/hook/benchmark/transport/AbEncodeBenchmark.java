package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;

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

import java.util.concurrent.TimeUnit;

/**
 * Same-run A/B of the round-0 code paths (LegacyTransport copies) against the current ones, for a
 * host where other jobs make separate before/after runs noisy. Host JVM only; not ART evidence.
 * shape: small = no message/no frames, stack8 = 8 frames, msg1k32 = 1 KB ASCII message + 32 frames.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
public class AbEncodeBenchmark {
    @Param({"legacy", "current"})
    public String impl;

    @Param({"small", "stack8", "msg1k32"})
    public String shape;

    private CallbackExceptionTracker.Event event;
    private boolean includeMessage;
    private int maxFrames;
    private boolean legacy;

    @Setup(Level.Trial)
    public void setUp() {
        legacy = "legacy".equals(impl);
        includeMessage = "msg1k32".equals(shape);
        maxFrames = "small".equals(shape) ? 0 : "stack8".equals(shape) ? 8 : 32;
        event = TraceFixtures.event("bench.ab.hook", new TraceFixtures.SyntheticFailure(
                includeMessage ? TraceFixtures.asciiMessage(900) : null, 40));
        byte[] a = LegacyTransport.encode(event, includeMessage, maxFrames);
        byte[] b = JsonLineTraceTransport.encode(event, includeMessage, maxFrames);
        if (!java.util.Arrays.equals(a, b)) throw new IllegalStateException("encoders disagree");
    }

    @Benchmark
    public byte[] jsonEncode() {
        return legacy ? LegacyTransport.encode(event, includeMessage, maxFrames)
                : JsonLineTraceTransport.encode(event, includeMessage, maxFrames);
    }
}
