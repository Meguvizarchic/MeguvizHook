package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.BoundedTraceCompression;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BatchTraceTransportTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void batchUsesOneSynchronousFrameAndPreservesRecords() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch", 4);
        try {
            RuntimeException failure = new IllegalStateException("private");
            TestSupport.installOrFail(env, TestSupport.spec("batch.hook", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            for (int i = 0; i < 3; i++) {
                assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            List<byte[]> records = new ArrayList<byte[]>();
            int[] calls = {0};
            JsonLineTraceTransport.RecordWriter receiver = BatchTraceTransport.receiver(batch -> {
                calls[0]++;
                records.addAll(batch);
                assertThrows(UnsupportedOperationException.class, () -> batch.clear());
            }, 4096, 2);
            CallbackExceptionTracker.BatchTransport sender = BatchTraceTransport.sender(receiver, false, 0, 4096, 2);
            assertEquals(2, tracker.deliverBatch(sender, 3));
            assertEquals(1, tracker.pendingCount());
            assertEquals(1, calls[0]);
            assertEquals(2, records.size());
            for (byte[] record : records) {
                String json = new String(record, StandardCharsets.UTF_8);
                assertTrue(json.contains("\"hook\":\"batch.hook\""));
                assertFalse(json.contains("private"));
                assertTrue(json.endsWith("\n"));
            }
            assertEquals(1, tracker.deliverBatch(sender, 3));
            assertEquals(0, tracker.pendingCount());
            assertEquals(2, calls[0]);
        } finally { env.close(); }
    }

    @Test public void rejectsMalformedBatchBeforeCallingReceiver() throws Exception {
        int[] calls = {0};
        JsonLineTraceTransport.RecordWriter receiver = BatchTraceTransport.receiver(batch -> calls[0]++, 64, 2);
        // MHB1, count=1, length=1, payload='x'.
        byte[] valid = {0x4d, 0x48, 0x42, 0x31, 0, 0, 0, 1, 0, 0, 0, 1, 'x'};
        receiver.writeRecord(valid);
        assertEquals(1, calls[0]);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] oversizedLength = valid.clone();
        oversizedLength[8] = 0x7f;
        byte[] impossibleCount = valid.clone();
        impossibleCount[7] = 2;
        byte[] wrongMagic = valid.clone();
        wrongMagic[0] = 0;
        for (byte[] bad : new byte[][] {trailing, oversizedLength, impossibleCount, wrongMagic,
                Arrays.copyOf(valid, valid.length - 1), new byte[65]}) {
            assertThrows(IllegalArgumentException.class, () -> receiver.writeRecord(bad));
        }
        assertEquals(1, calls[0]);
    }

    @Test public void failedReceiverKeepsBatchAtTrackerAndOversizeNeverSends() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch-fail", 2);
        try {
            RuntimeException failure = new IllegalStateException("failure");
            TestSupport.installOrFail(env, TestSupport.spec("batch.fail", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            Exception rejected = new Exception("unknown receiver side effect");
            CallbackExceptionTracker.BatchTransport sender = BatchTraceTransport.sender(
                    BatchTraceTransport.receiver(records -> { throw rejected; }, 4096, 2), false, 0, 4096, 2);
            assertSame(rejected, TestSupport.capture(() -> tracker.deliverBatch(sender, 2)));
            assertEquals(1, tracker.pendingCount());
            int[] calls = {0};
            CallbackExceptionTracker.BatchTransport tooSmall = BatchTraceTransport.sender(
                    bytes -> calls[0]++, false, 0, 13, 2);
            assertTrue(TestSupport.capture(() -> tracker.deliverBatch(tooSmall, 2)) instanceof IllegalArgumentException);
            assertEquals(0, calls[0]);
            assertEquals(1, tracker.pendingCount());
        } finally { env.close(); }
    }
    @Test public void batchAndCompressionCanBeExplicitlyPaired() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch-compressed", 4);
        try {
            RuntimeException failure = new IllegalStateException("failure");
            TestSupport.installOrFail(env, TestSupport.spec("batch.compress", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            for (int i = 0; i < 3; i++) {
                assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            List<byte[]> received = new ArrayList<byte[]>();
            BoundedTraceCompression codec = new BoundedTraceCompression(4096, 4116, 1, 16,
                    BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
            JsonLineTraceTransport.RecordWriter batchReceiver =
                    BatchTraceTransport.receiver(received::addAll, 4096, 4);
            byte[][] wire = {null};
            JsonLineTraceTransport.RecordWriter decodedReceiver = codec.decodingWriter(batchReceiver);
            JsonLineTraceTransport.RecordWriter encoder = codec.encodingWriter(frame -> {
                wire[0] = frame.clone();
                decodedReceiver.writeRecord(frame);
            });
            CallbackExceptionTracker.BatchTransport sender =
                    BatchTraceTransport.sender(encoder, false, 0, 4096, 4);
            assertEquals(3, tracker.deliverBatch(sender, 4));
            assertEquals(3, received.size());
            assertEquals(0, tracker.pendingCount());
            assertNotNull(wire[0]);
            byte[] broken = wire[0].clone();
            broken[0] ^= 1;
            assertThrows(java.io.IOException.class, () -> decodedReceiver.writeRecord(broken));
            assertEquals(3, received.size());
        } finally { env.close(); }
    }
    @Test public void incompressibleFullBatchStillFitsConfiguredWireLimit() throws Exception {
        BoundedTraceCompression codec = new BoundedTraceCompression(16384, 16404, 1, 64,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        byte[] plain = new byte[16384];
        new Random(991).nextBytes(plain);
        BoundedTraceCompression.Encoding encoded = codec.encode(plain);
        assertFalse(encoded.compressed());
        assertEquals(16404, encoded.frameBytes());
        assertEquals(-20, encoded.netSavingBytes());
        assertArrayEquals(plain, codec.decode(encoded.bytes()));
    }

    @Test public void rawFallbackWithByteLimitedWriterDeliversOnlyFittingPrefix() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch-bounds", 4);
        try {
            RuntimeException failure = new IllegalStateException("failure");
            TestSupport.installOrFail(env, TestSupport.spec("batch.bounds", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            for (int i = 0; i < 3; i++) {
                assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            int plainLimit = 512;
            int wireLimit = plainLimit + BoundedTraceCompression.HEADER_BYTES;
            BoundedTraceCompression codec = new BoundedTraceCompression(plainLimit, wireLimit, 10000, 64,
                    BoundedTraceCompression.Algorithm.ZLIB);
            List<byte[]> received = new ArrayList<byte[]>();
            JsonLineTraceTransport.RecordWriter decoded = codec.decodingWriter(
                    BatchTraceTransport.receiver(received::addAll, plainLimit, 4));
            int[] wireCount = {0};
            JsonLineTraceTransport.RecordWriter limited = frame -> {
                assertTrue(frame.length <= wireLimit);
                assertEquals(0, frame[5]); // RAW method
                wireCount[0]++;
                decoded.writeRecord(frame);
            };
            CallbackExceptionTracker.BatchTransport sender = BatchTraceTransport.sender(
                    codec.encodingWriter(limited), false, 0, plainLimit, 4);
            int accepted = tracker.deliverBatch(sender, 4);
            assertTrue(accepted > 0 && accepted < 3);
            assertEquals(accepted, received.size());
            assertEquals(3 - accepted, tracker.pendingCount());
            assertEquals(1, wireCount[0]);
        } finally { env.close(); }
    }
}
