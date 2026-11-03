package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.BoundedTraceCompression;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BatchTraceTransportTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    private static final class RecordingFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final StringBuilder trace;
        private final int index;
        RecordingFailure(StringBuilder trace, int index) { this.trace = trace; this.index = index; }
        @Override public String getMessage() { trace.append("message").append(index).append(';'); return "same message"; }
    }

    private static final class TracedEvents extends AbstractList<CallbackExceptionTracker.Event> {
        private final List<CallbackExceptionTracker.Event> events;
        private final StringBuilder trace;
        private final RuntimeException failOnSecond;
        TracedEvents(List<CallbackExceptionTracker.Event> events, StringBuilder trace, RuntimeException failOnSecond) {
            this.events = events; this.trace = trace; this.failOnSecond = failOnSecond;
        }
        @Override public int size() { return events.size(); }
        @Override public CallbackExceptionTracker.Event get(int index) { return events.get(index); }
        @Override public Iterator<CallbackExceptionTracker.Event> iterator() {
            trace.append("iterator;");
            return new Iterator<CallbackExceptionTracker.Event>() {
                int index;
                @Override public boolean hasNext() { trace.append("has").append(index).append(';'); return index < events.size(); }
                @Override public CallbackExceptionTracker.Event next() {
                    trace.append("next").append(index).append(';');
                    if (index == 1 && failOnSecond != null) throw failOnSecond;
                    return events.get(index++);
                }
            };
        }
    }

    @Test public void senderPreservesIterationEncodingAndIndependentFrameOwnership() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch-order", 3);
        StringBuilder trace = new StringBuilder();
        RecordingFailure[] failures = { new RecordingFailure(trace, 0), new RecordingFailure(trace, 1) };
        int[] next = {0};
        try {
            TestSupport.installOrFail(env, TestSupport.spec("batch.order", ADD)
                    .before(tracker.before(invocation -> { throw failures[next[0]++]; })).build());
            assertSame(failures[0], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertSame(failures[1], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(2, tracker.deliver(events::add, 2));
            byte[] record = JsonLineTraceTransport.encode(events.get(0), true, 0);
            byte[] expected = ByteBuffer.allocate(12 + record.length).putInt(0x4d484231).putInt(1)
                    .putInt(record.length).put(record).array();
            trace.setLength(0);
            List<byte[]> frames = new ArrayList<byte[]>();
            CallbackExceptionTracker.BatchTransport limited = BatchTraceTransport.sender(bytes -> {
                trace.append("writer;"); frames.add(bytes);
            }, true, 0, 8 + 4 + record.length + 5, 2);
            assertEquals(1, limited.sendBatch(new TracedEvents(events, trace, null)));
            assertEquals("iterator;has0;next0;message0;has1;next1;message1;writer;", trace.toString());
            assertArrayEquals(expected, frames.get(0));
            trace.setLength(0);
            assertEquals(1, limited.sendBatch(new TracedEvents(events, trace, null)));
            assertNotSame(frames.get(0), frames.get(1));
            assertArrayEquals(expected, frames.get(1));
            frames.get(0)[0] = 0;
            assertArrayEquals(expected, frames.get(1));

            trace.setLength(0);
            RuntimeException iteratorFailure = new RuntimeException("next failed");
            CallbackExceptionTracker.BatchTransport countLimited = BatchTraceTransport.sender(bytes -> {
                trace.append("writer;");
            }, true, 0, 4096, 1);
            assertSame(iteratorFailure, TestSupport.capture(() -> countLimited.sendBatch(
                    new TracedEvents(events, trace, iteratorFailure))));
            assertEquals("iterator;has0;next0;message0;has1;next1;", trace.toString());

            trace.setLength(0);
            Exception rejected = new Exception("writer failure after side effect");
            CallbackExceptionTracker.BatchTransport rejecting = BatchTraceTransport.sender(bytes -> {
                trace.append("writer;"); throw rejected;
            }, true, 0, 4096, 2);
            assertSame(rejected, TestSupport.capture(() -> rejecting.sendBatch(new TracedEvents(events, trace, null))));
            assertEquals("iterator;has0;next0;message0;has1;next1;message1;has2;writer;", trace.toString());
        } finally { env.close(); }
    }

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

    @Test public void pairedBatchSendersMatchCompositionAndPreservePrefixAndOrder() throws Throwable {
        StringBuilder trace = new StringBuilder();
        List<CallbackExceptionTracker.Event> events = captureEvents(
                new RecordingFailure(trace, 0), new RecordingFailure(trace, 1));
        for (boolean useSession : new boolean[] {false, true}) {
            for (int mode = 0; mode < 3; mode++) {
                BoundedTraceCompression codec = new BoundedTraceCompression(8192, 8212,
                        mode == 0 ? 100000 : 1, 16, mode == 2
                        ? BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1 : BoundedTraceCompression.Algorithm.ZLIB);
                try (BoundedTraceCompression.Session session = useSession ? codec.newSession() : null) {
                    List<byte[]> baseline = new ArrayList<byte[]>();
                    List<byte[]> paired = new ArrayList<byte[]>();
                    JsonLineTraceTransport.RecordWriter oldWire = bytes -> { trace.append("writer;"); baseline.add(bytes); };
                    JsonLineTraceTransport.RecordWriter newWire = bytes -> { trace.append("writer;"); paired.add(bytes); };
                    CallbackExceptionTracker.BatchTransport existing = BatchTraceTransport.sender(
                            useSession ? session.encodingWriter(oldWire) : codec.encodingWriter(oldWire), true, 0, 8192, 4);
                    CallbackExceptionTracker.BatchTransport combined = useSession
                            ? session.batchSender(newWire, true, 0, 8192, 4)
                            : codec.batchSender(newWire, true, 0, 8192, 4);
                    trace.setLength(0);
                    assertEquals(2, existing.sendBatch(new TracedEvents(events, trace, null)));
                    String expectedOrder = trace.toString();
                    trace.setLength(0);
                    assertEquals(2, combined.sendBatch(new TracedEvents(events, trace, null)));
                    assertEquals(expectedOrder, trace.toString());
                    assertEquals("iterator;has0;next0;message0;has1;next1;message1;has2;writer;", expectedOrder);
                    assertArrayEquals(baseline.get(0), paired.get(0));
                    assertNotSame(baseline.get(0), paired.get(0));
                    assertEquals(mode, paired.get(0)[5]);

                    int recordSize = JsonLineTraceTransport.encode(events.get(0), true, 0).length;
                    int prefixLimit = 8 + 4 + recordSize + 5;
                    List<byte[]> oldPrefix = new ArrayList<byte[]>(), newPrefix = new ArrayList<byte[]>();
                    CallbackExceptionTracker.BatchTransport existingPrefix = BatchTraceTransport.sender(
                            useSession ? session.encodingWriter(oldPrefix::add) : codec.encodingWriter(oldPrefix::add),
                            true, 0, prefixLimit, 4);
                    CallbackExceptionTracker.BatchTransport combinedPrefix = useSession
                            ? session.batchSender(newPrefix::add, true, 0, prefixLimit, 4)
                            : codec.batchSender(newPrefix::add, true, 0, prefixLimit, 4);
                    assertEquals(1, existingPrefix.sendBatch(events));
                    assertEquals(1, combinedPrefix.sendBatch(events));
                    assertArrayEquals(oldPrefix.get(0), newPrefix.get(0));

                    if (useSession) {
                        int[] reentered = {0};
                        assertEquals(2, session.batchSender(bytes -> {
                            session.encode(new byte[] {1});
                            reentered[0]++;
                        }, true, 0, 8192, 4).sendBatch(events));
                        assertEquals(1, reentered[0]);
                    }
                }
            }
        }
    }

    @Test public void pairedSessionSenderKeepsRejectedBatchAndRecoversAfterCodecFailure() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("owned-failure", 2);
        RuntimeException failure = new IllegalStateException("failure");
        try {
            TestSupport.installOrFail(env, TestSupport.spec("batch.owned.failure", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            BoundedTraceCompression codec = new BoundedTraceCompression(1, 128, 1, 1,
                    BoundedTraceCompression.Algorithm.ZLIB);
            int[] writes = {0};
            BoundedTraceCompression.Session session = codec.newSession();
            CallbackExceptionTracker.BatchTransport sender;
            try {
                sender = session.batchSender(bytes -> writes[0]++, false, 0, 4096, 2);
                List<CallbackExceptionTracker.Event> foreignEvents = captureEvents(
                        new RecordingFailure(new StringBuilder(), 0));
                AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
                Thread other = new Thread(() -> wrongThread.set(TestSupport.capture(() -> sender.sendBatch(foreignEvents))));
                other.setDaemon(true);
                other.start();
                other.join(5000);
                assertFalse(other.isAlive());
                assertTrue(wrongThread.get() instanceof IllegalStateException);
                assertTrue(TestSupport.capture(() -> tracker.tryDeliverBatch(sender, 2)) instanceof java.io.IOException);
                assertEquals(1, tracker.pendingCount());
                assertEquals(0, writes[0]);
                assertEquals(1, session.encode(new byte[] {7}).plainBytes());
            } finally { session.close(); }
            assertTrue(TestSupport.capture(() -> tracker.tryDeliverBatch(sender, 2)) instanceof IllegalStateException);
            assertEquals(1, tracker.pendingCount());
            assertEquals(0, writes[0]);

            BoundedTraceCompression retryCodec = new BoundedTraceCompression(4096, 4116, 1, 16,
                    BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
            Exception rejected = new Exception("receiver rejected");
            boolean[] reject = {true};
            CallbackExceptionTracker.BatchTransport retry = retryCodec.batchSender(bytes -> {
                writes[0]++;
                if (reject[0]) throw rejected;
            }, false, 0, 4096, 2);
            assertSame(rejected, TestSupport.capture(() -> tracker.tryDeliverBatch(retry, 2)));
            assertEquals(1, tracker.pendingCount());
            reject[0] = false;
            assertEquals(1, tracker.tryDeliverBatch(retry, 2).acceptedCount());
            assertEquals(0, tracker.pendingCount());
            assertEquals(2, writes[0]);
        } finally { env.close(); }
    }

    @Test public void singleWorkerCanObserveWireEntryAcrossExplicitBatchAttempts() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("wire-observation", 2);
        RuntimeException callbackFailure = new IllegalStateException("record this failure");
        AtomicBoolean enteredWire = new AtomicBoolean();
        AtomicBoolean rejectWire = new AtomicBoolean(true);
        AtomicInteger wireCalls = new AtomicInteger();
        java.io.IOException wireFailure = new java.io.IOException("wire performed a side effect before failing");
        JsonLineTraceTransport.RecordWriter observedWire = bytes -> {
            enteredWire.set(true);
            wireCalls.incrementAndGet();
            if (rejectWire.get()) throw wireFailure;
        };
        BoundedTraceCompression codec = new BoundedTraceCompression(4096, 4116, 1, 16,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        CallbackExceptionTracker.BatchTransport tooSmall = codec.batchSender(observedWire, false, 0, 13, 2);
        CallbackExceptionTracker.BatchTransport sender = codec.batchSender(observedWire, false, 0, 4096, 2);
        try {
            TestSupport.installOrFail(env, TestSupport.spec("batch.wire.observation", ADD)
                    .before(tracker.before(invocation -> { throw callbackFailure; })).build());
            assertSame(callbackFailure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            CallbackExceptionTracker.Event head = tracker.peekNext();
            assertNotNull(head);

            // The fixed single-record frame limit fails before the supplied wire writer is entered.
            enteredWire.set(false);
            assertTrue(TestSupport.capture(() -> tracker.tryDeliverBatch(tooSmall, 2)) instanceof IllegalArgumentException);
            assertFalse(enteredWire.get());
            assertEquals(0, wireCalls.get());
            assertEquals(1, tracker.pendingCount());
            assertSame(head, tracker.peekNext());

            // A writer may produce a side effect and then fail; the exact original error escapes.
            enteredWire.set(false);
            assertSame(wireFailure, TestSupport.capture(() -> tracker.tryDeliverBatch(sender, 2)));
            assertTrue(enteredWire.get());
            assertEquals(1, wireCalls.get());
            assertEquals(1, tracker.pendingCount());
            assertSame(head, tracker.peekNext());

            // This explicit third attempt is test-controlled, not a guarantee that retry is safe.
            rejectWire.set(false);
            enteredWire.set(false);
            CallbackExceptionTracker.BatchAttempt accepted = tracker.tryDeliverBatch(sender, 2);
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, accepted.status());
            assertEquals(1, accepted.acceptedCount());
            assertTrue(enteredWire.get());
            assertEquals(2, wireCalls.get());
            assertEquals(0, tracker.pendingCount());
            assertNull(tracker.peekNext());
        } finally { env.close(); }
    }

    @Test public void pairedSenderKeepsCompressionToFitDecisionAndWireBytes() throws Throwable {
        StringBuilder trace = new StringBuilder();
        List<CallbackExceptionTracker.Event> events = captureEvents(new RecordingFailure(trace, 0));
        byte[] record = JsonLineTraceTransport.encode(events.get(0), true, 0);
        byte[] batch = ByteBuffer.allocate(12 + record.length).putInt(0x4d484231).putInt(1)
                .putInt(record.length).put(record).array();
        BoundedTraceCompression codec = new BoundedTraceCompression(batch.length, batch.length + 19,
                100000, 100000, BoundedTraceCompression.Algorithm.ZLIB);
        BoundedTraceCompression.Encoding expected = codec.encode(batch);
        assertEquals(BoundedTraceCompression.Decision.COMPRESSED_TO_FIT, expected.reason());
        List<byte[]> wire = new ArrayList<byte[]>();
        assertEquals(1, codec.batchSender(wire::add, true, 0, batch.length, 1).sendBatch(events));
        assertArrayEquals(expected.bytes(), wire.get(0));
        assertArrayEquals(batch, codec.decode(wire.get(0)));
    }

    private static List<CallbackExceptionTracker.Event> captureEvents(RecordingFailure... failures) throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("owned-sender", failures.length);
        int[] next = {0};
        try {
            TestSupport.installOrFail(env, TestSupport.spec("batch.owned.events", ADD)
                    .before(tracker.before(invocation -> { throw failures[next[0]++]; })).build());
            for (RecordingFailure failure : failures) {
                assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(failures.length, tracker.deliver(events::add, failures.length));
            return events;
        } finally { env.close(); }
    }

    @Test public void compressedReceiverMatchesExistingCompositionForRawAndCompressedFrames() throws Exception {
        byte[] plain = exampleBatch();
        for (int mode = 0; mode < 3; mode++) {
            BoundedTraceCompression.Algorithm algorithm = mode == 2
                    ? BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1 : BoundedTraceCompression.Algorithm.ZLIB;
            BoundedTraceCompression codec = new BoundedTraceCompression(1024, 1044,
                    mode == 0 ? 4096 : 1, 16, algorithm);
            BoundedTraceCompression.Encoding encoding = codec.encode(plain);
            assertEquals(mode != 0, encoding.compressed());
            byte[] wire = encoding.bytes();
            byte[] original = wire.clone();
            List<List<byte[]>> baseline = new ArrayList<List<byte[]>>();
            List<List<byte[]>> paired = new ArrayList<List<byte[]>>();
            JsonLineTraceTransport.RecordWriter existing = codec.decodingWriter(
                    BatchTraceTransport.receiver(baseline::add, 1024, 4));
            JsonLineTraceTransport.RecordWriter combined =
                    BatchTraceTransport.compressedReceiver(codec, paired::add, 1024, 4);
            existing.writeRecord(wire);
            combined.writeRecord(wire);
            assertArrayEquals(original, wire);
            assertEquals(1, baseline.size());
            assertEquals(1, paired.size());
            assertSameRecords(baseline.get(0), paired.get(0));
            assertThrows(UnsupportedOperationException.class, () -> paired.get(0).clear());
            paired.get(0).get(0)[0] = 0;
            combined.writeRecord(wire);
            assertSameRecords(baseline.get(0), paired.get(1));
            assertArrayEquals(original, wire);
        }
    }

    @Test public void compressedSessionReceiverKeepsValidationAndSessionLifetime() throws Exception {
        BoundedTraceCompression codec = new BoundedTraceCompression(1024, 1044, 1, 16,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        byte[] valid = exampleBatch();
        byte[] wire = codec.encode(valid).bytes();
        byte[] badWire = wire.clone();
        badWire[0] ^= 1;
        byte[] invalidBatch = valid.clone();
        invalidBatch[0] = 0;
        byte[] badBatchWire = codec.encode(invalidBatch).bytes();
        int[] calls = {0};
        JsonLineTraceTransport.RecordWriter combined;
        try (BoundedTraceCompression.Session session = codec.newSession()) {
            JsonLineTraceTransport.RecordWriter existing = session.decodingWriter(
                    BatchTraceTransport.receiver(records -> calls[0]++, 1024, 4));
            combined = BatchTraceTransport.compressedSessionReceiver(session, records -> calls[0]++, 1024, 4);
            for (JsonLineTraceTransport.RecordWriter receiver : new JsonLineTraceTransport.RecordWriter[] {existing, combined}) {
                int before = calls[0];
                assertThrows(java.io.IOException.class, () -> receiver.writeRecord(badWire));
                assertThrows(IllegalArgumentException.class, () -> receiver.writeRecord(badBatchWire));
                assertEquals(before, calls[0]);
                receiver.writeRecord(wire); // A malformed input must not poison a healthy session.
            }
            assertEquals(2, calls[0]);
            AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
            Thread other = new Thread(() -> wrongThread.set(TestSupport.capture(() -> combined.writeRecord(wire))));
            other.setDaemon(true);
            other.start();
            other.join(5000);
            assertFalse(other.isAlive());
            assertTrue(wrongThread.get() instanceof IllegalStateException);
            assertEquals(2, calls[0]);
        }
        assertThrows(IllegalStateException.class, () -> combined.writeRecord(wire));
        assertEquals(2, calls[0]);
    }

    @Test public void compressedReceiverPreservesBatchFailureAndRetryAcknowledgement() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("paired-retry", 2);
        try {
            RuntimeException callbackFailure = new IllegalStateException("hook failure");
            TestSupport.installOrFail(env, TestSupport.spec("batch.paired.retry", ADD)
                    .before(tracker.before(invocation -> { throw callbackFailure; })).build());
            assertSame(callbackFailure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            BoundedTraceCompression codec = new BoundedTraceCompression(4096, 4116, 1, 16,
                    BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
            Exception writerFailure = new Exception("receiver failure");
            boolean[] reject = {true};
            int[] calls = {0};
            JsonLineTraceTransport.RecordWriter receiver = BatchTraceTransport.compressedReceiver(codec, records -> {
                calls[0]++;
                if (reject[0]) throw writerFailure;
                assertEquals(1, records.size());
            }, 4096, 2);
            CallbackExceptionTracker.BatchTransport sender = BatchTraceTransport.sender(
                    codec.encodingWriter(receiver), false, 0, 4096, 2);
            assertSame(writerFailure, TestSupport.capture(() -> tracker.deliverBatch(sender, 2)));
            assertEquals(1, tracker.pendingCount());
            reject[0] = false;
            assertEquals(1, tracker.deliverBatch(sender, 2));
            assertEquals(0, tracker.pendingCount());
            assertEquals(2, calls[0]);
        } finally { env.close(); }
    }

    private static byte[] exampleBatch() {
        byte[] first = new byte[128];
        byte[] second = new byte[128];
        Arrays.fill(first, (byte) 'a');
        Arrays.fill(second, (byte) 'b');
        return ByteBuffer.allocate(8 + 4 + first.length + 4 + second.length)
                .putInt(0x4d484231).putInt(2).putInt(first.length).put(first)
                .putInt(second.length).put(second).array();
    }

    private static void assertSameRecords(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i));
            assertNotSame(expected.get(i), actual.get(i));
        }
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
