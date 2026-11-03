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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Compression of one plain batch frame: stateless codec vs thread-owned Session vs
 * SerializedSession. records=1 stays below the 1 KiB threshold (RAW frame); records=16 is
 * compressed with the schema dictionary. Host JVM only; not ART evidence.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class CompressionBenchmark {
    private static final int LIMIT = 65536;

    @Param({"stateless", "session", "serialized"})
    public String mode;

    @Param({"1", "16"})
    public int records;

    private BoundedTraceCompression codec;
    private BoundedTraceCompression.Session session;
    private BoundedTraceCompression.SerializedSession serialized;
    private List<CallbackExceptionTracker.Event> events;
    private byte[] plainFrame;
    private byte[] wireFrame;
    private CallbackExceptionTracker.BatchTransport batchSender;
    private JsonLineTraceTransport.RecordWriter receiver;

    @Setup(Level.Trial)
    public void setUp(Blackhole sink) throws Exception {
        codec = new BoundedTraceCompression(LIMIT, LIMIT + BoundedTraceCompression.HEADER_BYTES, 1024, 64,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        events = Collections.unmodifiableList(TraceFixtures.events(records, null, 6));
        byte[][] captured = new byte[1][];
        BatchTraceTransport.sender(bytes -> captured[0] = bytes, false, 2, LIMIT, 1024).sendBatch(events);
        plainFrame = captured[0];
        wireFrame = codec.encode(plainFrame).bytes();
        BatchTraceTransport.BatchWriter batchSink = batch -> sink.consume(batch);
        JsonLineTraceTransport.RecordWriter wire = bytes -> sink.consume(bytes);
        switch (mode) {
            case "stateless":
                batchSender = codec.batchSender(wire, false, 2, LIMIT, 1024);
                receiver = BatchTraceTransport.compressedReceiver(codec, batchSink, LIMIT, 1024);
                break;
            case "session":
                session = codec.newSession();
                batchSender = session.batchSender(wire, false, 2, LIMIT, 1024);
                receiver = BatchTraceTransport.compressedSessionReceiver(session, batchSink, LIMIT, 1024);
                break;
            case "serialized":
                serialized = codec.newSerializedSession();
                batchSender = serialized.batchSender(wire, false, 2, LIMIT, 1024);
                receiver = BatchTraceTransport.compressedSerializedReceiver(serialized, batchSink, LIMIT, 1024);
                break;
            default:
                throw new IllegalArgumentException(mode);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (session != null) session.close();
        if (serialized != null) serialized.close();
    }

    /** Public encode of an existing plain frame (includes the documented input snapshot). */
    @Benchmark
    public Object encodeFrame() throws IOException {
        switch (mode) {
            case "session": return session.encode(plainFrame);
            case "serialized": return serialized.encode(plainFrame);
            default: return codec.encode(plainFrame);
        }
    }

    /** Public decode of one wire frame. */
    @Benchmark
    public byte[] decodeFrame() throws IOException {
        switch (mode) {
            case "session": return session.decode(wireFrame);
            case "serialized": return serialized.decode(wireFrame);
            default: return codec.decode(wireFrame);
        }
    }

    /** JSON encode + batch frame + compression, as used by a poller transport. */
    @Benchmark
    public int batchSend() throws Exception {
        return batchSender.sendBatch(events);
    }

    /** Paired decode + batch parse, as used by a module-side consumer. */
    @Benchmark
    public void pairedReceive() throws Exception {
        receiver.writeRecord(wireFrame);
    }
}
