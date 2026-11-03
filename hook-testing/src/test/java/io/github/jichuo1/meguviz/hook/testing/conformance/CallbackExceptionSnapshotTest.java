package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.RandomAccess;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Batch snapshots are taken with one queue lock and accepted prefixes are removed with one bulk
 * drain. These tests pin the observable list contract and the FIFO/no-loss/no-duplicate removal
 * semantics under concurrent producers.
 */
public final class CallbackExceptionSnapshotTest {
    static final class Numbered extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int producer;
        final int sequence;
        Numbered(int producer, int sequence) {
            super(null, null, false, false);
            this.producer = producer;
            this.sequence = sequence;
        }
    }

    private static final class Capture {
        private final BeforeCallback wrapped;
        private final TraceWireDifferentialTest.StubInvocation invocation =
                new TraceWireDifferentialTest.StubInvocation(HookId.of("snapshot.hook"), 1L);
        private final AtomicReference<RuntimeException> next = new AtomicReference<RuntimeException>();

        Capture(CallbackExceptionTracker tracker) {
            wrapped = tracker.before(ignored -> { throw next.get(); });
        }

        void capture(RuntimeException failure) {
            next.set(failure);
            try {
                wrapped.before(invocation);
                fail("callback did not throw");
            } catch (Throwable thrown) {
                assertSame(failure, thrown);
            }
        }
    }

    @Test public void snapshotIsAnImmutableOrderedPrefixOfExactlyTheLimit() throws Exception {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("snapshot", 16);
        Capture capture = new Capture(tracker);
        List<RuntimeException> failures = new ArrayList<RuntimeException>();
        for (int i = 0; i < 6; i++) {
            Numbered failure = new Numbered(0, i);
            failures.add(failure);
            capture.capture(failure);
        }
        AtomicReference<List<CallbackExceptionTracker.Event>> retained =
                new AtomicReference<List<CallbackExceptionTracker.Event>>();
        CallbackExceptionTracker.BatchAttempt attempt = tracker.tryDeliverBatch(events -> {
            retained.set(events);
            assertEquals(4, events.size());
            assertTrue(events instanceof RandomAccess);
            for (int i = 0; i < 4; i++) assertSame(failures.get(i), events.get(i).failure());
            List<CallbackExceptionTracker.Event> copy = new ArrayList<CallbackExceptionTracker.Event>(events);
            assertEquals(copy, events);
            assertEquals(events, copy);
            assertEquals(copy.hashCode(), events.hashCode());
            assertEquals(copy.toString(), events.toString());
            assertArrayEquals(copy.toArray(), events.toArray());
            assertEquals(1, events.indexOf(copy.get(1)));
            assertTrue(events.contains(copy.get(3)));
            assertEquals(Arrays.asList(copy.get(1), copy.get(2)), events.subList(1, 3));
            assertTrue(TestSupport.capture(() -> events.get(4)) instanceof IndexOutOfBoundsException);
            assertTrue(TestSupport.capture(() -> events.get(-1)) instanceof IndexOutOfBoundsException);
            CallbackExceptionTracker.Event head = events.get(0);
            assertUnsupported(() -> events.add(head));
            assertUnsupported(() -> events.add(0, head));
            assertUnsupported(() -> events.set(0, head));
            assertUnsupported(() -> events.remove(0));
            assertUnsupported(() -> events.remove(head));
            assertUnsupported(events::clear);
            assertUnsupported(() -> events.addAll(copy));
            assertUnsupported(() -> events.removeAll(copy));
            assertUnsupported(() -> events.retainAll(Collections.<CallbackExceptionTracker.Event>emptyList()));
            assertUnsupported(() -> events.removeIf(event -> true));
            assertUnsupported(() -> events.replaceAll(event -> event));
            assertUnsupported(() -> events.sort(null));
            assertUnsupported(() -> events.subList(0, 2).clear());
            assertUnsupported(() -> { java.util.Iterator<CallbackExceptionTracker.Event> it = events.iterator(); it.next(); it.remove(); });
            assertUnsupported(() -> { ListIterator<CallbackExceptionTracker.Event> it = events.listIterator(); it.next(); it.set(head); });
            assertEquals(4, events.size());
            return 3;
        }, 4);
        assertEquals(CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED, attempt.status());
        assertEquals(3, attempt.acceptedCount());
        // A retained list still exposes exactly the reserved prefix.
        assertEquals(4, retained.get().size());
        assertEquals(3, tracker.pendingCount());
        assertSame(failures.get(3), tracker.peekNext().failure());

        // The legacy holding variant shares the snapshot and prefix-removal path.
        assertEquals(2, tracker.deliverBatch(events -> {
            assertEquals(3, events.size());
            assertSame(failures.get(3), events.get(0).failure());
            assertSame(failures.get(5), events.get(2).failure());
            return 2;
        }, 8));
        assertEquals(1, tracker.pendingCount());
        assertSame(failures.get(5), tracker.discardNext().failure());
        assertEquals(0, tracker.deliverBatch(events -> { fail("empty batch sent"); return 1; }, 8));
    }

    private static void assertUnsupported(TestSupport.ThrowingCall call) {
        assertTrue(TestSupport.capture(call) instanceof UnsupportedOperationException);
    }

    @Test public void concurrentProducersNeitherLoseNorDuplicateAcceptedPrefixes() throws Throwable {
        int producers = 4;
        int perProducer = 5000;
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("snapshot-stress", 64);
        List<Thread> threads = new ArrayList<Thread>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int p = 0; p < producers; p++) {
            final int producer = p;
            threads.add(new Thread(() -> {
                Capture capture = new Capture(tracker);
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        capture.capture(new Numbered(producer, i));
                        if ((i & 63) == 0) Thread.yield();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }, "snapshot-producer-" + p));
        }
        List<CallbackExceptionTracker.Event> delivered = new ArrayList<CallbackExceptionTracker.Event>();
        Random random = new Random(11);
        AtomicBoolean producing = new AtomicBoolean(true);
        for (Thread thread : threads) thread.start();
        start.countDown();
        Thread watcher = new Thread(() -> {
            for (Thread thread : threads) {
                try { thread.join(); } catch (InterruptedException interrupted) { return; }
            }
            producing.set(false);
        }, "snapshot-watcher");
        watcher.start();
        while (producing.get() || tracker.pendingCount() > 0) {
            int limit = 1 + random.nextInt(48);
            CallbackExceptionTracker.BatchTransport transport = events -> {
                int accepted = 1 + random.nextInt(events.size());
                delivered.addAll(events.subList(0, accepted));
                return accepted;
            };
            if (random.nextInt(4) == 0) {
                tracker.deliverBatch(transport, limit);
            } else {
                CallbackExceptionTracker.BatchAttempt attempt = tracker.tryDeliverBatch(transport, limit);
                assertNotEquals(CallbackExceptionTracker.BatchAttemptStatus.BUSY, attempt.status());
            }
        }
        watcher.join();
        if (failure.get() != null) throw failure.get();
        long total = (long) producers * perProducer;
        assertEquals(total, delivered.size() + tracker.droppedCount());
        int[] lastSequence = new int[producers];
        Arrays.fill(lastSequence, -1);
        java.util.Set<CallbackExceptionTracker.Event> unique = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<CallbackExceptionTracker.Event, Boolean>());
        for (CallbackExceptionTracker.Event event : delivered) {
            assertTrue("duplicate delivery", unique.add(event));
            Numbered numbered = (Numbered) event.failure();
            assertTrue("per-producer FIFO", numbered.sequence > lastSequence[numbered.producer]);
            lastSequence[numbered.producer] = numbered.sequence;
        }
        assertEquals(0, tracker.trackingFailureCount());
    }
}
