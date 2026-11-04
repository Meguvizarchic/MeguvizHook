package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.TraceBatchPoller;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in deadline-accurate observed wait: TraceBatchPoller.WaitExpiry.DEADLINE. */
public final class TraceBatchPollerDeadlineTest {
    private static final RuntimeException PROBLEM = new RuntimeException("captured");
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    private static final class Hook {
        final BeforeCallback wrapped;
        final TraceWireDifferentialTest.StubInvocation invocation =
                new TraceWireDifferentialTest.StubInvocation(HookId.of("deadline.hook"), 1L);
        Hook(CallbackExceptionTracker tracker) { wrapped = tracker.before(ignored -> { throw PROBLEM; }); }
        void fail(int times) {
            for (int i = 0; i < times; i++) {
                try {
                    wrapped.before(invocation);
                    org.junit.Assert.fail("callback did not throw");
                } catch (Throwable thrown) {
                    assertSame(PROBLEM, thrown);
                }
            }
        }
    }

    private static final class ManualFuture extends FutureTask<Object> implements RunnableScheduledFuture<Object> {
        final Runnable task;
        final boolean periodic;
        final long delayNanos;
        ManualFuture(Runnable task, boolean periodic, long delayNanos) {
            super(task, null);
            this.task = task;
            this.periodic = periodic;
            this.delayNanos = delayNanos;
        }
        @Override public boolean isPeriodic() { return periodic; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(delayNanos, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) { return 0; }
        /** Runs the task as the executor would, ignoring cancellation (stale tasks must be no-ops). */
        void runTask() { task.run(); }
    }

    /** Records periodic, one-shot and immediate submissions instead of running them. */
    private static final class ManualExecutor extends ScheduledThreadPoolExecutor {
        ManualFuture periodic;
        final List<ManualFuture> oneShots = new ArrayList<ManualFuture>();
        final List<Runnable> submitted = new ArrayList<Runnable>();
        RuntimeException scheduleRejection;
        ManualExecutor() { super(1); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                                    long delay, TimeUnit unit) {
            periodic = new ManualFuture(command, true, unit.toNanos(delay));
            return periodic;
        }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (scheduleRejection != null) throw scheduleRejection;
            ManualFuture oneShot = new ManualFuture(command, false, unit.toNanos(delay));
            synchronized (oneShots) { oneShots.add(oneShot); }
            return oneShot;
        }
        @Override public void execute(Runnable command) {
            synchronized (submitted) { submitted.add(command); }
        }
        int oneShotCount() { synchronized (oneShots) { return oneShots.size(); } }
        ManualFuture oneShot(int index) { synchronized (oneShots) { return oneShots.get(index); } }
        int submittedCount() { synchronized (submitted) { return submitted.size(); } }
        Runnable takeSubmitted() { synchronized (submitted) { return submitted.remove(0); } }
    }

    private static final class Sink implements CallbackExceptionTracker.BatchTransport {
        final List<Integer> batches = Collections.synchronizedList(new ArrayList<Integer>());
        volatile RuntimeException failure;
        @Override public int sendBatch(List<CallbackExceptionTracker.Event> events) {
            if (failure != null) throw failure;
            batches.add(events.size());
            return events.size();
        }
    }

    private static TraceBatchPoller deadlinePoller(ManualExecutor executor, CallbackExceptionTracker tracker,
            CallbackExceptionTracker.BatchTransport transport, int trigger, int batchLimit, int drain,
            AtomicLong clock) {
        return TraceBatchPoller.startDraining(executor, tracker, transport, trigger, batchLimit, drain,
                1, 20, TimeUnit.MILLISECONDS, clock::get, TraceBatchPoller.WaitExpiry.DEADLINE);
    }

    @Test public void nextPollModeNeverSchedulesOneShots() throws Exception {
        for (int variant = 0; variant < 2; variant++) {
            ManualExecutor executor = new ManualExecutor();
            CallbackExceptionTracker tracker = new CallbackExceptionTracker("next-poll", 8);
            Hook hook = new Hook(tracker);
            Sink sink = new Sink();
            AtomicLong clock = new AtomicLong(1000 * MS);
            TraceBatchPoller poller = variant == 0
                    ? TraceBatchPoller.startDraining(executor, tracker, sink, 16, 8, 1, 1, 20,
                            TimeUnit.MILLISECONDS, clock::get)
                    : TraceBatchPoller.startDraining(executor, tracker, sink, 16, 8, 1, 1, 20,
                            TimeUnit.MILLISECONDS, clock::get, TraceBatchPoller.WaitExpiry.NEXT_POLL);
            try {
                hook.fail(1);
                executor.periodic.runTask();
                assertTrue(poller.requestPoll());
                executor.takeSubmitted().run();
                clock.addAndGet(19 * MS);
                executor.periodic.runTask();
                assertTrue(sink.batches.isEmpty());
                clock.addAndGet(MS);
                executor.periodic.runTask();
                assertEquals(Collections.singletonList(1), sink.batches);
                assertEquals("no one-shot in NEXT_POLL mode", 0, executor.oneShotCount());
            } finally {
                poller.close();
                executor.shutdownNow();
            }
        }
        assertTrue(TestSupport.capture(() -> TraceBatchPoller.startDraining(new ManualExecutor(),
                new CallbackExceptionTracker("null-expiry", 1), new Sink(), 1, 1, 1, 1, 0, TimeUnit.SECONDS,
                System::nanoTime, null)) instanceof NullPointerException);
    }

    @Test public void deadlineArmsOncePerWindowForTheRemainingWait() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("arm-once", 8);
        Hook hook = new Hook(tracker);
        Sink sink = new Sink();
        AtomicLong clock = new AtomicLong(1000 * MS);
        TraceBatchPoller poller = deadlinePoller(executor, tracker, sink, 16, 8, 1, clock);
        try {
            executor.periodic.runTask(); // empty queue: nothing to arm
            assertEquals(0, executor.oneShotCount());
            hook.fail(1);
            clock.addAndGet(3 * MS);
            executor.periodic.runTask(); // observes the window at 1003 ms
            assertEquals(1, executor.oneShotCount());
            assertEquals(20 * MS, executor.oneShot(0).delayNanos);
            assertFalse(executor.oneShot(0).isPeriodic());
            clock.addAndGet(7 * MS);
            executor.periodic.runTask(); // still pending: no second one-shot
            assertTrue(poller.requestPoll());
            executor.takeSubmitted().run();
            assertEquals(1, executor.oneShotCount());
            assertTrue(sink.batches.isEmpty());
            clock.set(1023 * MS);
            executor.oneShot(0).runTask(); // an ordinary evaluation at the expiry sends
            assertEquals(Collections.singletonList(1), sink.batches);
            assertEquals(1, poller.progress().acceptedRecords());
            executor.periodic.runTask();
            assertEquals("the closed window arms nothing", 1, executor.oneShotCount());
            assertEquals(0, executor.submittedCount());
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void requestedEvaluationStartsTheWindowAndItsDeadline() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<TraceBatchPoller> target = new AtomicReference<TraceBatchPoller>();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("signal-deadline", 8, () -> {
            TraceBatchPoller poller = target.get();
            if (poller != null) poller.requestPoll();
        });
        Hook hook = new Hook(tracker);
        Sink sink = new Sink();
        AtomicLong clock = new AtomicLong(500 * MS);
        target.set(deadlinePoller(executor, tracker, sink, 16, 8, 1, clock));
        try {
            executor.periodic.runTask();
            clock.addAndGet(MS);
            hook.fail(1); // the signal submits an evaluation
            assertEquals(1, executor.submittedCount());
            executor.takeSubmitted().run(); // observes at 501 ms
            assertEquals(1, executor.oneShotCount());
            assertEquals(20 * MS, executor.oneShot(0).delayNanos);
            clock.set(521 * MS);
            executor.oneShot(0).runTask();
            assertEquals(Collections.singletonList(1), sink.batches);
        } finally {
            target.get().close();
            executor.shutdownNow();
        }
    }

    @Test public void staleOneShotRunsOnceAndArmsTheNextWindow() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("stale", 32);
        Hook hook = new Hook(tracker);
        Sink sink = new Sink();
        AtomicLong clock = new AtomicLong(0);
        TraceBatchPoller poller = deadlinePoller(executor, tracker, sink, 4, 8, 1, clock);
        try {
            hook.fail(1);
            executor.periodic.runTask(); // window 1 at 0 ms, deadline 20 ms
            assertEquals(1, executor.oneShotCount());
            hook.fail(3);
            clock.set(2 * MS);
            executor.periodic.runTask(); // count trigger: sends 4, window 1 closes
            assertEquals(Collections.singletonList(4), sink.batches);
            hook.fail(1);
            clock.set(5 * MS);
            executor.periodic.runTask(); // window 2 at 5 ms; window 1's one-shot is still pending
            assertEquals("never two pending one-shots", 1, executor.oneShotCount());
            clock.set(20 * MS);
            executor.oneShot(0).runTask(); // stale: window 2 has not expired, so it arms 25 ms
            assertEquals(Collections.singletonList(4), sink.batches);
            assertEquals(2, executor.oneShotCount());
            assertEquals(5 * MS, executor.oneShot(1).delayNanos);
            clock.set(25 * MS);
            executor.oneShot(1).runTask();
            assertEquals(Arrays.asList(4, 1), sink.batches);
            assertEquals(2, executor.oneShotCount());
            assertFalse(executor.oneShot(0).isCancelled()); // windows closing never cancel
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void oneShotThatRunsBeforeTheClockExpiresIsNotRearmed() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("lagging-clock", 8);
        Hook hook = new Hook(tracker);
        Sink sink = new Sink();
        AtomicLong clock = new AtomicLong(0);
        TraceBatchPoller poller = deadlinePoller(executor, tracker, sink, 16, 8, 1, clock);
        try {
            hook.fail(1);
            executor.periodic.runTask();
            clock.set(19 * MS); // the supplied clock lags the executor's
            executor.oneShot(0).runTask();
            executor.periodic.runTask();
            assertTrue(sink.batches.isEmpty());
            assertEquals("one-shot per window: no spin on a lagging clock", 1, executor.oneShotCount());
            clock.set(20 * MS);
            executor.periodic.runTask(); // the periodic tick is the fallback
            assertEquals(Collections.singletonList(1), sink.batches);
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void oneShotDuringAnInFlightEvaluationLeadsToOneFollowUp() throws Throwable {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("in-flight", 8);
        Hook hook = new Hook(tracker);
        Sink sink = new Sink();
        AtomicLong time = new AtomicLong(0);
        CountDownLatch inClock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger blockOnCall = new AtomicInteger(-1);
        AtomicInteger calls = new AtomicInteger();
        TraceBatchPoller.Clock clock = () -> {
            if (calls.getAndIncrement() == blockOnCall.get()) {
                inClock.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
            }
            return time.get();
        };
        TraceBatchPoller poller = TraceBatchPoller.startDraining(executor, tracker, sink, 3, 1, 1, 1, 20,
                TimeUnit.MILLISECONDS, clock, TraceBatchPoller.WaitExpiry.DEADLINE);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        try {
            hook.fail(1);
            executor.periodic.runTask(); // arms 20 ms
            assertEquals(1, executor.oneShotCount());
            time.set(19 * MS);
            blockOnCall.set(calls.get());
            Thread periodic = new Thread(() -> {
                try { executor.periodic.runTask(); } catch (Throwable problem) { failure.set(problem); }
            }, "in-flight-tick");
            periodic.start();
            assertTrue(inClock.await(5, TimeUnit.SECONDS));
            executor.oneShot(0).runTask(); // finds the tick in flight
            assertTrue(sink.batches.isEmpty());
            release.countDown(); // the in-flight tick read 19 ms: not expired
            periodic.join(5000);
            assertFalse(periodic.isAlive());
            assertNull(failure.get());
            assertTrue(sink.batches.isEmpty());
            assertEquals("follow-up submitted", 1, executor.submittedCount());
            assertEquals("no second one-shot for the same window", 1, executor.oneShotCount());
            time.set(20 * MS);
            executor.takeSubmitted().run();
            assertEquals(Collections.singletonList(1), sink.batches);
            // The follow-up did not opt into requestPoll's backlog behaviour: a capped tick
            // with records left still waits for the next period.
            hook.fail(3);
            executor.periodic.runTask();
            assertEquals(Arrays.asList(1, 1), sink.batches);
            assertEquals(2, tracker.pendingCount());
            assertEquals(0, executor.submittedCount());
        } finally {
            release.countDown();
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void suspensionAndCloseCancelThePendingOneShot() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("cancel", 32);
        Hook hook = new Hook(tracker);
        Sink sink = new Sink();
        AtomicLong clock = new AtomicLong(0);
        TraceBatchPoller poller = deadlinePoller(executor, tracker, sink, 4, 8, 1, clock);
        try {
            hook.fail(1);
            executor.periodic.runTask();
            ManualFuture first = executor.oneShot(0);
            RuntimeException broken = new IllegalStateException("transport failed");
            sink.failure = broken;
            hook.fail(3);
            executor.periodic.runTask(); // count trigger, transport fails
            assertEquals(TraceBatchPoller.State.SUSPENDED, poller.state());
            assertSame(broken, poller.lastFailure());
            assertTrue("suspension cancels the one-shot", first.isCancelled());
            first.runTask(); // a stale run after suspension does nothing
            assertTrue(sink.batches.isEmpty());
            assertEquals(4, tracker.pendingCount());
            sink.failure = null;
            assertTrue(poller.resume());
            hook.fail(24); // 28 pending: one capped batch of 8 leaves the window open
            clock.set(MS);
            executor.periodic.runTask(); // resumed: sends 8 (batch limit), records remain, window open
            assertEquals(Collections.singletonList(8), sink.batches);
            assertEquals(2, executor.oneShotCount());
            ManualFuture second = executor.oneShot(1);
            poller.close();
            assertTrue("close cancels the one-shot", second.isCancelled());
            second.runTask();
            assertEquals(Collections.singletonList(8), sink.batches);
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void rejectedScheduleIsReportedWithoutSuspendingAndNotRetriedForTheWindow() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("rejected", 8);
        Hook hook = new Hook(tracker);
        Sink sink = new Sink();
        AtomicLong clock = new AtomicLong(0);
        TraceBatchPoller poller = deadlinePoller(executor, tracker, sink, 16, 8, 1, clock);
        try {
            RejectedExecutionException rejected = new RejectedExecutionException("full");
            executor.scheduleRejection = rejected;
            hook.fail(1);
            executor.periodic.runTask();
            assertSame(rejected, poller.schedulingFailure());
            assertEquals(TraceBatchPoller.State.ACTIVE, poller.state());
            executor.scheduleRejection = null;
            clock.set(5 * MS);
            executor.periodic.runTask();
            assertEquals("the rejected window relies on the periodic tick", 0, executor.oneShotCount());
            clock.set(20 * MS);
            executor.periodic.runTask();
            assertEquals(Collections.singletonList(1), sink.batches);
            hook.fail(1);
            clock.set(30 * MS);
            executor.periodic.runTask(); // a new window arms again
            assertEquals(1, executor.oneShotCount());
            assertEquals(20 * MS, executor.oneShot(0).delayNanos);
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void oneShotDelayCountsFromAfterTheSends() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("after-send", 8);
        Hook hook = new Hook(tracker);
        AtomicLong clock = new AtomicLong(0);
        List<Integer> batches = new ArrayList<Integer>();
        TraceBatchPoller poller = TraceBatchPoller.startDraining(executor, tracker, events -> {
            clock.addAndGet(5 * MS); // a slow synchronous transport
            batches.add(events.size());
            return events.size();
        }, 3, 1, 1, 1, 20, TimeUnit.MILLISECONDS, clock::get, TraceBatchPoller.WaitExpiry.DEADLINE);
        try {
            hook.fail(3);
            executor.periodic.runTask(); // count trigger at 0 ms: one capped batch, window stays open
            assertEquals(Collections.singletonList(1), batches);
            assertEquals(1, executor.oneShotCount());
            assertEquals("remaining wait after the 5 ms send", 15 * MS, executor.oneShot(0).delayNanos);
        } finally {
            poller.close();
            executor.shutdownNow();
        }
    }

    @Test public void oneShotSwallowedBySendingEvaluationStillFollowsUp() throws Throwable {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("swallowed", 8);
        Hook hook = new Hook(tracker);
        AtomicLong clock = new AtomicLong(0);
        CountDownLatch inSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> batches = Collections.synchronizedList(new ArrayList<Integer>());
        TraceBatchPoller poller = TraceBatchPoller.startDraining(executor, tracker, events -> {
            if (batches.isEmpty()) {
                inSend.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                clock.set(25 * MS); // the deadline passes during this send
            }
            batches.add(events.size());
            return events.size();
        }, 3, 1, 1, 1, 20, TimeUnit.MILLISECONDS, clock::get, TraceBatchPoller.WaitExpiry.DEADLINE);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        try {
            hook.fail(1);
            executor.periodic.runTask(); // window at 0 ms, one-shot for 20 ms
            assertEquals(1, executor.oneShotCount());
            hook.fail(2);
            clock.set(19 * MS);
            Thread periodic = new Thread(() -> {
                try { executor.periodic.runTask(); } catch (Throwable problem) { failure.set(problem); }
            }, "sending-tick");
            periodic.start(); // count trigger: sends one capped batch
            assertTrue(inSend.await(5, TimeUnit.SECONDS));
            executor.oneShot(0).runTask(); // swallowed: the tick is in flight
            release.countDown();
            periodic.join(5000);
            assertNull(failure.get());
            assertEquals(Collections.singletonList(1), batches);
            assertEquals(2, tracker.pendingCount());
            assertEquals("follow-up for the swallowed one-shot", 1, executor.submittedCount());
            executor.takeSubmitted().run(); // the window has expired: sends
            assertEquals(Arrays.asList(1, 1), batches);
        } finally {
            release.countDown();
            poller.close();
            executor.shutdownNow();
        }
    }

    /**
     * resume() makes the poller ACTIVE before the next generation starts. An evaluation that runs
     * in between can arm a one-shot under the old ticket; the new generation must not inherit its
     * slot, which would silently turn DEADLINE into NEXT_POLL. Races resume against a requesting
     * evaluator and, after every cycle, checks (white-box) that a marked slot has a live handle.
     */
    @Test public void resumeRacingAnEvaluationNeverLeavesTheOneShotSlotStuck() throws Throwable {
        ManualExecutor executor = new ManualExecutor();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("resume-race", 8);
        new Hook(tracker).fail(1);
        java.util.concurrent.atomic.AtomicBoolean clockFails = new java.util.concurrent.atomic.AtomicBoolean();
        TraceBatchPoller poller = TraceBatchPoller.startDraining(executor, tracker, events -> events.size(),
                16, 8, 1, 1, 20, TimeUnit.HOURS, () -> {
                    if (clockFails.get()) throw new IllegalStateException("suspend via the clock");
                    return 1000 * MS;
                }, TraceBatchPoller.WaitExpiry.DEADLINE);
        java.lang.reflect.Field pendingField = TraceBatchPoller.class.getDeclaredField("deadlinePending");
        java.lang.reflect.Field futureField = TraceBatchPoller.class.getDeclaredField("deadlineFuture");
        pendingField.setAccessible(true);
        futureField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean pause = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicLong evaluations = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread evaluator = new Thread(() -> {
            try {
                while (!stop.get()) {
                    if (pause.get()) {
                        paused.set(true);
                        while (pause.get() && !stop.get()) Thread.yield();
                        paused.set(false);
                        continue;
                    }
                    if (poller.requestPoll()) {
                        Runnable task;
                        synchronized (executor.submitted) {
                            task = executor.submitted.isEmpty() ? null : executor.submitted.remove(0);
                        }
                        if (task != null) {
                            task.run();
                            evaluations.incrementAndGet();
                        }
                    }
                }
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            }
        }, "racing-evaluator");
        evaluator.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int cycles = 0;
        try {
            for (; cycles < 4000 && System.nanoTime() < deadline; cycles++) {
                clockFails.set(true);
                while (poller.state() == TraceBatchPoller.State.ACTIVE) {
                    poller.requestPoll();
                    Runnable task;
                    synchronized (executor.submitted) {
                        task = executor.submitted.isEmpty() ? null : executor.submitted.remove(0);
                    }
                    if (task != null) task.run(); // the failing clock suspends the poller
                    if (failure.get() != null) throw failure.get();
                }
                clockFails.set(false);
                while (!poller.resume()) Thread.yield(); // races the evaluator's requested evaluation
                long seen = evaluations.get();
                long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20);
                while (evaluations.get() < seen + 2 && System.nanoTime() < settle) Thread.yield();
                pause.set(true);
                while (!paused.get() && evaluator.isAlive()) Thread.yield();
                boolean pending;
                Object handle;
                synchronized (poller) {
                    pending = (Boolean) pendingField.get(poller);
                    handle = futureField.get(poller);
                }
                assertFalse("cycle " + cycles + ": one-shot slot marked pending without a handle",
                        pending && handle == null);
                pause.set(false);
            }
        } finally {
            stop.set(true);
            evaluator.join(5000);
        }
        assertNull(failure.get());
        assertTrue("cycles " + cycles, cycles > 200);
        poller.close();
        executor.shutdownNow();
    }

    @Test public void singleRecordIsDeliveredAtTheObservedWaitOnARealScheduler() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        AtomicReference<TraceBatchPoller> target = new AtomicReference<TraceBatchPoller>();
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("real-deadline", 16, () -> {
            TraceBatchPoller poller = target.get();
            if (poller != null) poller.requestPoll();
        });
        Hook hook = new Hook(tracker);
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            // The periodic tick is an hour away: only the one-shot can deliver in time.
            target.set(TraceBatchPoller.startDraining(executor, tracker, events -> {
                delivered.countDown();
                return events.size();
            }, 16, 16, 1, TimeUnit.HOURS.toMillis(1), 100, TimeUnit.MILLISECONDS,
                    System::nanoTime, TraceBatchPoller.WaitExpiry.DEADLINE));
            Thread.sleep(20);
            long start = System.nanoTime();
            hook.fail(1);
            assertTrue("delivered by the deadline evaluation", delivered.await(10, TimeUnit.SECONDS));
            long elapsed = System.nanoTime() - start;
            assertTrue("not before the observed wait: " + elapsed, elapsed >= 100 * MS);
            assertTrue(executor.getQueue().size() <= 2);
        } finally {
            if (target.get() != null) target.get().close();
            executor.shutdownNow();
        }
    }

    /**
     * With an hour-long period and an unreachable count trigger, only deadline evaluations can
     * deliver. Producers pause at random so windows open and close continually. The executor
     * queue must stay bounded (periodic task, at most one one-shot, at most one request).
     */
    @Test public void deadlinesLoseNoRecordAndKeepTheQueueBounded() throws Throwable {
        for (int threads : new int[] {1, 4}) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads);
            AtomicReference<TraceBatchPoller> target = new AtomicReference<TraceBatchPoller>();
            CallbackExceptionTracker tracker = new CallbackExceptionTracker("deadline-stress", 8192, () -> {
                TraceBatchPoller poller = target.get();
                if (poller != null) poller.requestPoll();
            });
            AtomicInteger delivered = new AtomicInteger();
            AtomicInteger maxQueue = new AtomicInteger();
            int producers = 4;
            int perProducer = 400;
            List<Thread> workers = new ArrayList<Thread>();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            try {
                target.set(TraceBatchPoller.startDraining(executor, tracker, events -> {
                    delivered.addAndGet(events.size());
                    return events.size();
                }, 100000, 64, 2, TimeUnit.HOURS.toMillis(1), 2, TimeUnit.MILLISECONDS,
                        System::nanoTime, TraceBatchPoller.WaitExpiry.DEADLINE));
                for (int p = 0; p < producers; p++) {
                    workers.add(new Thread(() -> {
                        Hook hook = new Hook(tracker);
                        java.util.Random pause = new java.util.Random();
                        try {
                            for (int i = 0; i < perProducer; i++) {
                                hook.fail(1);
                                maxQueue.accumulateAndGet(executor.getQueue().size(), Math::max);
                                if (pause.nextInt(8) == 0) Thread.sleep(pause.nextInt(4));
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        }
                    }, "deadline-producer-" + p));
                }
                for (Thread worker : workers) worker.start();
                for (Thread worker : workers) worker.join(60000);
                if (failure.get() != null) throw failure.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                int expected = producers * perProducer - (int) tracker.droppedCount();
                while (delivered.get() < expected && System.nanoTime() < deadline) {
                    maxQueue.accumulateAndGet(executor.getQueue().size(), Math::max);
                    Thread.sleep(1);
                }
                assertEquals("executor threads " + threads, expected, delivered.get());
                assertEquals(0, tracker.pendingCount());
                assertNull(target.get().lastFailure());
                assertNull(target.get().schedulingFailure());
                assertTrue("queue bounded: " + maxQueue.get(), maxQueue.get() <= 3);
            } finally {
                if (target.get() != null) target.get().close();
                executor.shutdownNow();
            }
        }
    }
}
