package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Queue-head inspection and conditional discard preserve delivery ownership. */
public final class CallbackExceptionHeadInspectionTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void observingTheHeadDoesNotRemoveIt() throws Throwable {
        try (Fixture f = new Fixture()) {
            assertNull(f.tracker.peekNext());
            f.add();
            CallbackExceptionTracker.Event head = f.tracker.peekNext();
            assertNotNull(head);
            assertSame(f.failure, head.failure());
            assertSame(head, f.tracker.peekNext());
            assertEquals(1, f.tracker.pendingCount());
            assertEquals(0, f.tracker.discardedCount());
            assertTrue(f.tracker.discardIfNext(head));
            assertEquals(1, f.tracker.discardedCount());
            assertNull(f.tracker.peekNext());
            assertFalse(f.tracker.discardIfNext(head));
        }
    }

    @Test public void staleHeadCannotDiscardItsSuccessor() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.add();
            f.add();
            CallbackExceptionTracker.Event first = f.tracker.peekNext();
            assertEquals(1, f.tracker.deliver(event -> assertSame(first, event), 1));
            CallbackExceptionTracker.Event second = f.tracker.peekNext();
            assertNotNull(second);
            assertNotSame(first, second);
            assertFalse(f.tracker.discardIfNext(first));
            assertSame(second, f.tracker.peekNext());
            assertEquals(0, f.tracker.discardedCount());
            assertTrue(f.tracker.discardIfNext(second));
            assertEquals(1, f.tracker.discardedCount());
        }
    }

    @Test public void reservedBatchRejectsDiscardAndKeepsItsConfirmation() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.add();
            f.add();
            CallbackExceptionTracker.Event first = f.tracker.peekNext();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> problem = new AtomicReference<Throwable>();
            AtomicReference<CallbackExceptionTracker.BatchAttempt> outcome = new AtomicReference<CallbackExceptionTracker.BatchAttempt>();
            Thread worker = new Thread(() -> {
                try {
                    outcome.set(f.tracker.tryDeliverBatch(events -> {
                        assertSame(first, events.get(0));
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("transport timed out");
                        return 1;
                    }, 2));
                } catch (Throwable failure) { problem.set(failure); }
            }, "trace-head-reservation");
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertSame(first, f.tracker.peekNext());
                assertThrows(IllegalStateException.class, () -> f.tracker.discardIfNext(first));
                assertEquals(2, f.tracker.pendingCount());
                assertEquals(0, f.tracker.discardedCount());
            } finally {
                release.countDown();
                worker.join(5000);
            }
            assertFalse(worker.isAlive());
            assertNull(problem.get());
            assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, outcome.get().status());
            assertEquals(1, outcome.get().acceptedCount());
            assertFalse(f.tracker.discardIfNext(first));
            assertEquals(1, f.tracker.pendingCount());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FakeBackend backend = FakeBackend.create();
        final HookEnvironment environment = HookEnvironment.create(backend);
        final CallbackExceptionTracker tracker = new CallbackExceptionTracker("head-inspection", 4);
        final RuntimeException failure = new IllegalStateException("fixture failure");

        Fixture() {
            TestSupport.installOrFail(environment, TestSupport.spec("trace.head", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
        }

        void add() { assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2))); }
        @Override public void close() { environment.close(); }
    }
}
