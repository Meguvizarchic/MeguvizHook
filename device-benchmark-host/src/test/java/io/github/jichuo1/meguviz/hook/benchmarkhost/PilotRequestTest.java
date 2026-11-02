package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Guardrails and direct workload results; these are JVM tests, not device measurements. */
public class PilotRequestTest {
    private static final String RUN = "f83b50a7-cda5-4d58-9249-d37e2da9929d";
    private static final String PAIR = "184b0505-57a1-4075-8e33-c75f4247ebbc";
    private static final String SESSION = "a215634c-75bc-4202-9893-8a291abda755";

    @Test
    public void boundedExplicitRequestIsAccepted() {
        assertNotNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1));
        assertNotNull(PilotRequest.create(RUN, PAIR, SESSION, "B", "B02", "A2", 1_000_000, 9, 1_000_000));
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1).observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1, "observed").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1, "unobserved").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B03", "A0", 0, 1, 1, "observed").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B03", "A2", 0, 1, 1, "observed").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B03", "A0", 0, 1, 1, "unobserved").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B03", "A2", 0, 1, 1, "unobserved").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B04", "A0", 0, 1, 1, "observed").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B04", "A2", 0, 1, 1, "observed").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B04", "A0", 0, 1, 1, "unobserved").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B04", "A2", 0, 1, 1, "unobserved").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B05", "A0", 0, 1, 1, "observed").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B05", "A2", 0, 1, 1, "observed").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B05", "A0", 0, 1, 1, "unobserved").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B05", "A2", 0, 1, 1, "unobserved").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B06", "A0", 0, 1, 1, "observed").observed);
        assertTrue(PilotRequest.create(RUN, PAIR, SESSION, "A", "B06", "A2", 0, 1, 1, "observed").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B06", "A0", 0, 1, 1, "unobserved").observed);
        assertFalse(PilotRequest.create(RUN, PAIR, SESSION, "B", "B06", "A2", 0, 1, 1, "unobserved").observed);
    }

    @Test
    public void invalidIdentityAndUnknownModesAreRejected() {
        assertNull(PilotRequest.create(null, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1));
        assertNull(PilotRequest.create(RUN.toUpperCase(java.util.Locale.ROOT), PAIR, SESSION, "A", "B01", "A0", 0, 1, 1));
        assertNull(PilotRequest.create(RUN, "not-a-uuid", SESSION, "A", "B01", "A0", 0, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "left", "B01", "A0", 0, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B07", "A0", 0, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A1", 0, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1, "OBSERVED"));
    }

    @Test
    public void rejectsOutOfRangeAndExcessiveTotalWork() {
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", -1, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 1_000_001, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 0, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 201, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 0));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1_000_001));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 1, 10, 1_000_000));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0", 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
        // B06 performs four actual target calls for each requested top-level iteration.
        assertNotNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B06", "A0", 500_000, 2, 1_000_000));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B06", "A0", 500_001, 2, 1_000_000));
    }

    @Test
    public void targetLoopsAreDeterministicAndControlDefaultsToDisabled() {
        assertEquals(42L, BenchmarkTarget.noArgs());
        assertEquals(7, BenchmarkTarget.add(3, 4));
        assertEquals(42L * 19, BenchmarkTarget.loopB01(19));
        assertEquals(7L * 19, BenchmarkTarget.loopB02(19));
        int[] box = {0};
        assertEquals(42L, BenchmarkTarget.snapshot(box, 1));
        assertEquals(1, box[0]);
        assertEquals(0L, BenchmarkTarget.loopB03(0));
        assertEquals(42L * 19, BenchmarkTarget.loopB03(19));
        assertEquals(7, BenchmarkTarget.mutableAdd(3, 4));
        assertEquals(9, BenchmarkTarget.mutableAdd(5, 4));
        assertEquals(7L * 19, BenchmarkTarget.loopB04(19));
        assertEquals(42L, BenchmarkTarget.aroundValue());
        assertEquals(0L, BenchmarkTarget.loopB05(0));
        assertEquals(42L * 19, BenchmarkTarget.loopB05(19));
        assertEquals(1, BenchmarkTarget.reentrant(0));
        assertEquals(3, BenchmarkTarget.reentrant(2));
        assertEquals(4, BenchmarkTarget.reentrant(3));
        assertEquals(0L, BenchmarkTarget.loopB06(0));
        assertEquals(4L * 19, BenchmarkTarget.loopB06(19));
        assertFalse(BenchmarkControl.configure(RUN, PAIR, SESSION, "A", "B01", "A0", 0, 1, 1));
        assertFalse(BenchmarkControl.finish(RUN));
    }

    @Test
    public void reentrantTargetsRejectDepthOutsideTheBoundedFixture() {
        try {
            BenchmarkTarget.reentrant(-1);
            fail("negative depth must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ObservedBenchmarkTarget.observedReentrant(4);
            fail("depth greater than three must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
