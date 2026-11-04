package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.BoundedTraceCompression;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import java.lang.reflect.Executable;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Differential wire-format tests: the production JSONL encoder, batch sender and batch receiver
 * must produce exactly the bytes, outcomes, getter calls and iteration pattern of the round-0
 * implementations kept verbatim in {@link LegacyTraceCodec}, over many randomized inputs.
 */
public final class TraceWireDifferentialTest {
    private static final long SEED = 0x5eed_2024_0b0dL;

    /** Throwable with controllable, call-counting message and stack accessors. */
    static class ScriptedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String message;
        final StackTraceElement[] frames;
        final int messageMode;  // 0 value, 1 throws
        final int framesMode;   // 0 value (may be null), 1 throws
        int messageCalls;
        int frameCalls;
        final StringBuilder order = new StringBuilder();

        ScriptedFailure(String message, int messageMode, StackTraceElement[] frames, int framesMode) {
            super(null, null, false, false);
            this.message = message;
            this.messageMode = messageMode;
            this.frames = frames;
            this.framesMode = framesMode;
        }

        @Override public String getMessage() {
            messageCalls++;
            order.append('m');
            if (messageMode == 1) throw new IllegalStateException("message getter failed");
            return message;
        }

        @Override public StackTraceElement[] getStackTrace() {
            frameCalls++;
            order.append('s');
            if (framesMode == 1) throw new IllegalStateException("stack getter failed");
            return frames == null ? null : frames.clone();
        }
    }

    // A non-ASCII type name exercises escaping in the "type" field.
    static final class Ünïcödé异常Failure extends ScriptedFailure {
        private static final long serialVersionUID = 1L;
        Ünïcödé异常Failure(String message, int messageMode, StackTraceElement[] frames, int framesMode) {
            super(message, messageMode, frames, framesMode);
        }
    }

    static final class StubInvocation implements HookInvocation {
        private final HookId id;
        private final long generation;
        StubInvocation(HookId id, long generation) { this.id = id; this.generation = generation; }
        @Override public HookId id() { return id; }
        @Override public long generation() { return generation; }
        @Override public Executable target() { throw new UnsupportedOperationException(); }
        @Override public Object thisObject() { throw new UnsupportedOperationException(); }
        @Override public ParameterAccess access() { return ParameterAccess.NO_ARGS; }
        @Override public Thread ownerThread() { return Thread.currentThread(); }
        @Override public void setEarlyResult(Object value) { throw new UnsupportedOperationException(); }
        @Override public void setEarlyThrowable(Throwable throwable) { throw new UnsupportedOperationException(); }
        @Override public boolean hasEarlyOutcome() { return false; }
    }

    static CallbackExceptionTracker.Event event(String source, HookId id, long generation, RuntimeException failure) {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker(source, 1);
        BeforeCallback wrapped = tracker.before(invocation -> { throw failure; });
        try {
            wrapped.before(new StubInvocation(id, generation));
            fail("callback did not throw");
        } catch (Throwable thrown) {
            assertSame(failure, thrown);
        }
        CallbackExceptionTracker.Event event = tracker.discardNext();
        assertNotNull(event);
        return event;
    }

    // ---- random inputs ----------------------------------------------------------------------

    private static final int[] LENGTHS = {0, 1, 2, 3, 7, 31, 64, 126, 127, 128, 129, 130, 200, 255,
            510, 511, 512, 513, 514, 600, 1100};

    static String randomText(Random random, int length) {
        StringBuilder text = new StringBuilder(length);
        int profile = random.nextInt(6);
        while (text.length() < length) {
            int kind = profile == 0 ? 0 : random.nextInt(profile == 1 ? 3 : 10);
            switch (kind) {
                case 0: case 1: text.append((char) (0x20 + random.nextInt(0x5f))); break;
                case 2: text.append(random.nextBoolean() ? '"' : '\\'); break;
                case 3: text.append((char) random.nextInt(0x20)); break;
                case 4: text.append((char) 0x7f); break;
                case 5: text.append((char) (0x80 + random.nextInt(0x780))); break;
                case 6: {
                    char c = (char) (0x800 + random.nextInt(0xf800));
                    if (Character.isSurrogate(c)) c = '\u4e2d';
                    text.append(c);
                    break;
                }
                case 7: text.appendCodePoint(0x10000 + random.nextInt(0xfffff)); break;
                case 8: text.append((char) (0xd800 + random.nextInt(0x400))); break; // lone high
                default: text.append((char) (0xdc00 + random.nextInt(0x400))); break; // lone low
            }
        }
        // Sometimes straddle a truncation boundary with a valid surrogate pair.
        if (length > 2 && random.nextInt(4) == 0) {
            int at = Math.min(length - 2, random.nextBoolean() ? 127 : 511);
            if (at >= 0 && at + 2 <= text.length()) text.replace(at, at + 2, "\ud83d\ude00");
        }
        return text.length() > length ? text.substring(0, length) : text.toString();
    }

    static String randomNullableText(Random random) {
        if (random.nextInt(8) == 0) return null;
        return randomText(random, LENGTHS[random.nextInt(LENGTHS.length)]);
    }

    static HookId randomId(Random random) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-:/";
        int length = 1 + random.nextInt(random.nextBoolean() ? 20 : 128);
        StringBuilder id = new StringBuilder(length);
        for (int i = 0; i < length; i++) id.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return HookId.of(id.toString());
    }

    static long randomGeneration(Random random) {
        switch (random.nextInt(6)) {
            case 0: return Long.MIN_VALUE;
            case 1: return Long.MAX_VALUE;
            case 2: return -random.nextInt(1000);
            case 3: return random.nextInt(10);
            default: return random.nextLong();
        }
    }

    static StackTraceElement[] randomFrames(Random random) {
        if (random.nextInt(10) == 0) return null;
        int count = random.nextInt(5) == 0 ? random.nextInt(3) : random.nextInt(45);
        StackTraceElement[] frames = new StackTraceElement[count];
        for (int i = 0; i < count; i++) {
            if (random.nextInt(25) == 0) continue; // malformed null element
            String className = randomText(random, 1 + random.nextInt(random.nextBoolean() ? 60 : 140));
            String method = randomText(random, 1 + random.nextInt(30));
            String file = random.nextInt(6) == 0 ? null : randomText(random, random.nextInt(random.nextBoolean() ? 25 : 140));
            int line = random.nextInt(5) == 0 ? -1 - random.nextInt(3) : random.nextInt(100000);
            if (random.nextInt(50) == 0) line = random.nextBoolean() ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            frames[i] = new StackTraceElement(className, method, file, line);
        }
        return frames;
    }

    static ScriptedFailure randomFailure(Random random) {
        String message = randomNullableText(random);
        int messageMode = random.nextInt(12) == 0 ? 1 : 0;
        StackTraceElement[] frames = randomFrames(random);
        int framesMode = random.nextInt(12) == 0 ? 1 : 0;
        return random.nextInt(5) == 0
                ? new Ünïcödé异常Failure(message, messageMode, frames, framesMode)
                : new ScriptedFailure(message, messageMode, frames, framesMode);
    }

    static String randomSource(Random random) {
        String source = randomText(random, 1 + random.nextInt(128));
        return source.isEmpty() ? "s" : source;
    }

    static CallbackExceptionTracker.Event randomEvent(Random random) {
        return event(randomSource(random), randomId(random), randomGeneration(random), randomFailure(random));
    }

    // ---- JSONL --------------------------------------------------------------------------------

    @Test public void jsonLineBytesAndGetterCallsMatchLegacyEncoder() {
        Random random = new Random(SEED);
        for (int i = 0; i < 4000; i++) {
            CallbackExceptionTracker.Event event = randomEvent(random);
            ScriptedFailure failure = (ScriptedFailure) event.failure();
            boolean includeMessage = random.nextBoolean();
            int maxFrames = random.nextInt(4) == 0 ? 32 : random.nextInt(33);
            byte[] expected = LegacyTraceCodec.encode(event, includeMessage, maxFrames);
            int legacyMessageCalls = failure.messageCalls;
            int legacyFrameCalls = failure.frameCalls;
            String legacyOrder = failure.order.toString();
            failure.messageCalls = 0;
            failure.frameCalls = 0;
            failure.order.setLength(0);
            byte[] actual = JsonLineTraceTransport.encode(event, includeMessage, maxFrames);
            assertArrayEquals("case " + i, expected, actual);
            assertEquals("message getter calls, case " + i, legacyMessageCalls, failure.messageCalls);
            assertEquals("stack getter calls, case " + i, legacyFrameCalls, failure.frameCalls);
            assertEquals("getter order, case " + i, legacyOrder, failure.order.toString());
            failure.messageCalls = 0;
            failure.frameCalls = 0;
            assertArrayEquals(expected, new JsonLineTraceTransport(bytes -> { }, includeMessage, maxFrames).encode(event));
        }
    }

    @Test public void jsonLineEdgeValuesMatchLegacyEncoder() {
        String[] values = {"", "\"", "\\", "\u0000\u001f\u007f\u0080\u07ff\u0800\uffff", "\ud800", "\udc00",
                "\ud83d\ude00", "\ud83d", repeat('a', 127) + "\ud83d\ude00", repeat('a', 511) + "\ud83d\ude00",
                repeat('é', 600), repeat('中', 600), repeat('\u0001', 600), repeat('"', 600)};
        for (String value : values) {
            StackTraceElement[] frames = {new StackTraceElement(value.isEmpty() ? "C" : value, "m", value, 1),
                    null, new StackTraceElement("C", value.isEmpty() ? "m" : value, null, -2)};
            String source = value.isEmpty() ? "s" : value.substring(0, Math.min(128, value.length()));
            CallbackExceptionTracker.Event event = event(source, HookId.of("edge"),
                    Long.MIN_VALUE, new ScriptedFailure(value, 0, frames, 0));
            for (int maxFrames : new int[] {0, 1, 2, 3, 32}) {
                for (boolean includeMessage : new boolean[] {false, true}) {
                    assertArrayEquals(LegacyTraceCodec.encode(event, includeMessage, maxFrames),
                            JsonLineTraceTransport.encode(event, includeMessage, maxFrames));
                }
            }
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // ---- batch sender --------------------------------------------------------------------------

    /** Records every iteration step so both implementations must traverse identically. */
    static final class TracingList extends AbstractList<CallbackExceptionTracker.Event> {
        private final List<CallbackExceptionTracker.Event> events;
        final StringBuilder trace = new StringBuilder();
        TracingList(List<CallbackExceptionTracker.Event> events) { this.events = events; }
        @Override public int size() { trace.append("size;"); return events.size(); }
        @Override public CallbackExceptionTracker.Event get(int index) { trace.append("get").append(index).append(';'); return events.get(index); }
        @Override public Iterator<CallbackExceptionTracker.Event> iterator() {
            trace.append("iterator;");
            return new Iterator<CallbackExceptionTracker.Event>() {
                int index;
                @Override public boolean hasNext() { trace.append("has").append(index).append(';'); return index < events.size(); }
                @Override public CallbackExceptionTracker.Event next() { trace.append("next").append(index).append(';'); return events.get(index++); }
            };
        }
    }

    static String outcome(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    static String calls(List<CallbackExceptionTracker.Event> events) {
        StringBuilder calls = new StringBuilder();
        for (CallbackExceptionTracker.Event event : events) {
            ScriptedFailure failure = (ScriptedFailure) event.failure();
            calls.append(failure.messageCalls).append('/').append(failure.frameCalls)
                    .append('/').append(failure.order).append(';');
            failure.messageCalls = 0;
            failure.frameCalls = 0;
            failure.order.setLength(0);
        }
        return calls.toString();
    }

    @Test public void batchFramesOutcomesAndTraversalMatchLegacySender() throws Exception {
        Random random = new Random(SEED + 1);
        int fitted = 0;
        int rejected = 0;
        int partial = 0;
        for (int i = 0; i < 1500; i++) {
            int count = 1 + random.nextInt(random.nextInt(4) == 0 ? 120 : 12);
            List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>(count);
            for (int j = 0; j < count; j++) events.add(randomEvent(random));
            boolean includeMessage = random.nextBoolean();
            int maxFrames = random.nextInt(33);
            int maxRecords = random.nextInt(3) == 0 ? 1 + random.nextInt(4) : 1 + random.nextInt(1024);
            int maxFrameBytes = frameLimit(random, events, includeMessage, maxFrames);
            for (CallbackExceptionTracker.Event event : events) calls(Arrays.asList(event));

            TracingList legacyList = new TracingList(events);
            List<byte[]> legacyFrames = new ArrayList<byte[]>();
            String legacyOutcome;
            try {
                legacyOutcome = "accepted " + LegacyTraceCodec.send(legacyList, includeMessage, maxFrames,
                        maxFrameBytes, maxRecords, legacyFrames);
            } catch (RuntimeException failure) {
                legacyOutcome = outcome(failure);
            }
            String legacyCalls = calls(events);

            TracingList currentList = new TracingList(events);
            List<byte[]> currentFrames = new ArrayList<byte[]>();
            CallbackExceptionTracker.BatchTransport sender = BatchTraceTransport.sender(currentFrames::add,
                    includeMessage, maxFrames, maxFrameBytes, maxRecords);
            String currentOutcome;
            try {
                currentOutcome = "accepted " + sender.sendBatch(currentList);
            } catch (RuntimeException failure) {
                currentOutcome = outcome(failure);
            }
            String message = "case " + i + " limit=" + maxFrameBytes + " records=" + maxRecords;
            assertEquals(message, legacyOutcome, currentOutcome);
            assertEquals(message, legacyCalls, calls(events));
            assertEquals(message, legacyList.trace.toString(), currentList.trace.toString());
            assertEquals(message, legacyFrames.size(), currentFrames.size());
            for (int f = 0; f < legacyFrames.size(); f++) {
                assertArrayEquals(message, legacyFrames.get(f), currentFrames.get(f));
                int accepted = Integer.parseInt(currentOutcome.substring("accepted ".length()));
                if (accepted == count) fitted++; else partial++;
            }
            if (legacyFrames.isEmpty()) rejected++;
        }
        // The random limits must exercise complete, partial and rejected batches.
        assertTrue("fitted " + fitted, fitted > 100);
        assertTrue("partial " + partial, partial > 100);
        assertTrue("rejected " + rejected, rejected > 50);
    }

    static int frameLimit(Random random, List<CallbackExceptionTracker.Event> events,
                                  boolean includeMessage, int maxFrames) {
        switch (random.nextInt(4)) {
            case 0: return 13 + random.nextInt(400);
            case 1: return 13 + random.nextInt(65536 - 13 + 1);
            case 2: return random.nextBoolean() ? 65536 : 4096;
            default: {
                // Exact fit, one byte short and one byte spare around a random record boundary.
                int end = 8;
                int boundary = random.nextInt(events.size());
                for (int i = 0; i <= boundary; i++) {
                    ScriptedFailure failure = (ScriptedFailure) events.get(i).failure();
                    end += 4 + LegacyTraceCodec.encode(events.get(i), includeMessage, maxFrames).length;
                    failure.messageCalls = 0;
                    failure.frameCalls = 0;
                    failure.order.setLength(0);
                }
                int limit = end + random.nextInt(3) - 1;
                return Math.max(13, Math.min(65536, limit));
            }
        }
    }

    @Test public void compressedBatchSendersEmitTheLegacyFrameEncoding() throws Exception {
        Random random = new Random(SEED + 2);
        BoundedTraceCompression codec = new BoundedTraceCompression(65536, 65556, 512, 16,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        try (BoundedTraceCompression.Session session = codec.newSession()) {
            for (int i = 0; i < 200; i++) {
                List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>();
                int count = 1 + random.nextInt(20);
                for (int j = 0; j < count; j++) events.add(randomEvent(random));
                boolean includeMessage = random.nextBoolean();
                int maxFrames = random.nextInt(9);
                List<byte[]> legacyPlain = new ArrayList<byte[]>();
                int expectedCount = LegacyTraceCodec.send(events, includeMessage, maxFrames, 65536, 1024, legacyPlain);
                byte[] expected = codec.encode(legacyPlain.get(0)).bytes();
                List<byte[]> stateless = new ArrayList<byte[]>();
                List<byte[]> reused = new ArrayList<byte[]>();
                assertEquals(expectedCount, codec.batchSender(stateless::add, includeMessage, maxFrames, 65536, 1024)
                        .sendBatch(events));
                assertEquals(expectedCount, session.batchSender(reused::add, includeMessage, maxFrames, 65536, 1024)
                        .sendBatch(events));
                assertArrayEquals(expected, stateless.get(0));
                assertArrayEquals(expected, reused.get(0));
            }
        }
    }

    // ---- batch receiver ------------------------------------------------------------------------

    @Test public void batchReceiverMatchesLegacyParserOnValidAndCorruptFrames() throws Exception {
        Random random = new Random(SEED + 3);
        int accepted = 0;
        for (int i = 0; i < 3000; i++) {
            byte[] frame = randomFrame(random);
            byte[] mutated = mutate(random, frame);
            int maxFrameBytes = random.nextInt(5) == 0 ? 13 + random.nextInt(Math.max(1, mutated.length)) : 65536;
            int maxRecords = random.nextInt(5) == 0 ? 1 + random.nextInt(8) : 1024;
            String expected;
            List<byte[]> expectedRecords = null;
            try {
                expectedRecords = LegacyTraceCodec.receive(mutated, maxFrameBytes, maxRecords);
                expected = "records " + expectedRecords.size();
            } catch (RuntimeException failure) {
                expected = outcome(failure);
            }
            List<List<byte[]>> batches = new ArrayList<List<byte[]>>();
            String actual;
            byte[] before = mutated.clone();
            try {
                BatchTraceTransport.receiver(batches::add, maxFrameBytes, maxRecords).writeRecord(mutated);
                actual = "records " + batches.get(0).size();
            } catch (RuntimeException failure) {
                actual = outcome(failure);
            }
            assertEquals("case " + i, expected, actual);
            assertArrayEquals("receiver must not modify its input", before, mutated);
            if (expectedRecords != null) {
                accepted++;
                List<byte[]> records = batches.get(0);
                assertEquals(expectedRecords.size(), records.size());
                for (int r = 0; r < records.size(); r++) assertArrayEquals(expectedRecords.get(r), records.get(r));
                assertTrue(TestSupport.capture(records::clear) instanceof UnsupportedOperationException);
                if (!records.isEmpty() && mutated.length > 12) {
                    // Records are independent copies of the input.
                    byte saved = records.get(0)[0];
                    mutated[12] ^= 1;
                    assertEquals(saved, records.get(0)[0]);
                }
            }
        }
        assertTrue("valid frames " + accepted, accepted > 500);
    }

    @Test public void receiverStaysConsistentWhileTheCallerMutatesItsArray() throws Exception {
        Random random = new Random(SEED + 4);
        byte[] frame = randomFrame(random);
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        byte[] original = frame.clone();
        Thread mutator = new Thread(() -> {
            Random noise = new Random(7);
            while (running.get()) {
                int at = noise.nextInt(frame.length);
                frame[at] = noise.nextBoolean() ? original[at] : (byte) noise.nextInt(256);
            }
        }, "batch-frame-mutator");
        mutator.start();
        try {
            for (int i = 0; i < 20000; i++) {
                List<List<byte[]>> batches = new ArrayList<List<byte[]>>();
                try {
                    BatchTraceTransport.receiver(batches::add, 65536, 1024).writeRecord(frame);
                } catch (IllegalArgumentException rejected) {
                    continue; // Any other exception type (e.g. an index error) fails the test.
                }
                int total = 8;
                for (byte[] record : batches.get(0)) total += 4 + record.length;
                assertEquals("records must match the validated lengths", frame.length, total);
            }
        } finally {
            running.set(false);
            mutator.join();
        }
    }

    private static byte[] randomFrame(Random random) {
        int count = 1 + random.nextInt(6);
        List<byte[]> records = new ArrayList<byte[]>();
        int size = 8;
        for (int i = 0; i < count; i++) {
            byte[] record = new byte[1 + random.nextInt(random.nextBoolean() ? 8 : 300)];
            random.nextBytes(record);
            records.add(record);
            size += 4 + record.length;
        }
        java.nio.ByteBuffer frame = java.nio.ByteBuffer.allocate(size).putInt(0x4d484231).putInt(count);
        for (byte[] record : records) frame.putInt(record.length).put(record);
        return frame.array();
    }

    private static byte[] mutate(Random random, byte[] frame) {
        byte[] copy = frame.clone();
        switch (random.nextInt(9)) {
            case 0: case 1: case 2: return copy; // valid
            case 3: copy[random.nextInt(8)] ^= (byte) (1 << random.nextInt(8)); return copy;
            case 4: {
                int at = 8 + random.nextInt(Math.max(1, copy.length - 8));
                copy[Math.min(at, copy.length - 1)] = (byte) random.nextInt(256);
                return copy;
            }
            case 5: return Arrays.copyOf(copy, random.nextInt(copy.length));
            case 6: return Arrays.copyOf(copy, copy.length + 1 + random.nextInt(6));
            case 7: {
                // Corrupt one length word.
                int position = 8;
                int target = random.nextInt(Math.max(1, (copy[7] & 255)));
                for (int i = 0; i < target && position + 4 <= copy.length; i++) {
                    int length = (copy[position] & 255) << 24 | (copy[position + 1] & 255) << 16
                            | (copy[position + 2] & 255) << 8 | copy[position + 3] & 255;
                    position += 4 + length;
                }
                if (position + 4 <= copy.length) copy[position + random.nextInt(4)] ^= (byte) (1 << random.nextInt(8));
                return copy;
            }
            default: {
                byte[] garbage = new byte[random.nextInt(40)];
                random.nextBytes(garbage);
                return garbage;
            }
        }
    }
}
