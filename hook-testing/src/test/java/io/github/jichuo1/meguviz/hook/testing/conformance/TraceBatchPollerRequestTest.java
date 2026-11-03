package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.TraceBatchPoller;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in early trigger: TraceBatchPoller.requestPoll and CallbackExceptionTracker.CaptureSignal. */
public final class TraceBatchPollerRequestTest {
    private static final RuntimeException PROBLEM = new RuntimeException("captured");

    private static final class Hook {
        final BeforeCallback wrapped;
        final TraceWireDifferentialTest.StubInvocation invocation =
                new TraceWireDifferentialTest.StubInvocation(HookId.of("request.hook"), 1L);
        Hook(CallbackExceptionTracker tracker) { wrapped = tracker.before(ignored -> { throw PROBLEM; }); }
        void fail() {
            try {
                wrapped.before(invocation);
                org.junit.Assert.fail("callback did not throw");
            } catch (Throwable thrown) {
                assertSame(PROBLEM, thrown);
            }
        }
    }

    /** Records periodic and one-shot submissions instead of running them. */
    private static final class ManualExecutor extends ScheduledThreadPoolExecutor {
        Runnable periodic;
        final List<Runnable> submitted = new ArrayList<Runnable>();
        RuntimeException rejection;
        boolean inline;
        ManualExecutor() { super(1); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                                    long delay, TimeUnit unit) {
            periodic = command;
            return new ManualFuture(command);
        }
        @Override public void execute(Runnable command) {
            if (rejection != null) throw rejection;
            if (inline) { command.run(); return; }
            synchronized (submitted) { submitted.add(command); }
        }
        Runnable take() {
            synchronized (submitted) { return submitted.remove(0); }
        }
        int pendingSubmissions() {
            synchronized (submitted) { return submitted.size(); }
        }
    }

    private static final class ManualFuture extends FutureTask<Object> implements RunnableScheduledFuture<Object> {
        ManualFuture(Runnable task) { super(task, null); }
        @Override public boolean isPeriodic() { return true; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }

    @Test public void requestsAreCoalescedAndRunOrdinaryEvaluations() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("request", 8);
        Hook hook = new Hook(tracker);
        List<Integer> batches = new ArrayList<Integer>();
        TraceBatchPoller poller = TraceBatchPoller.start(executor, tracker, events -> {
            batches.add(events.size());
            return events.size();
        }, 3, 8, 1, 1, TimeUnit.HOURS);
        try {
            hook.fail();
            assertTrue(poller.requestPoll());
            assertTrue(poller.requestPoll());
            assertTrue(poller.requestPoll());
            assertEquals("coalesced into one submission", 1, executor.pendingSubmissions());
            executor.take().run();
            // The ordinary count trigger (3) and observed wait (1 h) still apply: nothing is sent.
            assertTrue(batches.isEmpty());
            assertNull(poller.lastAttempt());
            hook.fail();
            hook.fail();
            assertTrue(poller.requestPoll());
            assertEquals(1, executor.pendingSubmissions());
            executor.take().run();
            assertEquals(java.util.Collections.singletonList(3), batches);
            assertEquals(3, poller.progress().acceptedRecords());
            assertEquals(0, tracker.pendingCount());
            assertNull(poller.lastFailure());
            assertEquals(TraceBatchPoller.State.ACTIVE, poller.state());
        } finally {
            poller.close();
            executor.shutdownNow();
        }
        assertFalse("closed poller accepts no requests", poller.requestPoll());
        assertEquals(0, executor.pendingSubmissions());
    }

    @Test public void requestDuringAnInFlightTickSchedulesOneFollowUpEvaluation() throws Throwable {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("rerun", 8);
        Hook hook = new Hook(tracker);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        TraceBatchPoller poller = TraceBatchPoller.start(executor, tracker, events -> {
            if (sends.getAndIncrement() == 0) {
                inside.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return events.size();
        }, 1, 8, 1, 0, TimeUnit.HOURS);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread periodic = new Thread(() -> {
            try { executor.periodic.run(); } catch (Throwable problem) { failure.set(problem); }
        }, "periodic-tick");
        try {
            hook.fail();
            periodic.start();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            // A record queued after the in-flight tick read the queue.
            hook.fail();
            assertTrue(poller.requestPoll());
            executor.take().run(); // finds the tick in flight: no concurrent send
            assertEquals(1, sends.get());
            assertEquals(0, executor.pendingSubmissions());
            release.countDown();
            periodic.join(5000);
            assertFalse(periodic.isAlive());
            assertNull(failure.get());
            assertEquals("follow-up evaluation submitted", 1, executor.pendingSubmissions());
            executor.take().run();
            assertEquals(2, sends.get());
            assertEquals(0, tracker.pendingCount());
            assertEquals(0, executor.pendingSubmissions());
        } finally {
            release.countDown();
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void inlineExecutionNeverRunsTheTransportOnTheRequestingThread() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("inline", 8);
        Hook hook = new Hook(tracker);
        List<Thread> senders = new ArrayList<Thread>();
        TraceBatchPoller poller = TraceBatchPoller.start(executor, tracker, events -> {
            senders.add(Thread.currentThread());
            return events.size();
        }, 1, 8, 1, 0, TimeUnit.HOURS);
        try {
            hook.fail();
            executor.inline = true;
            assertTrue(poller.requestPoll());
            assertTrue("inline request skipped", senders.isEmpty());
            assertEquals(1, tracker.pendingCount());
            // Re-armed: another request is submitted again rather than coalesced forever.
            executor.inline = false;
            assertTrue(poller.requestPoll());
            assertEquals(1, executor.pendingSubmissions());
            Thread worker = new Thread(() -> executor.take().run(), "request-worker");
            worker.start();
            worker.join(5000);
            assertEquals(1, senders.size());
            assertSame(worker, senders.get(0));
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void rejectionIsReportedWithoutSuspendingAndSuspensionRefusesRequests() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("rejected", 8);
        Hook hook = new Hook(tracker);
        RuntimeException broken = new IllegalStateException("transport failed");
        TraceBatchPoller poller = TraceBatchPoller.start(executor, tracker, events -> { throw broken; },
                1, 8, 1, 0, TimeUnit.HOURS);
        try {
            RejectedExecutionException rejected = new RejectedExecutionException("full");
            executor.rejection = rejected;
            assertFalse(poller.requestPoll());
            assertSame(rejected, poller.schedulingFailure());
            assertEquals(TraceBatchPoller.State.ACTIVE, poller.state());
            executor.rejection = null;
            assertTrue("re-armed after rejection", poller.requestPoll());
            hook.fail();
            executor.take().run();
            assertEquals(TraceBatchPoller.State.SUSPENDED, poller.state());
            assertSame(broken, poller.lastFailure());
            assertEquals(1, tracker.pendingCount());
            assertFalse(poller.requestPoll());
            assertEquals(0, executor.pendingSubmissions());
            assertTrue(poller.resume());
            assertTrue(poller.requestPoll());
            assertEquals(1, executor.pendingSubmissions());
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void captureSignalRunsOnlyForQueuedRecordsAndIsolatesItsFailures() {
        AtomicInteger signals = new AtomicInteger();
        AtomicReference<RuntimeException> signalFailure = new AtomicReference<RuntimeException>();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("signal", 2, () -> {
            signals.incrementAndGet();
            RuntimeException problem = signalFailure.get();
            if (problem != null) throw problem;
        });
        Hook hook = new Hook(tracker);
        hook.fail();
        assertEquals(1, signals.get());
        signalFailure.set(new IllegalStateException("signal failed"));
        hook.fail(); // the callback failure still propagates unchanged
        assertEquals(2, signals.get());
        assertEquals(1, tracker.trackingFailureCount());
        assertEquals(2, tracker.pendingCount());
        signalFailure.set(null);
        hook.fail(); // queue full: dropped, no signal
        assertEquals(1, tracker.droppedCount());
        assertEquals(2, signals.get());
        assertTrue(TestSupport.capture(() -> new CallbackExceptionTracker("signal", 1, null))
                instanceof NullPointerException);
    }

    static final class FatalSignal extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }

    @Test public void fatalSignalFailurePropagatesLikeOtherTrackingFailures() {
        FatalSignal fatal = new FatalSignal();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("fatal-signal", 2, () -> { throw fatal; });
        BeforeCallback wrapped = tracker.before(ignored -> { throw PROBLEM; });
        assertSame(fatal, TestSupport.capture(() -> wrapped.before(
                new TraceWireDifferentialTest.StubInvocation(HookId.of("fatal.signal"), 1L))));
        assertEquals(1, tracker.pendingCount());
    }

    @Test public void signalledPollerDeliversFromTheExecutorWithoutWaitingForThePeriod() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        AtomicReference<TraceBatchPoller> target = new AtomicReference<TraceBatchPoller>();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("signalled", 64, () -> {
            TraceBatchPoller poller = target.get();
            if (poller != null) poller.requestPoll();
        });
        Hook hook = new Hook(tracker);
        CountDownLatch delivered = new CountDownLatch(5);
        List<Thread> senders = new java.util.concurrent.CopyOnWriteArrayList<Thread>();
        try {
            // The first periodic tick runs immediately and finds nothing; the next is an hour away.
            target.set(TraceBatchPoller.start(executor, tracker, events -> {
                senders.add(Thread.currentThread());
                for (int i = 0; i < events.size(); i++) delivered.countDown();
                return events.size();
            }, 1, 32, 1, 0, TimeUnit.HOURS));
            Thread.sleep(50);
            for (int i = 0; i < 5; i++) hook.fail();
            assertTrue("delivered via requestPoll", delivered.await(5, TimeUnit.SECONDS));
            for (Thread sender : senders) assertNotSame(Thread.currentThread(), sender);
            assertEquals(5, target.get().progress().acceptedRecords());
        } finally {
            if (target.get() != null) target.get().close();
            executor.shutdownNow();
        }
    }
}
