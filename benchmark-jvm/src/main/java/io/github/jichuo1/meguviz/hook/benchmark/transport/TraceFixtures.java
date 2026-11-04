package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark-only fixtures. Events are produced through the public tracker wrapper exactly as a
 * failing hook callback would, using a minimal invocation stub instead of a backend.
 */
final class TraceFixtures {
    private TraceFixtures() { }

    /** Deterministic failure: fixed message and synthetic, realistically named frames. */
    static final class SyntheticFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyntheticFailure(String message, int frames) {
            super(message, null, false, true);
            StackTraceElement[] trace = new StackTraceElement[frames];
            for (int i = 0; i < frames; i++) {
                trace[i] = new StackTraceElement("com.example.app.feature" + (i % 7) + ".internal.HandlerClass" + i,
                        "dispatchEvent" + (i % 5), "HandlerClass" + i + ".java", 100 + i * 7);
            }
            setStackTrace(trace);
        }
    }

    /** Minimal invocation: the tracker only reads id() and generation(). */
    static final class StubInvocation implements HookInvocation {
        private final HookId id;
        private final long generation;

        StubInvocation(String id, long generation) {
            this.id = HookId.of(id);
            this.generation = generation;
        }

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

    /** Captures through a wrapped callback; returns normally after the failure was recorded. */
    static final class Producer {
        private final BeforeCallback wrapped;
        private final StubInvocation invocation;
        private final RuntimeException failure;

        Producer(CallbackExceptionTracker tracker, String hook, RuntimeException failure) {
            this.failure = failure;
            this.wrapped = tracker.before(ignored -> { throw this.failure; });
            this.invocation = new StubInvocation(hook, 7L);
        }

        void capture() {
            try {
                wrapped.before(invocation);
            } catch (Throwable expected) {
                if (expected != failure) throw new IllegalStateException(expected);
            }
        }
    }

    static String asciiMessage(int chars) {
        StringBuilder text = new StringBuilder(chars);
        String words = "request failed while dispatching handler state=ready id=0042 retry=false; ";
        while (text.length() < chars) text.append(words);
        text.setLength(chars);
        return text.toString();
    }

    static String unicodeMessage(int chars) {
        StringBuilder text = new StringBuilder(chars);
        String words = "处理请求失败 state=就绪 id=0042 \"quoted\" \\ tab\t ok; ";
        while (text.length() < chars) text.append(words);
        text.setLength(chars);
        return text.toString();
    }

    /** Pure CJK text: three UTF-8 bytes per unit, the largest share without escapes. */
    static String cjkMessage(int chars) {
        StringBuilder text = new StringBuilder(chars);
        String words = "处理请求失败状态就绪重试";
        while (text.length() < chars) text.append(words);
        text.setLength(chars);
        return text.toString();
    }

    static String message(String kind) {
        switch (kind) {
            case "none": return null;
            case "ascii1k": return asciiMessage(900);
            case "unicode1k": return unicodeMessage(400);
            default: throw new IllegalArgumentException(kind);
        }
    }

    static CallbackExceptionTracker.Event event(String hook, RuntimeException failure) {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("target-process", 1);
        new Producer(tracker, hook, failure).capture();
        CallbackExceptionTracker.Event event = tracker.discardNext();
        if (event == null) throw new IllegalStateException("event was not captured");
        return event;
    }

    static List<CallbackExceptionTracker.Event> events(int count, String message, int stackFrames) {
        List<CallbackExceptionTracker.Event> events = new ArrayList<CallbackExceptionTracker.Event>(count);
        for (int i = 0; i < count; i++) {
            events.add(event("bench.hook." + (i % 4), new SyntheticFailure(message, stackFrames)));
        }
        return events;
    }
}
