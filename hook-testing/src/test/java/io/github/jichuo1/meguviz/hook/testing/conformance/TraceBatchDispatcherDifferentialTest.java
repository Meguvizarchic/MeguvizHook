package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.TraceBatchDispatcher;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Differential tests: TraceBatchDispatcher must behave exactly like the round-1 implementation
 * kept verbatim in {@link LegacyTraceBatchDispatcher} (CountDownLatch and a method reference per
 * attempt) for every scripted sequence of submit, run, cancel, get (interrupted or not, timed or
 * not), state queries and reference release, and for concurrent waiters.
 */
public final class TraceBatchDispatcherDifferentialTest {
    private static final long SEED = 0xd15_7a7c4L;

    static final class Fatal extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
        Fatal(String message) { super(message); }
    }

    /** One implementation behind a common shape. */
    interface Impl {
        Object submit(Executor executor, CallbackExceptionTracker tracker,
                      CallbackExceptionTracker.BatchTransport transport, int limit);
        Future<CallbackExceptionTracker.BatchAttempt> future(Object handle);
        Throwable submissionFailure(Object handle);
        Object field(Object handle, String name) throws Exception;
    }

    static final Impl LEGACY = new Impl() {
        @Override public Object submit(Executor e, CallbackExceptionTracker t,
                                       CallbackExceptionTracker.BatchTransport b, int l) {
            return LegacyTraceBatchDispatcher.submit(e, t, b, l);
        }
        @Override public Future<CallbackExceptionTracker.BatchAttempt> future(Object handle) {
            return (LegacyTraceBatchDispatcher.Attempt) handle;
        }
        @Override public Throwable submissionFailure(Object handle) {
            return ((LegacyTraceBatchDispatcher.Attempt) handle).submissionFailure();
        }
        @Override public Object field(Object handle, String name) throws Exception {
            Field field = LegacyTraceBatchDispatcher.Attempt.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(handle);
        }
    };

    static final Impl CURRENT = new Impl() {
        @Override public Object submit(Executor e, CallbackExceptionTracker t,
                                       CallbackExceptionTracker.BatchTransport b, int l) {
            return TraceBatchDispatcher.submit(e, t, b, l);
        }
        @Override public Future<CallbackExceptionTracker.BatchAttempt> future(Object handle) {
            return (TraceBatchDispatcher.Attempt) handle;
        }
        @Override public Throwable submissionFailure(Object handle) {
            return ((TraceBatchDispatcher.Attempt) handle).submissionFailure();
        }
        @Override public Object field(Object handle, String name) throws Exception {
            Field field = TraceBatchDispatcher.Attempt.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(handle);
        }
    };

    private static final class Hook {
        final RuntimeException problem = new RuntimeException("tracked");
        final BeforeCallback wrapped;
        Hook(CallbackExceptionTracker tracker) { wrapped = tracker.before(ignored -> { throw problem; }); }
        void fail(int times) {
            for (int i = 0; i < times; i++) {
                assertSame(problem, TestSupport.capture(() -> wrapped.before(
                        new TraceWireDifferentialTest.StubInvocation(HookId.of("dispatch.diff"), 1L))));
            }
        }
    }

    /** A deterministic script; the same instance drives both implementations. */
    private static final class Script {
        final int executorKind;
        final int transportKind;
        final int pending;
        final int limit;
        final int[] ops;
        final boolean[] interruptFirst;
        final long[] timeouts;
        final TimeUnit[] units;
        final RuntimeException submitFailure = new IllegalStateException("execute failed");
        final Error submitError = new AssertionError("execute error");
        final Fatal submitFatal = new Fatal("execute fatal");
        final Exception sendChecked = new java.io.IOException("send failed");
        final RuntimeException sendRuntime = new IllegalArgumentException("send runtime");
        final Fatal sendFatal = new Fatal("send fatal");

        Script(Random random) {
            executorKind = random.nextInt(8);
            transportKind = random.nextInt(6);
            pending = new int[] {0, 1, 3}[random.nextInt(3)];
            limit = 1 + random.nextInt(4);
            int count = 1 + random.nextInt(9);
            ops = new int[count];
            interruptFirst = new boolean[count];
            timeouts = new long[count];
            units = new TimeUnit[count];
            TimeUnit[] choices = {TimeUnit.NANOSECONDS, TimeUnit.MILLISECONDS, null};
            long[] values = {-5, 0, 1, Long.MIN_VALUE};
            for (int i = 0; i < count; i++) {
                ops[i] = random.nextInt(11);
                interruptFirst[i] = random.nextInt(3) == 0;
                timeouts[i] = values[random.nextInt(values.length)];
                units[i] = random.nextInt(8) == 0 ? null : choices[random.nextInt(2)];
            }
        }

        Map<Object, String> labels() {
            Map<Object, String> labels = new IdentityHashMap<Object, String>();
            labels.put(submitFailure, "submitFailure");
            labels.put(submitError, "submitError");
            labels.put(submitFatal, "submitFatal");
            labels.put(sendChecked, "sendChecked");
            labels.put(sendRuntime, "sendRuntime");
            labels.put(sendFatal, "sendFatal");
            return labels;
        }
    }

    private static String describe(Object value, Map<Object, String> labels) {
        if (value == null) return "null";
        String label = labels.get(value);
        if (label != null) return label;
        if (value instanceof CallbackExceptionTracker.BatchAttempt) {
            CallbackExceptionTracker.BatchAttempt attempt = (CallbackExceptionTracker.BatchAttempt) value;
            return attempt.status() + "/" + attempt.acceptedCount();
        }
        if (value instanceof Throwable) {
            Throwable failure = (Throwable) value;
            return failure.getClass().getSimpleName() + "(" + describe(failure.getCause(), labels) + ")";
        }
        return String.valueOf(value);
    }

    /** Runs a script against one implementation and returns everything observable as text. */
    private static String run(Impl impl, Script script) throws Exception {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("dispatch-diff", 8);
        new Hook(tracker).fail(script.pending);
        Map<Object, String> labels = script.labels();
        StringBuilder trace = new StringBuilder();
        List<Runnable> kept = new ArrayList<Runnable>();
        Executor executor = task -> {
            switch (script.executorKind) {
                case 0: task.run(); return;                                // inline
                case 1: kept.add(task); return;                            // deferred
                case 2: kept.add(task); throw script.submitFailure;        // retained, then throws
                case 3: throw script.submitFailure;                        // rejected
                case 4: task.run(); throw script.submitFailure;            // ran, then throws
                case 5: kept.add(task); throw script.submitError;          // non-fatal Error
                case 6: kept.add(task); throw script.submitFatal;          // fatal
                default: kept.add(task); kept.add(task); return;           // may run twice
            }
        };
        CallbackExceptionTracker.BatchTransport transport = events -> {
            trace.append("send").append(events.size()).append(';');
            switch (script.transportKind) {
                case 0: return events.size();
                case 1: return 1;
                case 2: throw script.sendChecked;
                case 3: throw script.sendRuntime;
                case 4: return 0; // invalid prefix: the tracker throws
                default: throw script.sendFatal;
            }
        };
        Object handle;
        try {
            handle = impl.submit(executor, tracker, transport, script.limit);
            trace.append("submitted;");
        } catch (Throwable failure) {
            trace.append("submit threw ").append(describe(failure, labels)).append(';');
            handle = null;
        }
        trace.append("interrupted=").append(Thread.interrupted()).append(';');
        if (handle == null) return trace.append("pending=").append(tracker.pendingCount()).toString();
        Future<CallbackExceptionTracker.BatchAttempt> future = impl.future(handle);
        int nextKept = 0;
        for (int i = 0; i < script.ops.length; i++) {
            trace.append('#').append(i).append(':');
            try {
                switch (script.ops[i]) {
                    case 0: trace.append("cancel(true)=").append(future.cancel(true)); break;
                    case 1: trace.append("cancel(false)=").append(future.cancel(false)); break;
                    case 2: trace.append("isDone=").append(future.isDone()); break;
                    case 3: trace.append("isCancelled=").append(future.isCancelled()); break;
                    case 4: trace.append("submissionFailure=")
                            .append(describe(impl.submissionFailure(handle), labels)); break;
                    case 5: {
                        // An untimed get on an unfinished attempt would block forever: only
                        // issue it pre-interrupted then (both sides decide from their own isDone).
                        boolean done = future.isDone();
                        boolean interrupt = script.interruptFirst[i] || !done;
                        trace.append("done=").append(done).append(" get(interrupt=").append(interrupt).append(")=");
                        if (interrupt) Thread.currentThread().interrupt();
                        trace.append(describe(future.get(), labels));
                        break;
                    }
                    case 6: {
                        if (script.interruptFirst[i]) Thread.currentThread().interrupt();
                        trace.append("get(").append(script.timeouts[i]).append(',').append(script.units[i])
                                .append(",interrupt=").append(script.interruptFirst[i]).append(")=");
                        trace.append(describe(future.get(script.timeouts[i], script.units[i]), labels));
                        break;
                    }
                    case 7: case 8: {
                        if (nextKept < kept.size()) {
                            trace.append("run kept ").append(nextKept).append(':');
                            kept.get(nextKept++).run();
                            trace.append("returned");
                        } else {
                            trace.append("no kept task");
                        }
                        break;
                    }
                    case 9: trace.append("released=").append(impl.field(handle, "tracker") == null)
                            .append('/').append(impl.field(handle, "transport") == null); break;
                    default: trace.append("pending=").append(tracker.pendingCount()); break;
                }
            } catch (Throwable failure) {
                trace.append(" threw ").append(describe(failure, labels));
            }
            trace.append(" interrupted=").append(Thread.interrupted()).append(';');
        }
        trace.append("final pending=").append(tracker.pendingCount()).append(" done=").append(future.isDone())
                .append(" cancelled=").append(future.isCancelled());
        return trace.toString();
    }

    @Test public void scriptedSequencesMatchTheLegacyDispatcher() throws Exception {
        Random random = new Random(SEED);
        int interrupted = 0;
        int timedOut = 0;
        int cancelled = 0;
        int failedRuns = 0;
        for (int i = 0; i < 6000; i++) {
            Script script = new Script(random);
            String expected = run(LEGACY, script);
            String actual = run(CURRENT, script);
            assertEquals("script " + i, expected, actual);
            if (expected.contains("InterruptedException")) interrupted++;
            if (expected.contains("TimeoutException")) timedOut++;
            if (expected.contains("CancellationException")) cancelled++;
            if (expected.contains("ExecutionException(send")) failedRuns++;
        }
        // The scripts must reach every outcome family.
        assertTrue("interrupted " + interrupted, interrupted > 300);
        assertTrue("timed out " + timedOut, timedOut > 100);
        assertTrue("cancelled " + cancelled, cancelled > 100);
        assertTrue("failed runs " + failedRuns, failedRuns > 100);
    }

    /** A waiter thread's view of one get call, in text. */
    private static String await(Impl impl, String mode, Runnable completeOrInterrupt) throws Exception {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("dispatch-wait", 4);
        new Hook(tracker).fail(1);
        AtomicReference<Runnable> kept = new AtomicReference<Runnable>();
        Object handle = impl.submit(kept::set, tracker, events -> events.size(), 4);
        Future<CallbackExceptionTracker.BatchAttempt> future = impl.future(handle);
        int waiters = "three".equals(mode) ? 3 : 1;
        List<Thread> threads = new ArrayList<Thread>();
        String[] seen = new String[waiters];
        CountDownLatch started = new CountDownLatch(waiters);
        for (int w = 0; w < waiters; w++) {
            int index = w;
            Thread thread = new Thread(() -> {
                started.countDown();
                String outcome;
                try {
                    CallbackExceptionTracker.BatchAttempt result;
                    switch (mode) {
                        case "timed": case "interruptTimed": result = future.get(10, TimeUnit.SECONDS); break;
                        case "maxNanos": result = future.get(Long.MAX_VALUE, TimeUnit.NANOSECONDS); break;
                        case "maxDays": result = future.get(Long.MAX_VALUE, TimeUnit.DAYS); break;
                        case "expire": result = future.get(50, TimeUnit.MILLISECONDS); break;
                        default: result = future.get(); break;
                    }
                    outcome = result.status() + "/" + result.acceptedCount();
                } catch (Throwable failure) {
                    outcome = failure.getClass().getSimpleName();
                }
                seen[index] = outcome + " interrupted=" + Thread.currentThread().isInterrupted();
            }, "dispatch-waiter-" + w);
            threads.add(thread);
            thread.start();
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // Wait until every waiter is parked inside get.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Thread thread : threads) {
            while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING
                    && thread.isAlive() && System.nanoTime() < deadline) Thread.yield();
        }
        if (completeOrInterrupt != null) {
            completeOrInterrupt.run();
        } else if ("interrupt".equals(mode) || "interruptTimed".equals(mode)) {
            for (Thread thread : threads) thread.interrupt();
        } else if ("cancel".equals(mode)) {
            future.cancel(false);
        } else if (!"expire".equals(mode)) {
            kept.get().run();
        }
        for (Thread thread : threads) thread.join(10000);
        StringBuilder text = new StringBuilder(mode).append(':');
        for (String outcome : seen) text.append(outcome).append(';');
        text.append(" done=").append(future.isDone()).append(" pending=").append(tracker.pendingCount());
        // After an interrupt the attempt is still pending and completes normally later.
        if (!future.isDone()) {
            kept.get().run();
            text.append(" later=").append(future.get().status());
        }
        return text.toString();
    }

    @Test public void concurrentWaitersMatchTheLegacyDispatcher() throws Exception {
        for (String mode : new String[] {"untimed", "timed", "maxNanos", "maxDays", "three", "interrupt",
                "interruptTimed", "cancel", "expire"}) {
            String expected = await(LEGACY, mode, null);
            String actual = await(CURRENT, mode, null);
            assertEquals(expected, actual);
        }
        assertEquals("interrupt:InterruptedException interrupted=false;"
                + " done=false pending=1 later=ACCEPTED", await(CURRENT, "interrupt", null));
        assertEquals("three:ACCEPTED/1 interrupted=false;ACCEPTED/1 interrupted=false;ACCEPTED/1 interrupted=false;"
                + " done=true pending=0", await(CURRENT, "three", null));
    }

    @Test public void timedGetWaitsForItsTimeout() throws Exception {
        for (Impl impl : new Impl[] {LEGACY, CURRENT}) {
            CallbackExceptionTracker tracker = new CallbackExceptionTracker("dispatch-timeout", 2);
            Object handle = impl.submit(task -> { }, tracker, events -> events.size(), 1);
            long start = System.nanoTime();
            Throwable failure = TestSupport.capture(() -> impl.future(handle).get(40, TimeUnit.MILLISECONDS));
            long elapsed = System.nanoTime() - start;
            assertTrue(failure instanceof java.util.concurrent.TimeoutException);
            assertTrue("waited " + elapsed, elapsed >= TimeUnit.MILLISECONDS.toNanos(40));
        }
    }

    @Test public void queuedTaskCannotReleaseWaitersEarly() throws Exception {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("dispatch-release", 2);
        new Hook(tracker).fail(1);
        AtomicReference<Runnable> kept = new AtomicReference<Runnable>();
        TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(kept::set, tracker, events -> 1, 1);
        Runnable task = kept.get();
        if (task instanceof AbstractQueuedSynchronizer) {
            // The synchronizer's public release only reports the terminal state.
            assertFalse(((AbstractQueuedSynchronizer) task).releaseShared(1));
        }
        assertTrue(TestSupport.capture(() -> attempt.get(1, TimeUnit.MILLISECONDS))
                instanceof java.util.concurrent.TimeoutException);
        assertFalse(attempt.isDone());
        task.run();
        assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, attempt.get().status());
    }
}
