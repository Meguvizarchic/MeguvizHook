package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Explicit tracking is independent of the default SDK error policy and does not perform I/O. */
public final class CallbackExceptionTrackerTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void beforeFailureKeepsIdentityAndQueueIsBounded() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 3);
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("process-0", 1);
        RuntimeException failure = new IllegalStateException("before");
        BeforeCallback wrapped = tracker.before(invocation -> { throw failure; });
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.before", ADD).before(wrapped).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(0, backend.originalCallCount(ADD));
            assertEquals(1, tracker.pendingCount());
            assertEquals(1L, tracker.droppedCount());
            List<CallbackExceptionTracker.Event> records = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(1, tracker.deliver(records::add, 1));
            assertSame(failure, records.get(0).failure());
            assertEquals(CallbackExceptionTracker.Stage.BEFORE, records.get(0).stage());
            assertEquals("trace.before", records.get(0).hookId().value());
            assertEquals("process-0", records.get(0).source());
            assertEquals(0, tracker.pendingCount());
        } finally { env.close(); }
    }

    @Test public void afterObservesOriginalAndCallbackFailuresWithoutChangingPrecedence() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        RuntimeException original = new IllegalArgumentException("original");
        RuntimeException after = new IllegalStateException("after");
        backend.onTarget(ADD, (self, args) -> { throw original; });
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("process-1", 4);
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.after", ADD)
                    .after(tracker.after((invocation, result) -> { throw after; }, true)).build());
            assertSame(original, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            List<CallbackExceptionTracker.Event> records = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(2, tracker.deliver(records::add, 4));
            assertEquals(CallbackExceptionTracker.Stage.OBSERVED_THROWABLE, records.get(0).stage());
            assertSame(original, records.get(0).failure());
            assertEquals(CallbackExceptionTracker.Stage.AFTER, records.get(1).stage());
            assertSame(after, records.get(1).failure());
            assertEquals(records.get(0).generation(), records.get(1).generation());
            assertEquals(1, backend.originalCallCount(ADD));
        } finally { env.close(); }
    }

    @Test public void observedThrowableIsNotAutomaticallyAttributedToOriginal() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("process-early", 2);
        RuntimeException early = new IllegalStateException("early");
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.early", ADD)
                    .before(invocation -> invocation.setEarlyThrowable(early))
                    .after(tracker.after((invocation, result) -> { }, true)).build());
            assertSame(early, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(0, backend.originalCallCount(ADD));
            List<CallbackExceptionTracker.Event> records = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(1, tracker.deliver(records::add, 1));
            assertEquals(CallbackExceptionTracker.Stage.OBSERVED_THROWABLE, records.get(0).stage());
            assertSame(early, records.get(0).failure());
        } finally { env.close(); }
    }

    @SuppressWarnings("removal")
    @Test public void fatalIsRethrownWithoutQueueingAndNormalPathDoesNotSend() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 3);
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("process-2", 2);
        ThreadDeath fatal = new ThreadDeath();
        AtomicInteger calls = new AtomicInteger();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.fatal", ADD)
                    .before(tracker.before(invocation -> { if (calls.getAndIncrement() == 1) throw fatal; })).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(0, tracker.pendingCount());
            assertEquals(0L, tracker.droppedCount());
            assertEquals(0L, tracker.trackingFailureCount());
        } finally { env.close(); }
    }

    @Test public void failedDeliveryKeepsHeadAndRequiresExplicitRetry() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("process-3", 2);
        RuntimeException callback = new IllegalStateException("callback");
        Exception transport = new Exception("transport may have committed");
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.delivery", ADD)
                    .before(tracker.before(invocation -> { throw callback; })).build());
            assertSame(callback, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            AtomicInteger sends = new AtomicInteger();
            assertSame(transport, TestSupport.capture(() -> tracker.deliver(event -> {
                sends.incrementAndGet(); throw transport;
            }, 1)));
            assertEquals(1, tracker.pendingCount());
            assertEquals(1, sends.get());
            assertEquals(1, tracker.deliver(event -> sends.incrementAndGet(), 1));
            assertEquals(2, sends.get());
            assertEquals(0, tracker.pendingCount());
        } finally { env.close(); }
    }

    @Test public void explicitDiscardReturnsEventAndIncrementsAuditCount() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("process-drop", 2);
        RuntimeException callback = new IllegalStateException("callback");
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.discard", ADD)
                    .before(tracker.before(invocation -> { throw callback; })).build());
            assertSame(callback, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            Exception unavailable = new Exception("not delivered");
            assertSame(unavailable, TestSupport.capture(() -> tracker.deliver(event -> { throw unavailable; }, 1)));
            CallbackExceptionTracker.Event discarded = tracker.discardNext();
            assertNotNull(discarded);
            assertSame(callback, discarded.failure());
            assertEquals("trace.discard", discarded.hookId().value());
            assertEquals(1L, tracker.discardedCount());
            assertNull(tracker.discardNext());
            assertEquals(1L, tracker.discardedCount());
        } finally { env.close(); }
    }

    @Test public void optionalJsonTransportBoundsFramesAndOmitsMessageUnlessEnabled() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("process\njson", 2);
        RuntimeException failure = new IllegalStateException("private\"message");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("Example", "first", "Example.java", 12),
                new StackTraceElement("Example", "second", null, 13)
        });
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.json", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            List<byte[]> payloads = new ArrayList<byte[]>();
            assertEquals(1, tracker.deliver(new JsonLineTraceTransport(payloads::add, false, 1), 1));
            String json = new String(payloads.get(0), StandardCharsets.UTF_8);
            Map<?, ?> parsed = (Map<?, ?>) TraceJsonParser.parse(json);
            assertEquals(1L, parsed.get("version"));
            assertEquals("process\njson", parsed.get("source"));
            assertEquals("trace.json", parsed.get("hook"));
            assertEquals(Boolean.TRUE, parsed.get("framesTruncated"));
            assertFalse(parsed.containsKey("message"));
            assertTrue(json.startsWith("{\"version\":1,"));
            assertTrue(json.contains("\"source\":\"process\\u000ajson\""));
            assertTrue(json.contains("\"hook\":\"trace.json\""));
            assertTrue(json.contains("\"framesTruncated\":true"));
            assertTrue(json.contains("\"method\":\"first\""));
            assertFalse(json.contains("second"));
            assertFalse(json.contains("private"));
            assertTrue(json.endsWith("}\n"));
        } finally { env.close(); }
    }

    @Test public void jsonLongFieldsEscapesAndUnicodeRemainParseable() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        String source = "process\"\\\n" + new String(Character.toChars(0x1f603)) + (char) 0xd800;
        CallbackExceptionTracker tracker = new CallbackExceptionTracker(source, 2);
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 511; i++) longMessage.append('x');
        longMessage.appendCodePoint(0x1f603).append("tail");
        RuntimeException failure = new IllegalStateException(longMessage.toString());
        StringBuilder longClass = new StringBuilder();
        for (int i = 0; i < 140; i++) longClass.append('C');
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(longClass.toString(), "method\"\\", null, 9),
                new StackTraceElement("More", "frame", "More.java", 10)
        });
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.json.long", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            List<byte[]> payloads = new ArrayList<byte[]>();
            assertEquals(1, tracker.deliver(new JsonLineTraceTransport(payloads::add, true, 1), 1));
            Map<?, ?> json = (Map<?, ?>) TraceJsonParser.parse(new String(payloads.get(0), StandardCharsets.UTF_8));
            assertEquals("process\"\\\n" + new String(Character.toChars(0x1f603)) + '\ufffd', json.get("source"));
            assertEquals(Boolean.TRUE, json.get("messageTruncated"));
            assertEquals(511, ((String) json.get("message")).length());
            assertEquals(Boolean.TRUE, json.get("framesTruncated"));
            List<?> frames = (List<?>) json.get("frames");
            assertEquals(1, frames.size());
            Map<?, ?> frame = (Map<?, ?>) frames.get(0);
            assertEquals(Boolean.TRUE, frame.get("classTruncated"));
            assertEquals(128, ((String) frame.get("class")).length());
            assertEquals("method\"\\", frame.get("method"));
            assertNull(frame.get("file"));
        } finally { env.close(); }
    }

    @Test public void jsonEscapesControlCharactersAndSurrogatesAsExactBytes() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        StringBuilder input = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            input.append((char) i);
            expected.append(String.format(java.util.Locale.ROOT, "\\u%04x", i));
        }
        input.append('"').append('\\').appendCodePoint(0x1f603).append((char) 0xd800).append('X').append((char) 0xdc00);
        expected.append("\\\"\\\\\\ud83d\\ude03\\ufffdX\\ufffd");
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("unicode", 1);
        RuntimeException failure = new RuntimeException(input.toString());
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.unicode", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            List<byte[]> payloads = new ArrayList<byte[]>();
            assertEquals(1, tracker.deliver(new JsonLineTraceTransport(payloads::add, true, 0), 1));
            String json = new String(payloads.get(0), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"message\":\"" + expected + "\""));
            assertEquals(input.substring(0, 32) + "\"\\" + new String(Character.toChars(0x1f603))
                    + '\ufffd' + "X" + '\ufffd', ((Map<?, ?>) TraceJsonParser.parse(json)).get("message"));
        } finally { env.close(); }
    }

    @Test public void jsonUtf8PreservesExactBytesForCjkAndLargeSupplementaryFrames() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("中文é", 1);
        IllegalStateException failure = new IllegalStateException("汉字é");
        StringBuilder name = new StringBuilder(), escaped = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            name.appendCodePoint(0x1f600);
            escaped.append("\\ud83d\\ude00");
        }
        StackTraceElement[] frames = new StackTraceElement[32];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement(name.toString(), name.toString(), name.toString(), Integer.MIN_VALUE);
        }
        failure.setStackTrace(frames);
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.utf8.large", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(1, tracker.deliver(events::add, 1));
            CallbackExceptionTracker.Event event = events.get(0);
            StringBuilder expected = new StringBuilder("{\"version\":1,\"source\":\"中文é\",\"hook\":\"trace.utf8.large\",\"generation\":")
                    .append(event.generation()).append(",\"timeNanos\":").append(event.timeNanos())
                    .append(",\"stage\":\"BEFORE\",\"type\":\"java.lang.IllegalStateException\",\"message\":\"汉字é\",\"frames\":[");
            for (int i = 0; i < frames.length; i++) {
                if (i != 0) expected.append(',');
                expected.append("{\"class\":\"").append(escaped).append("\",\"method\":\"")
                        .append(escaped).append("\",\"file\":\"").append(escaped)
                        .append("\",\"line\":").append(Integer.MIN_VALUE).append('}');
            }
            expected.append("],\"framesCollected\":true,\"framesTruncated\":false}\n");
            byte[] encoded = JsonLineTraceTransport.encode(event, true, 32);
            assertTrue(encoded.length > 65536);
            assertArrayEquals(expected.toString().getBytes(StandardCharsets.UTF_8), encoded);
        } finally { env.close(); }
    }

    @Test public void jsonReentrantGetterKeepsEachEncodedArrayIndependent() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("reentrant", 2);
        AtomicReference<RuntimeException> next = new AtomicReference<RuntimeException>();
        AtomicReference<CallbackExceptionTracker.Event> innerEvent = new AtomicReference<CallbackExceptionTracker.Event>();
        List<byte[]> innerBytes = new ArrayList<byte[]>();
        RuntimeException inner = new IllegalStateException("inside");
        RuntimeException outer = new RuntimeException() {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() {
                innerBytes.add(JsonLineTraceTransport.encode(innerEvent.get(), true, 0));
                return "outside";
            }
        };
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.utf8.reentrant", ADD)
                    .before(tracker.before(invocation -> { throw next.get(); })).build());
            next.set(inner);
            assertSame(inner, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            next.set(outer);
            assertSame(outer, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(2, tracker.deliver(events::add, 2));
            innerEvent.set(events.get(0));
            byte[] first = JsonLineTraceTransport.encode(events.get(1), true, 0);
            byte[] second = JsonLineTraceTransport.encode(events.get(1), true, 0);
            assertEquals(2, innerBytes.size());
            assertArrayEquals(first, second);
            assertNotSame(first, second);
            assertArrayEquals(innerBytes.get(0), innerBytes.get(1));
            assertNotSame(innerBytes.get(0), innerBytes.get(1));
            first[0] = 0;
            innerBytes.get(0)[0] = 0;
            assertEquals('{', second[0]);
            assertEquals('{', innerBytes.get(1)[0]);
            assertEquals("outside", ((Map<?, ?>) TraceJsonParser.parse(new String(second, StandardCharsets.UTF_8))).get("message"));
            assertEquals("inside", ((Map<?, ?>) TraceJsonParser.parse(new String(innerBytes.get(1), StandardCharsets.UTF_8))).get("message"));
        } finally { env.close(); }
    }

    private static final class HostileThrowable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean nullFrames;

        HostileThrowable(boolean nullFrames) { this.nullFrames = nullFrames; }
        @Override public String getMessage() { throw new IllegalStateException("message inaccessible"); }
        @Override public StackTraceElement[] getStackTrace() {
            if (nullFrames) return null;
            throw new IllegalStateException("stack inaccessible");
        }
    }

    @Test public void hostileThrowableAccessorsDoNotPoisonJsonQueue() throws Throwable {
        for (boolean nullFrames : new boolean[]{false, true}) {
            FakeBackend backend = FakeBackend.create();
            HookEnvironment env = HookEnvironment.create(backend);
            CallbackExceptionTracker tracker = new CallbackExceptionTracker("hostile", 1);
            HostileThrowable failure = new HostileThrowable(nullFrames);
            try {
                TestSupport.installOrFail(env, TestSupport.spec("trace.hostile", ADD)
                        .before(tracker.before(invocation -> { throw failure; })).build());
                assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                List<byte[]> payloads = new ArrayList<byte[]>();
                assertEquals(1, tracker.deliver(new JsonLineTraceTransport(payloads::add, true, 2), 1));
                Map<?, ?> json = (Map<?, ?>) TraceJsonParser.parse(new String(payloads.get(0), StandardCharsets.UTF_8));
                assertTrue(json.containsKey("message"));
                assertNull(json.get("message"));
                assertEquals(Boolean.TRUE, json.get("messageError"));
                assertEquals(Boolean.FALSE, json.get("framesCollected"));
                assertEquals(Boolean.TRUE, json.get("framesError"));
                assertEquals(0, tracker.pendingCount());
            } finally { env.close(); }
        }
    }

    @SuppressWarnings("removal")
    @Test public void fatalThrowableAccessorStillPropagatesAndRequiresExplicitDisposition() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("fatal-accessor", 1);
        ThreadDeath fatal = new ThreadDeath();
        RuntimeException failure = new RuntimeException() {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { throw fatal; }
        };
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.fatal.accessor", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertSame(fatal, TestSupport.capture(() -> tracker.deliver(
                    new JsonLineTraceTransport(bytes -> { }, true, 0), 1)));
            assertEquals(1, tracker.pendingCount());
            assertSame(failure, tracker.discardNext().failure());
            assertEquals(1L, tracker.discardedCount());
        } finally { env.close(); }
    }
}
