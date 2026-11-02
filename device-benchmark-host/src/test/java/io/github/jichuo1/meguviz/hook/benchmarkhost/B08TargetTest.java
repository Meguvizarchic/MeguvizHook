package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises the state used by the public target, without any Hook framework. */
public class B08TargetTest {
    @Test public void heldOriginalWaitsWhileNewOriginalCanComplete() throws Exception {
        B08Target.State state = new B08Target.State(2_000L);
        CountDownLatch[] gates = state.prepare();
        AtomicReference<Long> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(state.call(true)); }
            catch (Throwable error) { failure.set(error); }
        }, "B08-target-JVM-check");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(gates[0].await(1, TimeUnit.SECONDS));
            assertEquals(1L, gates[1].getCount());
            assertTrue(worker.isAlive());
            assertNull(result.get());
            assertArrayEquals(new long[]{1, 0}, state.originalCounts());
            assertEquals(42L, state.call(false));
            assertArrayEquals(new long[]{1, 1}, state.originalCounts());
            assertEquals(1L, gates[1].getCount());
            assertTrue(worker.isAlive());
        } finally {
            gates[1].countDown();
            worker.join(1_000L);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(Long.valueOf(42L), result.get());
        assertArrayEquals(new long[]{1, 1}, state.originalCounts());
    }

    @Test public void timeoutFailsInsteadOfPretendingTheHeldOriginalCompleted() throws Exception {
        B08Target.State state = new B08Target.State(1L);
        CountDownLatch[] gates = state.prepare();
        try {
            state.call(true);
            fail("unreleased original must fail");
        } catch (IllegalStateException expected) {
            assertEquals("B08 original release timed out", expected.getMessage());
        }
        assertEquals(0L, gates[0].getCount());
        assertEquals(1L, gates[1].getCount());
        assertArrayEquals(new long[]{1, 0}, state.originalCounts());
    }

    @Test public void targetRequiresPreparationAndCannotBePreparedAgain() throws Exception {
        B08Target.State state = new B08Target.State(1_000L);
        try {
            state.call(false);
            fail("unprepared target must fail");
        } catch (IllegalStateException expected) {
            assertArrayEquals(new long[]{0, 0}, state.originalCounts());
        }
        CountDownLatch[] gates = state.prepare();
        assertNotNull(gates[0]);
        assertNotNull(gates[1]);
        assertEquals(42L, state.call(false));
        try {
            state.prepare();
            fail("same process must not reset the transaction");
        } catch (IllegalStateException expected) {
            assertArrayEquals(new long[]{0, 1}, state.originalCounts());
            assertEquals(1L, gates[1].getCount());
        }
    }

    @Test public void publicTargetUsesTheSameOneShotContractAndReturnsDefensiveCounts() throws Exception {
        assertArrayEquals(new long[]{0, 0}, B08Target.originalCounts());
        CountDownLatch[] gates = B08Target.prepare();
        assertEquals(42L, B08Target.call(false));
        long[] counts = B08Target.originalCounts();
        counts[1] = 0;
        assertArrayEquals(new long[]{0, 1}, B08Target.originalCounts());
        assertEquals(1L, gates[0].getCount());
        assertEquals(1L, gates[1].getCount());
    }
}
