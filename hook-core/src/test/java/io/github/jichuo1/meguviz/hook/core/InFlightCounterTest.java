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
 * Contention-adaptive in-flight counter: uncontended leases stay on one word, the first overlap
 * publishes the cell table, every concurrent sum is non-negative and bounded by the leases that
 * were open around the read, and a quiescent sum is exact.
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
        assertTrue("an increment that sees another open lease publishes the table", counter.inflated());
        assertEquals("the inflating lease stays where it was counted", InFlightCounter.SHARED, reentrant);
        int striped = counter.enter();
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
