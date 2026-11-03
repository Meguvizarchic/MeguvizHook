package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.BoundedTraceCompression;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Same-run A/B for batch frames (16 and 128 records, one stack frame each) and stateless RAW
 * decode of a one-record frame. Host JVM only; not ART evidence.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
public class AbFrameBenchmark {
    private static final int LIMIT = 65536;

    @Param({"legacy", "current"})
    public String impl;

    @Param({"16", "128"})
    public int records;

    private boolean legacy;
    private List<CallbackExceptionTracker.Event> events;
    private CallbackExceptionTracker.BatchTransport sender;
    private JsonLineTraceTransport.RecordWriter receiver;
    private final List<byte[]> legacySink = new ArrayList<byte[]>(1);
    private byte[] frame;
    private byte[] rawFrame;
    private BoundedTraceCompression codec;
    private Blackhole sink;

    @Setup(Level.Trial)
    public void setUp(Blackhole blackhole) throws Exception {
        sink = blackhole;
        legacy = "legacy".equals(impl);
        events = Collections.unmodifiableList(TraceFixtures.events(records, null, 6));
        sender = BatchTraceTransport.sender(bytes -> sink.consume(bytes), false, 1, LIMIT, 1024);
        receiver = BatchTraceTransport.receiver(batch -> sink.consume(batch), LIMIT, 1024);
        List<byte[]> captured = new ArrayList<byte[]>();
        if (LegacyTransport.send(events, false, 1, LIMIT, 1024, captured) != records) {
            throw new IllegalStateException("batch did not fit");
        }
        frame = captured.get(0);
        byte[][] current = new byte[1][];
        BatchTraceTransport.sender(bytes -> current[0] = bytes, false, 1, LIMIT, 1024).sendBatch(events);
        if (!java.util.Arrays.equals(frame, current[0])) throw new IllegalStateException("senders disagree");
        codec = new BoundedTraceCompression(LIMIT, LIMIT + 20, LIMIT + 1, 0, BoundedTraceCompression.Algorithm.ZLIB);
        List<byte[]> one = new ArrayList<byte[]>();
        LegacyTransport.send(events.subList(0, 1), false, 1, LIMIT, 1024, one);
        rawFrame = codec.encode(one.get(0)).bytes();
    }

    @Benchmark
    public Object batchEncode() throws Exception {
        if (legacy) {
            legacySink.clear();
            LegacyTransport.send(events, false, 1, LIMIT, 1024, legacySink);
            return legacySink.get(0);
        }
        return sender.sendBatch(events);
    }

    @Benchmark
    public Object batchDecode() throws Exception {
        if (legacy) return LegacyTransport.receive(frame, LIMIT, 1024);
        receiver.writeRecord(frame);
        return null;
    }

    @Benchmark
    public byte[] rawDecode() throws IOException {
        return legacy ? LegacyTransport.decodeRaw(rawFrame, LIMIT, LIMIT + 20) : codec.decode(rawFrame);
    }
}
