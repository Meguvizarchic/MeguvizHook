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
 * JSONL record encoding on the caller's worker. message=none/maxFrames=0 is the small record,
 * ascii1k/unicode1k are ~1 KB records, and maxFrames 8/32 add stack frames from a 40-frame trace.
 * Host JVM only; not ART evidence.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class JsonLineEncodeBenchmark {
    @Param({"none", "ascii1k", "unicode1k"})
    public String message;

    @Param({"0", "8", "32"})
    public int maxFrames;

    private CallbackExceptionTracker.Event event;
    private boolean includeMessage;

    @Setup(Level.Trial)
    public void setUp() {
        event = TraceFixtures.event("bench.encode.hook", new TraceFixtures.SyntheticFailure(
                TraceFixtures.message(message), 40));
        includeMessage = !"none".equals(message);
    }

    @Benchmark
    public byte[] encode() {
        return JsonLineTraceTransport.encode(event, includeMessage, maxFrames);
    }
}
