package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.BoundedTraceCompression;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RawSafeBatchLimitTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void computedLimitCarriesRawBatchAndRetainsOversizedHead() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("raw-safe", 2);
        RuntimeException[] failures = {new RuntimeException(noise(96)), new RuntimeException(noise(4096))};
        int[] next = {0};
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("raw.safe", ADD)
                    .before(tracker.before(invocation -> { throw failures[next[0]++]; })).build());
            assertSame(failures[0], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertSame(failures[1], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));

            byte[] firstRecord = JsonLineTraceTransport.encode(tracker.peekNext(), true, 0);
            int exactBatchBytes = 8 + 4 + firstRecord.length;
            int wireLimit = BoundedTraceCompression.HEADER_BYTES + exactBatchBytes;
            BoundedTraceCompression codec = new BoundedTraceCompression(65536, 65556,
                    Integer.MAX_VALUE, 1, BoundedTraceCompression.Algorithm.ZLIB);
            int batchLimit = codec.rawSafeBatchLimit(wireLimit);
            assertEquals(exactBatchBytes, batchLimit);

            List<byte[]> received = new ArrayList<byte[]>();
            JsonLineTraceTransport.RecordWriter receiver =
                    BatchTraceTransport.compressedReceiver(codec, received::addAll, batchLimit, 2);
            List<byte[]> wire = new ArrayList<byte[]>();
            CallbackExceptionTracker.BatchTransport sender = codec.batchSender(frame -> {
                wire.add(frame.clone());
                receiver.writeRecord(frame);
            }, true, 0, batchLimit, 2);
            CallbackExceptionTracker.BatchAttempt delivered = tracker.tryDeliverBatch(sender, 2);
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, delivered.status());
            assertEquals(1, delivered.acceptedCount());
            assertEquals(1, wire.size());
            assertEquals(wireLimit, wire.get(0).length);
            assertEquals(0, wire.get(0)[5]); // RAW method; threshold intentionally disables compression.
            assertEquals(1, received.size());
            assertArrayEquals(firstRecord, received.get(0));

            CallbackExceptionTracker.Event oversized = tracker.peekNext();
            assertNotNull(oversized);
            assertTrue(JsonLineTraceTransport.encode(oversized, true, 0).length + 12 > batchLimit);
            assertTrue(TestSupport.capture(() -> tracker.tryDeliverBatch(sender, 2)) instanceof IllegalArgumentException);
            assertSame(oversized, tracker.peekNext());
            assertEquals(1, tracker.pendingCount());
            assertEquals(1, wire.size());
            assertEquals(1, received.size());
        } finally {
            environment.close();
        }
    }

    private static String noise(int length) {
        Random random = new Random(0x5eedL + length);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.toString();
    }
}
