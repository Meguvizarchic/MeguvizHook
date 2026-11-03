package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Batch frame formation (JSON encoding included) and receiver-side parsing for 1/16/128 records
 * with one stack frame each, within the 64 KiB frame bound. Host JVM only; not ART evidence.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class BatchFrameBenchmark {
    private static final int MAX_FRAME_BYTES = 65536;
    private static final int MAX_RECORDS = 1024;

    @Param({"1", "16", "128"})
    public int records;

    private List<CallbackExceptionTracker.Event> events;
    private CallbackExceptionTracker.BatchTransport sender;
    private JsonLineTraceTransport.RecordWriter receiver;
    private byte[] frame;
    private Blackhole sink;

    @Setup(Level.Trial)
    public void setUp(Blackhole blackhole) throws Exception {
        sink = blackhole;
        events = Collections.unmodifiableList(TraceFixtures.events(records, null, 6));
        // One stack frame per record keeps 128 records inside the 64 KiB frame bound.
        sender = BatchTraceTransport.sender(bytes -> sink.consume(bytes), false, 1, MAX_FRAME_BYTES, MAX_RECORDS);
        receiver = BatchTraceTransport.receiver(batch -> sink.consume(batch), MAX_FRAME_BYTES, MAX_RECORDS);
        byte[][] captured = new byte[1][];
        int accepted = BatchTraceTransport.sender(bytes -> captured[0] = bytes, false, 1, MAX_FRAME_BYTES, MAX_RECORDS)
                .sendBatch(events);
        if (accepted != records) throw new IllegalStateException("batch did not fit: " + accepted);
        frame = captured[0];
    }

    @Benchmark
    public int encodeFrame() throws Exception {
        return sender.sendBatch(events);
    }

    @Benchmark
    public void decodeFrame() throws Exception {
        receiver.writeRecord(frame);
    }
}
