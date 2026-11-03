package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Local contention and acknowledgement contracts; no Binder or device is exercised. */
public final class CallbackExceptionTryBatchTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void outsideLockBatchAllowsReverseCallsWithoutRemovingReservedHead() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("try-batch", 4);
        RuntimeException[] failures = {new RuntimeException("first"), new RuntimeException("second"),
                new RuntimeException("appended")};
        AtomicInteger next = new AtomicInteger();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.try.reverse", ADD)
                    .before(tracker.before(invocation -> { throw failures[next.getAndIncrement()]; })).build());
            for (int i = 0; i < 2; i++) {
                assertSame(failures[i], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            CallbackExceptionTracker.BatchAttempt result = tracker.tryDeliverBatch(events -> {
                assertEquals(2, events.size());
                assertSame(failures[0], events.get(0).failure());
                assertTrue(TestSupport.capture(events::clear) instanceof UnsupportedOperationException);
                assertEquals(CallbackExceptionTracker.BatchAttemptStatus.BUSY,
                        tracker.tryDeliverBatch(inner -> 1, 1).status());
                AtomicReference<Throwable> discard = new AtomicReference<Throwable>();
                AtomicReference<Throwable> legacy = new AtomicReference<Throwable>();
                AtomicReference<CallbackExceptionTracker.BatchAttempt> competing = new AtomicReference<CallbackExceptionTracker.BatchAttempt>();
                Thread reverse = new Thread(() -> {
                    discard.set(TestSupport.capture(tracker::discardNext));
                    legacy.set(TestSupport.capture(() -> tracker.deliver(event -> { }, 1)));
                    try { competing.set(tracker.tryDeliverBatch(inner -> 1, 1)); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }, "trace-reverse-callback");
                reverse.start();
                reverse.join(5000);
                assertFalse("reverse call waited for transport", reverse.isAlive());
                assertTrue(discard.get() instanceof IllegalStateException);
                assertTrue(legacy.get() instanceof IllegalStateException);
                assertEquals(CallbackExceptionTracker.BatchAttemptStatus.BUSY, competing.get().status());
                // A producer appends without changing the reserved list or removing its head.
                assertSame(failures[2], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                assertEquals(2, events.size());
                assertEquals(3, tracker.pendingCount());
                return 2;
            }, 4);
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, result.status());
            assertEquals(2, result.acceptedCount());
            assertEquals(1, tracker.pendingCount());
            assertSame(failures[2], tracker.discardNext().failure());
            CallbackExceptionTracker.BatchAttempt empty = tracker.tryDeliverBatch(events -> {
                fail("empty batch sent"); return 1;
            }, 1);
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.EMPTY, empty.status());
            assertEquals(0, empty.acceptedCount());
        } finally { env.close(); }
    }

    @Test public void failureAndInvalidAckRetainOrderAndReleaseReservation() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("try-failure", 3);
        RuntimeException[] failures = {new RuntimeException("first"), new RuntimeException("second")};
        AtomicInteger next = new AtomicInteger();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.try.failure", ADD)
                    .before(tracker.before(invocation -> { throw failures[next.getAndIncrement()]; })).build());
            for (int i = 0; i < failures.length; i++) {
                assertSame(failures[i], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            Exception unknown = new Exception("receiver may have accepted");
            assertSame(unknown, TestSupport.capture(() -> tracker.tryDeliverBatch(events -> { throw unknown; }, 2)));
            assertEquals(2, tracker.pendingCount());
            for (int invalid : new int[] {0, -1, 3}) {
                assertTrue(TestSupport.capture(() -> tracker.tryDeliverBatch(events -> invalid, 2))
                        instanceof IllegalStateException);
                assertEquals(2, tracker.pendingCount());
            }
            AssertionError fatalToTransport = new AssertionError("side effect unknown");
            assertSame(fatalToTransport, TestSupport.capture(() -> tracker.tryDeliverBatch(events -> {
                throw fatalToTransport;
            }, 2)));
            assertEquals(2, tracker.pendingCount());
            List<CallbackExceptionTracker.Event> received = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(2, tracker.deliver(event -> received.add(event), 2));
            assertSame(failures[0], received.get(0).failure());
            assertSame(failures[1], received.get(1).failure());
        } finally { env.close(); }
    }

    @Test public void legacyOnlyOperationsStillWaitWhileTryAttemptReturnsBusy() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("legacy-wait", 3);
        RuntimeException[] failures = {new RuntimeException("first"), new RuntimeException("second")};
        AtomicInteger next = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        AtomicReference<CallbackExceptionTracker.Event> discarded = new AtomicReference<CallbackExceptionTracker.Event>();
        Thread first = null;
        Thread second = null;
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.legacy.wait", ADD)
                    .before(tracker.before(invocation -> { throw failures[next.getAndIncrement()]; })).build());
            for (int i = 0; i < failures.length; i++) {
                assertSame(failures[i], TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            first = new Thread(() -> {
                try {
                    assertEquals(1, tracker.deliverBatch(events -> {
                        assertTrue(TestSupport.capture(tracker::discardNext) instanceof IllegalStateException);
                        assertTrue(TestSupport.capture(() -> tracker.deliver(event -> { }, 1)) instanceof IllegalStateException);
                        inside.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("legacy send timed out");
                        return 1;
                    }, 1));
                } catch (Throwable failure) { error.compareAndSet(null, failure); }
            }, "legacy-trace-send");
            first.setDaemon(true);
            first.start();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.BUSY,
                    tracker.tryDeliverBatch(events -> 1, 2).status());
            second = new Thread(() -> {
                try { attempting.countDown(); discarded.set(tracker.discardNext()); }
                catch (Throwable failure) { error.compareAndSet(null, failure); }
                finally { finished.countDown(); }
            }, "legacy-trace-discard");
            second.setDaemon(true);
            second.start();
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            assertFalse("legacy discard stopped waiting", finished.await(150, TimeUnit.MILLISECONDS));
            release.countDown();
            first.join(5000);
            second.join(5000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertNull(error.get());
            assertSame(failures[1], discarded.get().failure());
            assertEquals(0, tracker.pendingCount());
        } finally {
            release.countDown();
            if (first != null) first.join(5000);
            if (second != null) second.join(5000);
            env.close();
        }
    }

    @Test public void lockDoesNotConsumePreexistingInterruptStatus() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("try-interrupt", 1);
        RuntimeException failure = new RuntimeException("interrupt");
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.try.interrupt", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED,
                            tracker.tryDeliverBatch(events -> 1, 1).status());
                    assertTrue(Thread.currentThread().isInterrupted());
                } catch (Throwable problem) { error.set(problem); }
            }, "interrupted-trace-worker");
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(error.get());
        } finally { env.close(); }
    }
}
