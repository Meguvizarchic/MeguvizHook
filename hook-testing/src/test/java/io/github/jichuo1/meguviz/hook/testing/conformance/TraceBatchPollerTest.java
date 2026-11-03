package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.BoundedTraceCompression;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import io.github.jichuo1.meguviz.hook.core.TraceBatchPoller;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

public final class TraceBatchPollerTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void statelessCompressedBatchCanRunOnAnotherWorkerAndBeDecoded() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        TraceBatchPoller poller = null;
        Thread worker = null;
        try {
            f.add();
            f.add();
            List<byte[]> records = new ArrayList<byte[]>();
            AtomicReference<Thread> receiverThread = new AtomicReference<Thread>();
            AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
            BoundedTraceCompression codec = new BoundedTraceCompression(16384, 16404, 1, 1,
                    BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
            JsonLineTraceTransport.RecordWriter receiver = BatchTraceTransport.compressedReceiver(codec, batch -> {
                receiverThread.set(Thread.currentThread());
                records.addAll(batch);
            }, 16384, 32);
            CallbackExceptionTracker.BatchTransport sender = codec.batchSender(receiver, false, 0, 16384, 32);
            poller = TraceBatchPoller.start(scheduler, f.tracker, sender, 1, 32, 1, 0, TimeUnit.SECONDS);
            worker = new Thread(() -> {
                try { scheduler.fire(); } catch (Throwable failure) { workerFailure.set(failure); }
            }, "stateless-compressed-poller");
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(workerFailure.get());
            assertSame(worker, receiverThread.get());
            assertEquals(TraceBatchPoller.State.ACTIVE, poller.state());
            assertNull(poller.lastFailure());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, poller.lastAttempt().status());
            assertEquals(2, poller.lastAttempt().acceptedCount());
            assertEquals(0, f.tracker.pendingCount());
            assertEquals(2, records.size());
            for (byte[] record : records) {
                Map<?, ?> json = (Map<?, ?>) TraceJsonParser.parse(new String(record, StandardCharsets.UTF_8));
                assertEquals("poller", json.get("source"));
                assertEquals("poller.trace", json.get("hook"));
                assertEquals("BEFORE", json.get("stage"));
                assertFalse(json.containsKey("message"));
            }
        } finally {
            if (worker != null) worker.join(5000);
            if (poller != null) poller.close();
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void crossThreadSessionSuspendsAndKeepsUnconfirmedHead() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        BoundedTraceCompression codec = new BoundedTraceCompression(16384, 16404, 1, 1,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        BoundedTraceCompression.Session session = codec.newSession();
        TraceBatchPoller poller = null;
        Thread worker = null;
        try {
            f.add();
            CallbackExceptionTracker.Event head = f.tracker.peekNext();
            AtomicInteger writes = new AtomicInteger();
            AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
            CallbackExceptionTracker.BatchTransport sender = session.batchSender(
                    bytes -> writes.incrementAndGet(), false, 0, 16384, 32);
            poller = TraceBatchPoller.start(scheduler, f.tracker, sender, 1, 32, 1, 0, TimeUnit.SECONDS);
            worker = new Thread(() -> {
                try { scheduler.fire(); } catch (Throwable failure) { workerFailure.set(failure); }
            }, "wrong-session-owner-poller");
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(workerFailure.get());
            assertEquals(TraceBatchPoller.State.SUSPENDED, poller.state());
            assertTrue(poller.lastFailure() instanceof IllegalStateException);
            assertNull(poller.lastAttempt());
            assertEquals(0, writes.get());
            assertEquals(1, f.tracker.pendingCount());
            assertSame(head, f.tracker.peekNext());
        } finally {
            if (worker != null) worker.join(5000);
            if (poller != null) poller.close();
            session.close();
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void serializedSessionPollerMigratesWorkersAndDecodesBothBatches() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        BoundedTraceCompression codec = new BoundedTraceCompression(16384, 16404, 1, 1,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        BoundedTraceCompression.SerializedSession session = codec.newSerializedSession();
        TraceBatchPoller poller = null;
        Thread first = null;
        Thread second = null;
        try {
            f.add();
            List<byte[]> records = new ArrayList<byte[]>();
            List<Thread> receiverThreads = new ArrayList<Thread>();
            AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
            JsonLineTraceTransport.RecordWriter receiver = BatchTraceTransport.compressedSerializedReceiver(
                    session, batch -> { receiverThreads.add(Thread.currentThread()); records.addAll(batch); },
                    16384, 32);
            CallbackExceptionTracker.BatchTransport sender = session.batchSender(receiver, false, 0, 16384, 32);
            poller = TraceBatchPoller.start(scheduler, f.tracker, sender, 1, 1, 1, 0, TimeUnit.SECONDS);
            first = new Thread(() -> {
                try { scheduler.fire(); } catch (Throwable failure) { workerFailure.set(failure); }
            }, "serialized-poller-first");
            first.start();
            first.join(5000);
            assertFalse(first.isAlive());
            assertNull(workerFailure.get());
            assertEquals(0, f.tracker.pendingCount());
            assertEquals(1, poller.progress().acceptedRecords());

            f.add();
            second = new Thread(() -> {
                try { scheduler.fire(); } catch (Throwable failure) { workerFailure.set(failure); }
            }, "serialized-poller-second");
            second.start();
            second.join(5000);
            assertFalse(second.isAlive());
            assertNull(workerFailure.get());
            assertEquals(TraceBatchPoller.State.ACTIVE, poller.state());
            assertNull(poller.lastFailure());
            assertEquals(0, f.tracker.pendingCount());
            assertEquals(2, poller.progress().acceptedBatches());
            assertEquals(2, poller.progress().acceptedRecords());
            assertEquals(2, records.size());
            assertSame(first, receiverThreads.get(0));
            assertSame(second, receiverThreads.get(1));
            assertNotSame(first, second);
            assertNotSame(records.get(0), records.get(1));
            for (byte[] record : records) {
                Map<?, ?> json = (Map<?, ?>) TraceJsonParser.parse(new String(record, StandardCharsets.UTF_8));
                assertEquals("poller.trace", json.get("hook"));
                assertEquals("poller", json.get("source"));
            }
            byte[] secondRecord = records.get(1).clone();
            records.get(0)[0] ^= 1;
            assertArrayEquals(secondRecord, records.get(1));
            poller.close();
            scheduler.fire();
            assertEquals(2, records.size());
        } finally {
            if (first != null) first.join(5000);
            if (second != null) second.join(5000);
            if (poller != null) poller.close();
            session.close();
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void countAndObservedWaitTriggerAtMostOneBatchPerTick() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            AtomicInteger sends = new AtomicInteger();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker,
                    events -> { sends.incrementAndGet(); return 1; }, 2, 1, 1, 1, TimeUnit.DAYS);
            scheduler.fire();
            assertEquals(0, sends.get());
            f.add();
            scheduler.fire();
            assertEquals(1, sends.get());
            assertEquals(1, f.tracker.pendingCount());
            assertEquals(1, p.lastAttempt().acceptedCount());
            p.close();

            TraceBatchPoller low = TraceBatchPoller.start(scheduler, f.tracker,
                    events -> { sends.incrementAndGet(); return 1; }, 4, 2, 1, 0, TimeUnit.DAYS);
            scheduler.fire();
            assertEquals(2, sends.get());
            assertEquals(0, f.tracker.pendingCount());
            low.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void drainingIsBoundedAndDefaultRemainsSingleBatch() throws Throwable {
        Fixture f = new Fixture(10);
        ManualScheduler scheduler = new ManualScheduler();
        try {
            for (int i = 0; i < 10; i++) f.add();
            List<CallbackExceptionTracker.Event> delivered = new ArrayList<CallbackExceptionTracker.Event>();
            CallbackExceptionTracker.BatchTransport transport = events -> {
                delivered.addAll(events);
                return events.size();
            };
            TraceBatchPoller single = TraceBatchPoller.start(scheduler, f.tracker, transport,
                    1, 2, 1, 0, TimeUnit.SECONDS);
            scheduler.fire();
            assertEquals(8, f.tracker.pendingCount());
            assertEquals(1, single.progress().acceptedBatches());
            assertEquals(2, single.progress().acceptedRecords());
            single.close();

            TraceBatchPoller draining = TraceBatchPoller.startDraining(scheduler, f.tracker, transport,
                    1, 2, 3, 1, 0, TimeUnit.SECONDS);
            scheduler.fire();
            assertEquals(2, f.tracker.pendingCount());
            TraceBatchPoller.DeliveryProgress progress = draining.progress();
            assertEquals(3, progress.acceptedBatches());
            assertEquals(6, progress.acceptedRecords());
            scheduler.fire();
            assertEquals(0, f.tracker.pendingCount());
            assertEquals(4, draining.progress().acceptedBatches());
            assertEquals(8, draining.progress().acceptedRecords());
            assertEquals(10, delivered.size());
            assertEquals(10, new java.util.HashSet<CallbackExceptionTracker.Event>(delivered).size());
            // The earlier immutable snapshot is not updated by subsequent ticks.
            assertEquals(6, progress.acceptedRecords());
            draining.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void drainingYieldsAtLimitWhileProducerKeepsAppending() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            f.add();
            List<CallbackExceptionTracker.Event> accepted = new ArrayList<CallbackExceptionTracker.Event>();
            AtomicInteger calls = new AtomicInteger();
            TraceBatchPoller p = TraceBatchPoller.startDraining(scheduler, f.tracker, events -> {
                calls.incrementAndGet();
                assertEquals(1, events.size());
                accepted.add(events.get(0));
                try { f.add(); } catch (Throwable failure) { throw new AssertionError(failure); }
                return 1;
            }, 1, 1, 3, 1, 0, TimeUnit.SECONDS);
            scheduler.fire();
            assertEquals(3, calls.get());
            assertEquals(3, p.progress().acceptedBatches());
            assertEquals(3, p.progress().acceptedRecords());
            assertEquals(2, f.tracker.pendingCount());
            assertEquals(0, f.tracker.droppedCount());
            p.close();
            List<CallbackExceptionTracker.Event> remaining = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(2, f.tracker.deliver(remaining::add, 4));
            accepted.addAll(remaining);
            assertEquals(5, new java.util.HashSet<CallbackExceptionTracker.Event>(accepted).size());
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void drainingKeepsAcknowledgedPrefixWhenLaterBatchFails() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            for (int i = 0; i < 4; i++) f.add();
            Exception failure = new Exception("second batch failed");
            AtomicInteger calls = new AtomicInteger();
            List<CallbackExceptionTracker.Event> first = new ArrayList<CallbackExceptionTracker.Event>();
            TraceBatchPoller p = TraceBatchPoller.startDraining(scheduler, f.tracker, events -> {
                int call = calls.incrementAndGet();
                if (call == 1) { first.addAll(events); return 1; }
                if (call == 2) { assertSame(first.get(1), events.get(0)); throw failure; }
                return events.size();
            }, 1, 2, 3, 1, 0, TimeUnit.SECONDS);
            Runnable stale = scheduler.task;
            scheduler.fire();
            assertEquals(TraceBatchPoller.State.SUSPENDED, p.state());
            assertSame(failure, p.lastFailure());
            assertEquals(3, f.tracker.pendingCount());
            assertEquals(1, p.progress().acceptedBatches());
            assertEquals(1, p.progress().acceptedRecords());
            stale.run();
            assertEquals(2, calls.get());
            assertTrue(p.resume());
            scheduler.fire();
            assertEquals(0, f.tracker.pendingCount());
            assertEquals(3, p.progress().acceptedBatches());
            assertEquals(4, p.progress().acceptedRecords());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void drainingStopsImmediatelyWhenAnotherDeliveryIsBusy() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread external = null;
        try {
            f.add();
            external = new Thread(() -> {
                try {
                    f.tracker.tryDeliverBatch(events -> {
                        inside.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                        return 1;
                    }, 1);
                } catch (Throwable problem) { failure.set(problem); }
            });
            external.start();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            AtomicInteger calls = new AtomicInteger();
            TraceBatchPoller p = TraceBatchPoller.startDraining(scheduler, f.tracker,
                    events -> { calls.incrementAndGet(); return 1; }, 1, 1, 3, 1, 0, TimeUnit.SECONDS);
            scheduler.fire();
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.BUSY, p.lastAttempt().status());
            assertEquals(0, calls.get());
            assertEquals(0, p.progress().acceptedBatches());
            assertEquals(1, f.tracker.pendingCount());
            release.countDown();
            external.join(5000);
            assertFalse(external.isAlive());
            assertNull(failure.get());
            p.close();
        } finally {
            release.countDown();
            if (external != null) external.join(5000);
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void closeDuringFirstDrainedBatchDoesNotClaimNext() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = null;
        try {
            for (int i = 0; i < 4; i++) f.add();
            TraceBatchPoller p = TraceBatchPoller.startDraining(scheduler, f.tracker, events -> {
                calls.incrementAndGet();
                inside.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                return 1;
            }, 1, 1, 4, 1, 0, TimeUnit.SECONDS);
            worker = new Thread(() -> {
                try { scheduler.fire(); } catch (Throwable error) { failure.set(error); }
            });
            worker.start();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            p.close();
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(1, calls.get());
            assertEquals(3, f.tracker.pendingCount());
            assertEquals(1, p.progress().acceptedBatches());
            assertEquals(1, p.progress().acceptedRecords());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, p.lastAttempt().status());
        } finally {
            release.countDown();
            if (worker != null) worker.join(5000);
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void observedEmptyAfterDeliveryStartsNewWaitForNextBatch() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            AtomicLong clock = new AtomicLong();
            AtomicInteger sends = new AtomicInteger();
            f.add();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> {
                sends.incrementAndGet();
                return 1;
            }, 2, 1, 1, 100, TimeUnit.NANOSECONDS, clock::get);
            scheduler.fire();
            clock.set(100);
            scheduler.fire();
            assertEquals(1, sends.get());
            f.add();
            clock.set(101);
            scheduler.fire();
            assertEquals(1, sends.get());
            clock.set(201);
            scheduler.fire();
            assertEquals(2, sends.get());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void failureSuspendsAndOnlyExplicitResumeRetries() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            Exception failure = new Exception("remote side effect unknown");
            AtomicInteger calls = new AtomicInteger();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> {
                if (calls.incrementAndGet() == 1) throw failure;
                return 1;
            }, 1, 1, 1, 0, TimeUnit.SECONDS);
            Runnable stale = scheduler.task;
            scheduler.fire();
            assertEquals(TraceBatchPoller.State.SUSPENDED, p.state());
            assertSame(failure, p.lastFailure());
            assertEquals(1, f.tracker.pendingCount());
            stale.run();
            assertEquals(1, calls.get());
            assertTrue(p.resume());
            stale.run();
            scheduler.fire();
            assertEquals(2, calls.get());
            assertEquals(0, f.tracker.pendingCount());
            assertSame(failure, p.lastFailure());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void closeDuringSendKeepsOutcomeVisibleAndPreventsNewClaims() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = null;
        try {
            f.add();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                return 1;
            }, 1, 1, 1, 0, TimeUnit.SECONDS);
            Runnable task = scheduler.task;
            worker = new Thread(task, "poller-inflight-test");
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(p.inFlight());
            scheduler.latest.cancel(false);
            TraceBatchPoller.ScheduleObservation observation = p.scheduleObservation();
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.DONE, observation.status());
            assertTrue(observation.inFlight());
            p.close();
            assertEquals(TraceBatchPoller.State.CLOSED, p.state());
            assertNull(pollerField(p, "tracker"));
            assertNull(pollerField(p, "transport"));
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, p.lastAttempt().status());
            assertEquals(0, f.tracker.pendingCount());
            f.add();
            task.run();
            assertEquals(1, f.tracker.pendingCount());
            assertFalse(p.resume());
        } finally {
            release.countDown();
            if (worker != null) worker.join(5000);
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void claimedTickKeepsItsInputsWhenClosedBeforeTransportStarts() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch clockEntered = new CountDownLatch(1);
        CountDownLatch releaseClock = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = null;
        try {
            f.add();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS, () -> {
                        clockEntered.countDown();
                        try {
                            if (!releaseClock.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        return 1;
                    });
            worker = new Thread(() -> {
                try { scheduler.fire(); } catch (Throwable error) { failure.set(error); }
            }, "poller-close-before-send");
            worker.start();
            assertTrue(clockEntered.await(5, TimeUnit.SECONDS));
            p.close();
            assertNull(pollerField(p, "tracker"));
            assertNull(pollerField(p, "transport"));
            releaseClock.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(TraceBatchPoller.State.CLOSED, p.state());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, p.lastAttempt().status());
            assertEquals(0, f.tracker.pendingCount());
        } finally {
            releaseClock.countDown();
            if (worker != null) worker.join(5000);
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void rejectedScheduleInvalidatesRetainedTaskAndExplicitResumeWorks() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            RejectedExecutionException rejected = new RejectedExecutionException("retained then rejected");
            scheduler.rejection = rejected;
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            Runnable retained = scheduler.task;
            assertEquals(TraceBatchPoller.State.SUSPENDED, p.state());
            assertSame(rejected, p.schedulingFailure());
            retained.run();
            assertEquals(1, f.tracker.pendingCount());
            scheduler.rejection = null;
            assertTrue(p.resume());
            retained.run();
            scheduler.fire();
            assertEquals(0, f.tracker.pendingCount());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void busyKeepsQueueForNextTick() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread external = null;
        try {
            f.add();
            external = new Thread(() -> {
                try {
                    f.tracker.tryDeliverBatch(events -> {
                        inside.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                        return 1;
                    }, 1);
                } catch (Throwable problem) { failure.set(problem); }
            });
            external.start();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.fire();
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.BUSY, p.lastAttempt().status());
            assertEquals(1, f.tracker.pendingCount());
            release.countDown();
            external.join(5000);
            assertNull(failure.get());
            f.add();
            scheduler.fire();
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, p.lastAttempt().status());
            assertEquals(0, f.tracker.pendingCount());
            p.close();
        } finally {
            release.countDown();
            if (external != null) external.join(5000);
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void closeDuringScheduleCancelsLateReturnedHandle() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            scheduler.rejection = new RejectedExecutionException("first");
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.rejection = null;
            scheduler.onSchedule = p::close;
            assertTrue(p.resume());
            assertEquals(TraceBatchPoller.State.CLOSED, p.state());
            assertTrue(scheduler.latest.isCancelled());
            scheduler.fire();
            assertEquals(1, f.tracker.pendingCount());
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void invalidAcknowledgementSuspendsAndKeepsHead() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 0,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.fire();
            assertEquals(TraceBatchPoller.State.SUSPENDED, p.state());
            assertTrue(p.lastFailure() instanceof IllegalStateException);
            assertEquals(1, f.tracker.pendingCount());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void observedWaitUsesMonotonicDifferenceAcrossLongWrap() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 50);
            AtomicReference<TraceBatchPoller> ref = new AtomicReference<TraceBatchPoller>();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> {
                assertFalse(Thread.holdsLock(ref.get()));
                return 1;
            }, 2, 1, 1, 100, TimeUnit.NANOSECONDS, () -> {
                assertFalse(Thread.holdsLock(ref.get()));
                return clock.get();
            });
            ref.set(p);
            scheduler.fire();
            clock.addAndGet(99);
            scheduler.fire();
            assertEquals(1, f.tracker.pendingCount());
            clock.incrementAndGet();
            scheduler.fire();
            assertEquals(0, f.tracker.pendingCount());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void rejectsOversizedAndOverflowingDurations() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            assertTrue(TestSupport.capture(() -> TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 2, 0, TimeUnit.DAYS)) instanceof IllegalArgumentException);
            assertTrue(TestSupport.capture(() -> TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, Long.MAX_VALUE, TimeUnit.DAYS)) instanceof IllegalArgumentException);
            assertTrue(TestSupport.capture(() -> TraceBatchPoller.startDraining(scheduler, f.tracker, events -> 1,
                    1, 1, 0, 1, 0, TimeUnit.SECONDS)) instanceof IllegalArgumentException);
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void executorCanRunThenThrowWithoutHidingAcknowledgement() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            RejectedExecutionException failure = new RejectedExecutionException("ran then rejected");
            scheduler.onSchedule = () -> scheduler.task.run();
            scheduler.rejection = failure;
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            assertSame(failure, p.schedulingFailure());
            assertEquals(TraceBatchPoller.State.SUSPENDED, p.state());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, p.lastAttempt().status());
            assertEquals(0, f.tracker.pendingCount());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void fatalDeliveryRemainsPrimaryIfCancellationAlsoFailsFatally() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            OutOfMemoryError primary = new OutOfMemoryError("transport fatal");
            OutOfMemoryError secondary = new OutOfMemoryError("cancellation fatal");
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> { throw primary; },
                    1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.latest.cancelFailure = secondary;
            assertSame(primary, TestSupport.capture(scheduler::fire));
            assertSame(primary, p.lastFailure());
            assertSame(secondary, p.schedulingFailure());
            assertArrayEquals(new Throwable[] {secondary}, primary.getSuppressed());
            assertEquals(1, f.tracker.pendingCount());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void fatalCancellationOutranksOrdinaryDeliveryFailure() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            Exception primary = new Exception("transport failed");
            OutOfMemoryError fatal = new OutOfMemoryError("cancellation fatal");
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> { throw primary; },
                    1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.latest.cancelFailure = fatal;
            assertSame(fatal, TestSupport.capture(scheduler::fire));
            assertSame(primary, p.lastFailure());
            assertSame(fatal, p.schedulingFailure());
            assertEquals(1, f.tracker.pendingCount());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void scheduleObservationReportsPendingAndCompletedWithoutChangingState() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            TraceBatchPoller.ScheduleObservation first = p.scheduleObservation();
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.NOT_DONE, first.status());
            assertEquals(TraceBatchPoller.State.ACTIVE, first.state());
            assertFalse(first.inFlight());
            assertNull(first.checkFailure());
            scheduler.latest.cancel(false);
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.DONE, p.scheduleObservation().status());
            assertEquals(TraceBatchPoller.State.ACTIVE, p.state());
            assertEquals(1, f.tracker.pendingCount());
            p.close();
            TraceBatchPoller.ScheduleObservation closed = p.scheduleObservation();
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.INACTIVE, closed.status());
            assertEquals(TraceBatchPoller.State.CLOSED, closed.state());
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void standardSchedulerShutdownCanFinishThePeriodicFutureWhilePollerIsActive() throws Throwable {
        Fixture f = new Fixture();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        TraceBatchPoller p = null;
        try {
            f.add();
            p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    2, 1, 1, 1, TimeUnit.DAYS);
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
            TraceBatchPoller.ScheduleObservation observation = p.scheduleObservation();
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.DONE, observation.status());
            assertEquals(TraceBatchPoller.State.ACTIVE, observation.state());
            assertEquals(1, f.tracker.pendingCount());
        } finally {
            if (p != null) p.close();
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void scheduleObservationCanSeeHandleInstallationWithoutBlockingClose() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = null;
        try {
            scheduler.rejection = new RejectedExecutionException("first");
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.rejection = null;
            scheduler.onSchedule = () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };
            worker = new Thread(() -> {
                try { assertTrue(p.resume()); } catch (Throwable error) { failure.set(error); }
            }, "poller-schedule-observation");
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.HANDLE_PENDING,
                    p.scheduleObservation().status());
            p.close();
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.INACTIVE,
                    p.scheduleObservation().status());
            assertTrue(scheduler.latest.isCancelled());
        } finally {
            release.countDown();
            if (worker != null) worker.join(5000);
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void scheduleObservationReportsCustomFutureFailuresWithoutChangingPoller() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            IllegalStateException failure = new IllegalStateException("inspection failed");
            scheduler.latest.onInspect = () -> { assertFalse(Thread.holdsLock(p)); throw failure; };
            TraceBatchPoller.ScheduleObservation observation = p.scheduleObservation();
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.CHECK_FAILED, observation.status());
            assertSame(failure, observation.checkFailure());
            assertEquals(TraceBatchPoller.State.ACTIVE, p.state());
            assertNull(p.schedulingFailure());

            OutOfMemoryError fatal = new OutOfMemoryError("fatal inspection");
            scheduler.latest.onInspect = () -> { throw fatal; };
            assertSame(fatal, TestSupport.capture(p::scheduleObservation));
            assertEquals(TraceBatchPoller.State.ACTIVE, p.state());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void blockedFutureInspectionPreservesErrorAcrossResumeAndDoesNotHoldStateLock() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TraceBatchPoller.ScheduleObservation> outcome = new AtomicReference<TraceBatchPoller.ScheduleObservation>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
        Thread worker = null;
        try {
            f.add();
            Exception deliveryFailure = new Exception("delivery");
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker,
                    events -> { throw deliveryFailure; }, 1, 1, 1, 0, TimeUnit.SECONDS);
            IllegalStateException inspectionFailure = new IllegalStateException("old future");
            scheduler.latest.onInspect = () -> {
                assertFalse(Thread.holdsLock(p));
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                throw inspectionFailure;
            };
            worker = new Thread(() -> {
                try { outcome.set(p.scheduleObservation()); } catch (Throwable error) { workerFailure.set(error); }
            }, "poller-blocked-observation");
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.fire();
            assertEquals(TraceBatchPoller.State.SUSPENDED, p.state());
            assertSame(deliveryFailure, p.lastFailure());
            assertTrue(p.resume());
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(workerFailure.get());
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.CHANGED, outcome.get().status());
            assertEquals(TraceBatchPoller.State.ACTIVE, outcome.get().state());
            assertSame(inspectionFailure, outcome.get().checkFailure());
            assertEquals(1, f.tracker.pendingCount());
            p.close();
        } finally {
            release.countDown();
            if (worker != null) worker.join(5000);
            scheduler.shutdownNow();
            f.close();
        }
    }

    @Test public void reentrantFutureInspectionCanCloseWithoutDeadlockAndReportsChanged() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> 1,
                    1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.latest.onInspect = () -> { assertFalse(Thread.holdsLock(p)); p.close(); };
            TraceBatchPoller.ScheduleObservation observation = p.scheduleObservation();
            assertEquals(TraceBatchPoller.ScheduleObservationStatus.CHANGED, observation.status());
            assertEquals(TraceBatchPoller.State.CLOSED, observation.state());
            assertNull(observation.checkFailure());
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    @Test public void recursiveTickCannotSendTwice() throws Throwable {
        Fixture f = new Fixture();
        ManualScheduler scheduler = new ManualScheduler();
        try {
            f.add();
            AtomicInteger sends = new AtomicInteger();
            TraceBatchPoller p = TraceBatchPoller.start(scheduler, f.tracker, events -> {
                sends.incrementAndGet();
                scheduler.fire();
                return 1;
            }, 1, 1, 1, 0, TimeUnit.SECONDS);
            scheduler.fire();
            assertEquals(1, sends.get());
            assertEquals(0, f.tracker.pendingCount());
            p.close();
        } finally { scheduler.shutdownNow(); f.close(); }
    }

    private static Object pollerField(TraceBatchPoller poller, String name) throws Exception {
        Field field = TraceBatchPoller.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(poller);
    }

    private static final class Fixture implements AutoCloseable {
        final FakeBackend backend = FakeBackend.create();
        final HookEnvironment env = HookEnvironment.create(backend);
        final CallbackExceptionTracker tracker;
        final RuntimeException problem = new RuntimeException("captured");
        Fixture() throws Throwable {
            this(4);
        }
        Fixture(int capacity) throws Throwable {
            tracker = new CallbackExceptionTracker("poller", capacity);
            TestSupport.installOrFail(env, TestSupport.spec("poller.trace", ADD)
                    .before(tracker.before(invocation -> { throw problem; })).build());
        }
        void add() throws Throwable { assertSame(problem, TestSupport.capture(() -> backend.call(ADD, null, 1, 2))); }
        @Override public void close() { env.close(); }
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        Runnable task;
        RejectedExecutionException rejection;
        Runnable onSchedule;
        ManualFuture latest;
        ManualScheduler() { super(1); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                                    long delay, TimeUnit unit) {
            task = command;
            latest = new ManualFuture(command);
            if (onSchedule != null) onSchedule.run();
            if (rejection != null) throw rejection;
            return latest;
        }
        void fire() { task.run(); }
    }

    private static final class ManualFuture extends FutureTask<Object> implements RunnableScheduledFuture<Object> {
        Error cancelFailure;
        Runnable onInspect;
        ManualFuture(Runnable task) { super(task, null); }
        @Override public boolean isDone() {
            if (onInspect != null) onInspect.run();
            return super.isDone();
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancelFailure != null) throw cancelFailure;
            return super.cancel(mayInterruptIfRunning);
        }
        @Override public boolean isPeriodic() { return true; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
