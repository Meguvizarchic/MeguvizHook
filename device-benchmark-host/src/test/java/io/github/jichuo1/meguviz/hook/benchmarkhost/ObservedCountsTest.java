package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Observed workloads count actual calls and reject missing or duplicated work. */
public class ObservedCountsTest {
    @Test
    public void actualTargetCountsMustMatchTheHooksForBothShapes() {
        ObservedBenchmarkTarget.prepareObservation();
        AtomicLong[] counts = ObservedBenchmarkTarget.observationCounters();
        assertEquals(42L * 3, ObservedBenchmarkTarget.loopObservedB01(3));
        assertFalse(ObservedCounts.matches("B01", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[0].addAndGet(3);
        counts[1].addAndGet(3);
        assertTrue(ObservedCounts.matches("B01", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[2].incrementAndGet();
        assertFalse(ObservedCounts.matches("B01", 3, ObservedBenchmarkTarget.observationSnapshot()));

        ObservedBenchmarkTarget.resetObservations();
        assertEquals(7L * 2, ObservedBenchmarkTarget.loopObservedB02(2));
        counts[0].addAndGet(2);
        assertTrue(ObservedCounts.matches("B02", 2, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].incrementAndGet();
        assertFalse(ObservedCounts.matches("B02", 2, ObservedBenchmarkTarget.observationSnapshot()));

        ObservedBenchmarkTarget.resetObservations();
        assertEquals(42L * 3, ObservedBenchmarkTarget.loopObservedB03(3));
        assertFalse(ObservedCounts.matches("B03", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[0].addAndGet(3);
        counts[1].addAndGet(3);
        assertTrue(ObservedCounts.matches("B03", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].decrementAndGet();
        assertFalse(ObservedCounts.matches("B03", 3, ObservedBenchmarkTarget.observationSnapshot()));

        ObservedBenchmarkTarget.resetObservations();
        int[] box = {4};
        assertEquals(42L, ObservedBenchmarkTarget.observedSnapshot(box, 2));
        assertEquals(6, box[0]);
        assertEquals(1L, ObservedBenchmarkTarget.observationSnapshot()[2]);

        ObservedBenchmarkTarget.resetObservations();
        assertEquals(7L * 2, ObservedBenchmarkTarget.loopObservedB04(2));
        assertFalse(ObservedCounts.matches("B04", 2, ObservedBenchmarkTarget.observationSnapshot()));
        counts[0].addAndGet(2);
        assertTrue(ObservedCounts.matches("B04", 2, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].incrementAndGet();
        assertFalse(ObservedCounts.matches("B04", 2, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].decrementAndGet();
        counts[2].incrementAndGet();
        assertFalse(ObservedCounts.matches("B04", 2, ObservedBenchmarkTarget.observationSnapshot()));

        ObservedBenchmarkTarget.resetObservations();
        assertEquals(9, ObservedBenchmarkTarget.observedMutableAdd(5, 4));
        assertEquals(1L, ObservedBenchmarkTarget.observationSnapshot()[2]);
    }

    @Test
    public void aroundEntryExitAndOriginalCountsMustAllMatch() {
        ObservedBenchmarkTarget.prepareObservation();
        AtomicLong[] counts = ObservedBenchmarkTarget.observationCounters();
        assertEquals(42L * 3, ObservedBenchmarkTarget.loopObservedB05(3));
        assertFalse(ObservedCounts.matches("B05", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[0].addAndGet(3);
        assertFalse(ObservedCounts.matches("B05", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].addAndGet(3);
        assertTrue(ObservedCounts.matches("B05", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].incrementAndGet();
        assertFalse(ObservedCounts.matches("B05", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].decrementAndGet();
        counts[2].incrementAndGet();
        assertFalse(ObservedCounts.matches("B05", 3, ObservedBenchmarkTarget.observationSnapshot()));
        assertFalse(ObservedCounts.matches("B07", 3, ObservedBenchmarkTarget.observationSnapshot()));
    }

    @Test
    public void reentrantCallsCountAllFourLevelsAndRejectMissingOrExtraWork() {
        ObservedBenchmarkTarget.prepareObservation();
        AtomicLong[] counts = ObservedBenchmarkTarget.observationCounters();
        assertEquals(0L, ObservedBenchmarkTarget.loopObservedB06(0));
        assertTrue(ObservedCounts.matches("B06", 0, ObservedBenchmarkTarget.observationSnapshot()));
        assertEquals(4L * 3, ObservedBenchmarkTarget.loopObservedB06(3));
        assertEquals(12L, counts[2].get());
        assertFalse(ObservedCounts.matches("B06", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[0].addAndGet(12);
        counts[1].addAndGet(12);
        assertTrue(ObservedCounts.matches("B06", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].decrementAndGet();
        assertFalse(ObservedCounts.matches("B06", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[1].incrementAndGet();
        counts[2].incrementAndGet();
        assertFalse(ObservedCounts.matches("B06", 3, ObservedBenchmarkTarget.observationSnapshot()));
        counts[2].decrementAndGet();
        counts[3].incrementAndGet();
        assertFalse(ObservedCounts.matches("B06", 3, ObservedBenchmarkTarget.observationSnapshot()));
    }

    @Test
    public void otherThreadCallsAreNotConsideredValid() throws Exception {
        ObservedBenchmarkTarget.prepareObservation();
        Thread other = new Thread(ObservedBenchmarkTarget::observedNoArgs);
        other.start();
        other.join();
        AtomicLong[] counts = ObservedBenchmarkTarget.observationCounters();
        counts[0].incrementAndGet();
        counts[1].incrementAndGet();
        assertFalse(ObservedCounts.matches("B01", 1, ObservedBenchmarkTarget.observationSnapshot()));
    }
}
