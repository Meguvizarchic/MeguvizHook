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
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Round-2 same-run A/B of single-record JSONL encoding: round-1 copy (Round1Transport) against
 * the current encoder. shape = message kind + frame count: none0 has no message, ascii* uses a
 * 900-char ASCII message (truncated to 512), unicode* the 400-char mixed CJK/escape message of
 * JsonLineEncodeBenchmark; the digit is maxFrames out of a 40-frame trace. encode returns the
 * exact-length record; send passes it to a consuming RecordWriter. Host JVM only; not ART.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
public class AbRound2EncodeBenchmark {
    @Param({"round1", "current"})
    public String impl;

    @Param({"none0", "ascii0", "ascii8", "unicode0", "unicode8"})
    public String shape;

    private boolean round1;
    private CallbackExceptionTracker.Event event;
    private boolean includeMessage;
    private int maxFrames;
    private Round1Transport.RecordWriter round1Writer;
    private JsonLineTraceTransport transport;

    @Setup(Level.Trial)
    public void setUp(Blackhole sink) {
        round1 = "round1".equals(impl);
        String kind = shape.substring(0, shape.length() - 1);
        maxFrames = shape.charAt(shape.length() - 1) - '0';
        includeMessage = !"none".equals(kind);
        String message = "ascii".equals(kind) ? TraceFixtures.asciiMessage(900)
                : "unicode".equals(kind) ? TraceFixtures.unicodeMessage(400) : null;
        event = TraceFixtures.event("bench.ab2.hook", new TraceFixtures.SyntheticFailure(message, 40));
        round1Writer = sink::consume;
        transport = new JsonLineTraceTransport(sink::consume, includeMessage, maxFrames);
        byte[] a = Round1Transport.encode(event, includeMessage, maxFrames);
        byte[] b = JsonLineTraceTransport.encode(event, includeMessage, maxFrames);
        if (!Arrays.equals(a, b)) throw new IllegalStateException("encoders disagree");
    }

    @Benchmark
    public byte[] encode() {
        return round1 ? Round1Transport.encode(event, includeMessage, maxFrames)
                : JsonLineTraceTransport.encode(event, includeMessage, maxFrames);
    }

    @Benchmark
    public void send() throws Exception {
        if (round1) Round1Transport.send(round1Writer, event, includeMessage, maxFrames);
        else transport.send(event);
    }
}
