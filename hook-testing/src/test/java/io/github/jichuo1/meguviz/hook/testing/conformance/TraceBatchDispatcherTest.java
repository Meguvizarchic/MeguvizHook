package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.TraceBatchDispatcher;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TraceBatchDispatcherTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void cancellationBeforeStartNeverSendsEvenIfExecutorRunsTaskTwice() throws Throwable {
        Fixture fixture = new Fixture();
        try {
            AtomicReference<Runnable> task = new AtomicReference<Runnable>();
            AtomicBoolean sent = new AtomicBoolean();
            TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(task::set, fixture.tracker,
                    events -> { sent.set(true); return 1; }, 1);
            assertTrue(attempt.cancel(true));
            assertTrue(attempt.isDone());
            assertTrue(attempt.isCancelled());
            assertReleased(attempt);
            assertTrue(TestSupport.capture(attempt::get) instanceof CancellationException);
            task.get().run();
            task.get().run();
            assertFalse(sent.get());
            assertEquals(1, fixture.tracker.pendingCount());
        } finally { fixture.close(); }
    }

    @Test public void runningCannotBeCancelledAndAcknowledgementRemainsObservable() throws Throwable {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<Thread>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(task -> {
                Thread thread = new Thread(task, "trace-dispatch-test");
                worker.set(thread);
                thread.start();
            }, fixture.tracker, events -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out");
                interrupted.set(Thread.currentThread().isInterrupted());
                return 1;
            }, 1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(attempt.cancel(true));
            assertFalse(attempt.isDone());
            assertSame(fixture.tracker, attemptField(attempt, "tracker"));
            release.countDown();
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED,
                    attempt.get(5, TimeUnit.SECONDS).status());
            assertReleased(attempt);
            assertFalse(interrupted.get());
            assertEquals(0, fixture.tracker.pendingCount());
        } finally {
            release.countDown();
            if (worker.get() != null) worker.get().join(5000);
            fixture.close();
        }
    }

    @Test public void synchronousExecutionAndFailurePreserveReceiverOutcome() throws Throwable {
        Fixture fixture = new Fixture();
        try {
            Exception receiverFailure = new Exception("side effect unknown");
            TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(Runnable::run, fixture.tracker,
                    events -> { throw receiverFailure; }, 1);
            assertNull(attempt.submissionFailure());
            assertFalse(attempt.cancel(false));
            Throwable result = TestSupport.capture(attempt::get);
            assertTrue(result instanceof ExecutionException);
            assertSame(receiverFailure, result.getCause());
            assertReleased(attempt);
            assertEquals(1, fixture.tracker.pendingCount());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED,
                    TraceBatchDispatcher.submit(Runnable::run, fixture.tracker, events -> 1, 1).get().status());
        } finally { fixture.close(); }
    }

    @Test public void executorThrowBeforeStartClosesRetainedTaskAndReportsFailure() throws Throwable {
        Fixture fixture = new Fixture();
        try {
            RejectedExecutionException rejection = new RejectedExecutionException("possibly retained");
            AtomicReference<Runnable> retained = new AtomicReference<Runnable>();
            TraceBatchDispatcher.Attempt pending = TraceBatchDispatcher.submit(task -> {
                retained.set(task);
                throw rejection;
            }, fixture.tracker, events -> 1, 1);
            assertSame(rejection, pending.submissionFailure());
            assertTrue(pending.isDone());
            assertReleased(pending);
            assertFalse(pending.cancel(false));
            Throwable failure = TestSupport.capture(pending::get);
            assertTrue(failure instanceof ExecutionException);
            assertSame(rejection, failure.getCause());
            retained.get().run();
            assertEquals(1, fixture.tracker.pendingCount());
            assertSame(rejection, pending.submissionFailure());

            TraceBatchDispatcher.Attempt inline = TraceBatchDispatcher.submit(task -> {
                task.run();
                throw rejection;
            }, fixture.tracker, events -> 1, 1);
            assertSame(rejection, inline.submissionFailure());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, inline.get().status());
            assertReleased(inline);
            assertEquals(0, fixture.tracker.pendingCount());
            TraceBatchDispatcher.Attempt rejected = TraceBatchDispatcher.submit(task -> { throw rejection; },
                    fixture.tracker, events -> 1, 1);
            assertTrue(rejected.isDone());
            assertFalse(rejected.cancel(false));
            Throwable rejectedResult = TestSupport.capture(rejected::get);
            assertTrue(rejectedResult instanceof ExecutionException);
            assertSame(rejection, rejectedResult.getCause());
        } finally { fixture.close(); }
    }

    @Test public void executorThrowAfterStartDoesNotHideAcknowledgement() throws Throwable {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<Thread>();
        RejectedExecutionException afterStart = new RejectedExecutionException("started then threw");
        try {
            TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(task -> {
                Thread thread = new Thread(task, "trace-started-before-executor-throw");
                worker.set(thread);
                thread.start();
                try {
                    if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("not started");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                throw afterStart;
            }, fixture.tracker, events -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out");
                return 1;
            }, 1);
            assertSame(afterStart, attempt.submissionFailure());
            assertFalse(attempt.isDone());
            assertFalse(attempt.cancel(true));
            release.countDown();
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED,
                    attempt.get(5, TimeUnit.SECONDS).status());
            assertEquals(0, fixture.tracker.pendingCount());
        } finally {
            release.countDown();
            if (worker.get() != null) worker.get().join(5000);
            fixture.close();
        }
    }

    @Test public void cancellationRacingStartHasExactlyOneWinner() throws Throwable {
        Fixture fixture = new Fixture();
        AtomicReference<Runnable> task = new AtomicReference<Runnable>();
        AtomicBoolean sent = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        Thread worker = null;
        Thread canceller = null;
        try {
            TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(task::set, fixture.tracker,
                    events -> { sent.set(true); return 1; }, 1);
            worker = new Thread(() -> { await(start); task.get().run(); }, "trace-race-worker");
            canceller = new Thread(() -> { await(start); cancelled.set(attempt.cancel(true)); }, "trace-race-cancel");
            worker.start();
            canceller.start();
            start.countDown();
            worker.join(5000);
            canceller.join(5000);
            assertFalse(worker.isAlive());
            assertFalse(canceller.isAlive());
            if (cancelled.get()) {
                assertFalse(sent.get());
                assertEquals(1, fixture.tracker.pendingCount());
                assertTrue(TestSupport.capture(attempt::get) instanceof CancellationException);
            } else {
                assertTrue(sent.get());
                assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, attempt.get().status());
                assertEquals(0, fixture.tracker.pendingCount());
            }
        } finally {
            start.countDown();
            if (worker != null) worker.join(5000);
            if (canceller != null) canceller.join(5000);
            fixture.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test public void fatalTransportFailurePropagatesOnWorkerAndReleasesReservation() throws Throwable {
        Fixture fixture = new Fixture();
        try {
            AtomicReference<Runnable> task = new AtomicReference<Runnable>();
            OutOfMemoryError fatal = new OutOfMemoryError("simulated");
            TraceBatchDispatcher.Attempt attempt = TraceBatchDispatcher.submit(task::set, fixture.tracker,
                    events -> { throw fatal; }, 1);
            assertSame(fatal, TestSupport.capture(() -> task.get().run()));
            Throwable outcome = TestSupport.capture(attempt::get);
            assertTrue(outcome instanceof ExecutionException);
            assertSame(fatal, outcome.getCause());
            assertReleased(attempt);
            assertEquals(1, fixture.tracker.pendingCount());
            assertEquals(1, fixture.tracker.deliver(event -> { }, 1));
        } finally { fixture.close(); }
    }

    private static Object attemptField(TraceBatchDispatcher.Attempt attempt, String name) throws Exception {
        Field field = TraceBatchDispatcher.Attempt.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(attempt);
    }

    private static void assertReleased(TraceBatchDispatcher.Attempt attempt) throws Exception {
        assertNull(attemptField(attempt, "tracker"));
        assertNull(attemptField(attempt, "transport"));
    }

    private static final class Fixture implements AutoCloseable {
        final FakeBackend backend = FakeBackend.create();
        final HookEnvironment env = HookEnvironment.create(backend);
        final CallbackExceptionTracker tracker = new CallbackExceptionTracker("async-trace", 2);

        Fixture() throws Throwable {
            RuntimeException failure = new RuntimeException("tracked");
            TestSupport.installOrFail(env, TestSupport.spec("trace.dispatch", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
        }

        @Override public void close() { env.close(); }
    }
}
