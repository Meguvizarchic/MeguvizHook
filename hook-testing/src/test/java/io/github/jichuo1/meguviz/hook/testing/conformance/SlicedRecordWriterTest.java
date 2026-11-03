package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.BoundedTraceCompression;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Opt-in sliced record writes: a {@link JsonLineTraceTransport.SlicedRecordWriter} receives the
 * sender's buffer as a slice instead of an exact-length copy. Everything observable apart from
 * that hand-off (bytes, outcomes, getter calls, traversal, failures) must equal the copying path.
 */
public final class SlicedRecordWriterTest {
    private static final long SEED = 0x511ced_2025L;

    /** Captures each slice as a copy and records the hand-off shape. */
    private static final class SliceSink implements JsonLineTraceTransport.SlicedRecordWriter {
        final List<byte[]> records = new ArrayList<byte[]>();
        int sliceCalls;
        int wholeCalls;
        boolean sliceShorterThanBuffer;
        Exception failure;

        @Override public void writeRecord(byte[] buffer, int offset, int length) throws Exception {
            sliceCalls++;
            if (length < buffer.length) sliceShorterThanBuffer = true;
            records.add(Arrays.copyOfRange(buffer, offset, offset + length));
            if (failure != null) throw failure;
        }

        @Override public void writeRecord(byte[] record) throws Exception {
            wholeCalls++;
            JsonLineTraceTransport.SlicedRecordWriter.super.writeRecord(record);
        }
    }

    @Test public void jsonLineSendPassesTheEncodedSliceOnly() throws Exception {
        Random random = new Random(SEED);
        boolean sawSlack = false;
        for (int i = 0; i < 3000; i++) {
            CallbackExceptionTracker.Event event = TraceWireDifferentialTest.randomEvent(random);
            boolean includeMessage = random.nextBoolean();
            int maxFrames = random.nextInt(33);
            List<byte[]> copies = new ArrayList<byte[]>();
            new JsonLineTraceTransport(copies::add, includeMessage, maxFrames).send(event);
            String copyCalls = TraceWireDifferentialTest.calls(Arrays.asList(event));
            SliceSink sink = new SliceSink();
            new JsonLineTraceTransport(sink, includeMessage, maxFrames).send(event);
            assertEquals("getter calls, case " + i, copyCalls, TraceWireDifferentialTest.calls(Arrays.asList(event)));
            assertEquals(1, sink.sliceCalls);
            assertEquals(0, sink.wholeCalls);
            assertArrayEquals("case " + i, copies.get(0), sink.records.get(0));
            sawSlack |= sink.sliceShorterThanBuffer;
        }
        assertTrue("the copy was really skipped for some records", sawSlack);
        assertTrue(TestSupport.capture(() -> new JsonLineTraceTransport(new SliceSink(), false, 0).send(null))
                instanceof NullPointerException);
    }

    @Test public void batchSenderSliceMatchesTheCopyingSenderExactly() throws Exception {
        Random random = new Random(SEED + 1);
        int partial = 0;
        int rejected = 0;
        for (int i = 0; i < 1200; i++) {
            int count = 1 + random.nextInt(random.nextInt(4) == 0 ? 120 : 12);
            List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>(count);
            for (int j = 0; j < count; j++) events.add(TraceWireDifferentialTest.randomEvent(random));
            boolean includeMessage = random.nextBoolean();
            int maxFrames = random.nextInt(33);
            int maxRecords = random.nextInt(3) == 0 ? 1 + random.nextInt(4) : 1 + random.nextInt(1024);
            int maxFrameBytes = TraceWireDifferentialTest.frameLimit(random, events, includeMessage, maxFrames);
            TraceWireDifferentialTest.calls(events);

            TraceWireDifferentialTest.TracingList copyList = new TraceWireDifferentialTest.TracingList(events);
            List<byte[]> copies = new ArrayList<byte[]>();
            String copyOutcome;
            try {
                copyOutcome = "accepted " + BatchTraceTransport.sender(copies::add, includeMessage, maxFrames,
                        maxFrameBytes, maxRecords).sendBatch(copyList);
            } catch (RuntimeException failure) {
                copyOutcome = TraceWireDifferentialTest.outcome(failure);
            }
            String copyCalls = TraceWireDifferentialTest.calls(events);

            TraceWireDifferentialTest.TracingList sliceList = new TraceWireDifferentialTest.TracingList(events);
            SliceSink sink = new SliceSink();
            String sliceOutcome;
            try {
                sliceOutcome = "accepted " + BatchTraceTransport.sender(sink, includeMessage, maxFrames,
                        maxFrameBytes, maxRecords).sendBatch(sliceList);
            } catch (RuntimeException failure) {
                sliceOutcome = TraceWireDifferentialTest.outcome(failure);
            }
            String message = "case " + i + " limit=" + maxFrameBytes;
            assertEquals(message, copyOutcome, sliceOutcome);
            assertEquals(message, copyCalls, TraceWireDifferentialTest.calls(events));
            assertEquals(message, copyList.trace.toString(), sliceList.trace.toString());
            assertEquals(message, copies.size(), sink.records.size());
            assertEquals(0, sink.wholeCalls);
            if (copies.isEmpty()) {
                rejected++;
            } else {
                assertArrayEquals(message, copies.get(0), sink.records.get(0));
                if (!sliceOutcome.equals("accepted " + count)) partial++;
            }
        }
        assertTrue("partial " + partial, partial > 100);
        assertTrue("rejected " + rejected, rejected > 30);
    }

    @Test public void sliceWriterFailureKeepsTheQueueAndPropagatesUnchanged() throws Exception {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("slice-failure", 4);
        Random random = new Random(SEED + 2);
        TraceWireDifferentialTest.ScriptedFailure failure = new TraceWireDifferentialTest.ScriptedFailure(
                "boom", 0, TraceWireDifferentialTest.randomFrames(random), 0);
        io.github.jichuo1.meguviz.hook.api.BeforeCallback wrapped = tracker.before(ignored -> { throw failure; });
        for (int i = 0; i < 3; i++) {
            assertSame(failure, TestSupport.capture(() -> wrapped.before(new TraceWireDifferentialTest.StubInvocation(
                    io.github.jichuo1.meguviz.hook.api.HookId.of("slice.hook"), 1L))));
        }
        SliceSink sink = new SliceSink();
        Exception broken = new java.io.IOException("wire failed");
        sink.failure = broken;
        CallbackExceptionTracker.BatchTransport sender = BatchTraceTransport.sender(sink, true, 4, 65536, 32);
        assertSame(broken, TestSupport.capture(() -> tracker.tryDeliverBatch(sender, 32)));
        assertEquals(3, tracker.pendingCount());
        assertSame(broken, TestSupport.capture(() -> tracker.deliver(new JsonLineTraceTransport(sink, true, 4), 1)));
        assertEquals(3, tracker.pendingCount());
        sink.failure = null;
        assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, tracker.tryDeliverBatch(sender, 32).status());
        assertEquals(0, tracker.pendingCount());
    }

    @Test public void compressedBatchSendersEncodeTheSliceLikeTheTrimmedFrame() throws Exception {
        Random random = new Random(SEED + 3);
        // A low threshold compresses most batches; a high one keeps RAW frames; ZLIB and the
        // schema dictionary are both covered.
        BoundedTraceCompression[] codecs = {
            new BoundedTraceCompression(65536, 65556, 64, 1, BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1),
            new BoundedTraceCompression(65536, 65556, 65536, 16, BoundedTraceCompression.Algorithm.ZLIB),
            new BoundedTraceCompression(65536, 65556, 512, 16, BoundedTraceCompression.Algorithm.ZLIB),
        };
        for (BoundedTraceCompression codec : codecs) {
            try (BoundedTraceCompression.Session session = codec.newSession();
                 BoundedTraceCompression.SerializedSession serialized = codec.newSerializedSession()) {
                for (int i = 0; i < 150; i++) {
                    List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>();
                    int count = 1 + random.nextInt(20);
                    for (int j = 0; j < count; j++) events.add(TraceWireDifferentialTest.randomEvent(random));
                    boolean includeMessage = random.nextBoolean();
                    int maxFrames = random.nextInt(9);
                    List<byte[]> plain = new ArrayList<byte[]>();
                    int expectedCount = BatchTraceTransport.sender(plain::add, includeMessage, maxFrames, 65536, 1024)
                            .sendBatch(events);
                    BoundedTraceCompression.Encoding expected = codec.encode(plain.get(0));
                    List<byte[]> stateless = new ArrayList<byte[]>();
                    List<byte[]> reused = new ArrayList<byte[]>();
                    List<byte[]> shared = new ArrayList<byte[]>();
                    assertEquals(expectedCount, codec.batchSender(stateless::add, includeMessage, maxFrames,
                            65536, 1024).sendBatch(events));
                    assertEquals(expectedCount, session.batchSender(reused::add, includeMessage, maxFrames,
                            65536, 1024).sendBatch(events));
                    assertEquals(expectedCount, serialized.batchSender(shared::add, includeMessage, maxFrames,
                            65536, 1024).sendBatch(events));
                    assertArrayEquals(expected.bytes(), stateless.get(0));
                    assertArrayEquals(expected.bytes(), reused.get(0));
                    assertArrayEquals(expected.bytes(), shared.get(0));
                    assertArrayEquals(plain.get(0), codec.decode(stateless.get(0)));
                }
            }
        }
    }

    @Test public void defaultWholeArrayWriteIsTheFullSlice() throws Exception {
        List<String> calls = new ArrayList<String>();
        JsonLineTraceTransport.SlicedRecordWriter writer = (buffer, offset, length) ->
                calls.add(buffer.length + ":" + offset + ":" + length);
        writer.writeRecord(new byte[7]);
        assertEquals(Arrays.asList("7:0:7"), calls);
        assertTrue(TestSupport.capture(() -> writer.writeRecord(null)) instanceof NullPointerException);
    }
}
