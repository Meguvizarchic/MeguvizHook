package io.github.jichuo1.meguviz.hook.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Contention-adaptive in-flight counter: uncontended leases stay on one word, an overlap marks the
 * counter and the next lease publishes the cell table before it increments anything, every
 * concurrent sum is non-negative and bounded by the leases that were open around the read, and a
 * quiescent sum is exact.
 */
public class InFlightCounterTest {
    private static final int WORKERS = 8;

    @Test public void cellCountIsAPowerOfTwoOfAtLeastTwicePerProcessorCapped() {
        assertEquals(4, InFlightCounter.cellCount(1));
        assertEquals(4, InFlightCounter.cellCount(2));
        assertEquals(8, InFlightCounter.cellCount(3));
        assertEquals(16, InFlightCounter.cellCount(8));
        assertEquals(16, InFlightCounter.cellCount(6));
        assertEquals(64, InFlightCounter.cellCount(32));
        assertEquals(64, InFlightCounter.cellCount(4096));
    }

    @Test public void sequentialLeasesStayOnTheSharedWord() {
        InFlightCounter counter = new InFlightCounter();
        for (int i = 0; i < 1000; i++) {
            int lease = counter.enter();
            assertEquals(InFlightCounter.SHARED, lease);
            assertEquals(1, counter.sum());
            counter.exit(lease);
            assertTrue(counter.enter() == InFlightCounter.SHARED);
            counter.exitUntracked();
        }
        assertFalse("no overlap was ever observed", counter.inflated());
        assertEquals(0, counter.sum());
    }

    @Test public void overlapInflatesOnceAndKeepsExactQuiescentTotals() {
        InFlightCounter counter = new InFlightCounter();
        int outer = counter.enter();
        int reentrant = counter.enter();
        assertTrue("an increment that sees another open lease marks the counter", counter.inflationPending());
        assertFalse("no allocation follows an increment", counter.inflated());
        assertEquals("the overlapping lease stays where it was counted", InFlightCounter.SHARED, reentrant);
        int striped = counter.enter();
        assertTrue("the next lease publishes the table before its own increment", counter.inflated());
        assertNotEquals(InFlightCounter.SHARED, striped);
        assertEquals(3, counter.sum());
        counter.exit(striped);
        counter.exit(reentrant);
        assertEquals(1, counter.sum());
        // Split protocol: a lease opened before the table existed may close on the thread's cell.
        counter.exitUntracked();
        assertEquals(0, counter.sum());
        assertEquals(0L, counter.rawSum());
        assertEquals(InFlightCounter.SHARED, outer);
    }

    @Test public void concurrentSumsAreNeverNegativeAndQuiescentSumsAreExact() throws Throwable {
        for (boolean tokens : new boolean[]{true, false}) {
            InFlightCounter counter = new InFlightCounter();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            AtomicLong reads = new AtomicLong();
            List<Thread> threads = new ArrayList<Thread>();
            CyclicBarrier start = new CyclicBarrier(WORKERS + 1);
            for (int w = 0; w < WORKERS; w++) {
                threads.add(daemon("lease-" + w, failure, () -> {
                    start.await();
                    for (int i = 0; i < 200_000 && failure.get() == null; i++) {
                        if (tokens) {
                            int lease = counter.enter();
                            if ((i & 7) == 0) Thread.yield();
                            counter.exit(lease);
                        } else {
                            counter.enter();
                            if ((i & 7) == 0) Thread.yield();
                            counter.exitUntracked();
                        }
                    }
                }));
            }
            Thread reader = daemon("sum-reader", failure, () -> {
                while (!stop.get()) {
                    long raw = counter.rawSum();
                    reads.incrementAndGet();
                    if (raw < 0 || raw > WORKERS) {
                        throw new AssertionError("sum " + raw + " outside [0, " + WORKERS + "]");
                    }
                }
            });
            reader.start();
            for (Thread thread : threads) thread.start();
            start.await(10, TimeUnit.SECONDS);
            for (Thread thread : threads) thread.join(60_000);
            stop.set(true);
            reader.join(10_000);
            if (failure.get() != null) throw failure.get();
            assertTrue("reader ran concurrently", reads.get() > 0);
            assertTrue("eight contending threads observe an overlap", counter.inflated());
            assertEquals("quiescent total is exact", 0L, counter.rawSum());
            assertEquals(0, counter.sum());
        }
    }

    @Test public void heldLeasesAreAlwaysCountedDuringChurn() throws Throwable {
        int held = 3;
        int churners = 5;
        InFlightCounter counter = new InFlightCounter();
        CountDownLatch holding = new CountDownLatch(held);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        List<Thread> threads = new ArrayList<Thread>();
        for (int h = 0; h < held; h++) {
            threads.add(daemon("held-" + h, failure, () -> {
                int lease = counter.enter();
                holding.countDown();
                try {
                    assertTrue(release.await(60, TimeUnit.SECONDS));
                } finally {
                    counter.exit(lease);
                }
            }));
        }
        for (int c = 0; c < churners; c++) {
            boolean tokens = (c & 1) == 0;
            threads.add(daemon("churn-" + c, failure, () -> {
                assertTrue(holding.await(10, TimeUnit.SECONDS));
                while (!stop.get()) {
                    if (tokens) {
                        counter.exit(counter.enter());
                    } else {
                        counter.enter();
                        counter.exitUntracked();
                    }
                }
            }));
        }
        for (Thread thread : threads) thread.start();
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (System.nanoTime() < deadline && failure.get() == null) {
                long raw = counter.rawSum();
                // Leases open for the whole read are counted; each churner adds at most its own one.
                assertTrue("sum " + raw, raw >= held && raw <= held + churners);
            }
        } finally {
            stop.set(true);
            release.countDown();
            for (Thread thread : threads) thread.join(60_000);
        }
        if (failure.get() != null) throw failure.get();
        assertEquals(0, counter.sum());
    }

    @Test public void readOrderNeverShowsADepartureWithoutItsArrival() {
        // A split lease enters on the shared word while no table exists and exits on its thread's
        // cell after publication, all while one read is in progress. Reading the table and cells
        // first, then the shared word, can only over-count that lease; the reverse order sees -1.
        InFlightCounter counter = new InFlightCounter();
        long observed = counter.rawSum(() -> {
            counter.enter();
            counter.exit(counter.enter());
            counter.exit(counter.enter());
            counter.exitUntracked();
        });
        assertTrue(counter.inflated());
        assertEquals("the in-read lease counted at most once, never as departed", 1L, observed);
        assertEquals(0L, counter.rawSum());

        InFlightCounter striped = new InFlightCounter();
        int held = striped.enter();
        striped.exit(striped.enter());
        int cell = striped.enter();
        striped.exit(cell);
        assertTrue(striped.inflated());
        long withHeld = striped.rawSum(() -> {
            striped.enter();
            striped.exitUntracked();
            striped.exit(striped.enter());
        });
        assertEquals("a lease held for the whole read is counted", 1L, withHeld);
        striped.exit(held);
        assertEquals(0L, striped.rawSum());
    }

    @Test public void nestedLeasesOfOneThreadMayAllCountDuringARead() {
        // The concurrent upper bound is per thread its nesting depth, not one.
        InFlightCounter counter = new InFlightCounter();
        int[] leases = new int[3];
        long observed = counter.rawSum(() -> {
            for (int depth = 0; depth < leases.length; depth++) leases[depth] = counter.enter();
        });
        assertTrue(observed >= 0 && observed <= leases.length);
        for (int depth = leases.length - 1; depth >= 0; depth--) counter.exit(leases[depth]);
        assertEquals(0L, counter.rawSum());
    }

    @Test public void splitExitOnAnotherThreadKeepsQuiescentTotalsExact() throws Throwable {
        InFlightCounter counter = new InFlightCounter();
        counter.exit(counter.enter());
        int first = counter.enter();
        counter.exit(counter.enter());
        counter.exit(counter.enter());
        counter.exit(first);
        assertTrue(counter.inflated());
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int i = 0; i < 100; i++) {
            Thread entering = daemon("split-enter", failure, counter::enter);
            entering.start();
            entering.join();
            Thread exiting = daemon("split-exit", failure, counter::exitUntracked);
            exiting.start();
            exiting.join();
        }
        if (failure.get() != null) throw failure.get();
        assertEquals(0L, counter.rawSum());
    }

    @Test public void stackOverflowAroundTheFirstInflationLeaksNoLease() throws Throwable {
        for (boolean pending : new boolean[]{false, true}) {
            for (int trial = 0; trial < 40; trial++) {
                InFlightCounter counter = new InFlightCounter();
                int outer = counter.enter();
                if (pending) {
                    counter.exit(counter.enter());
                    assertTrue(counter.inflationPending());
                }
                int[] lease = {Integer.MIN_VALUE};
                AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
                Thread diver = new Thread(null, () -> {
                    try {
                        dive(counter, lease);
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                }, "dive", 256 * 1024);
                diver.start();
                diver.join(30_000);
                if (failure.get() != null) throw failure.get();
                assertNotEquals("the edge lease was eventually taken", Integer.MIN_VALUE, lease[0]);
                counter.exit(lease[0]);
                counter.exit(outer);
                assertEquals("pending=" + pending + " trial=" + trial, 0L, counter.rawSum());
            }
        }
    }

    /** Recurses to the stack limit, then takes one lease as close to the edge as it succeeds. */
    private static void dive(InFlightCounter counter, int[] lease) {
        try {
            dive(counter, lease);
        } catch (StackOverflowError overflow) {
            if (lease[0] != Integer.MIN_VALUE) return;
            lease[0] = counter.enter();
        }
    }

    private interface Body {
        void run() throws Throwable;
    }

    private static Thread daemon(String name, AtomicReference<Throwable> failure, Body body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }
}
