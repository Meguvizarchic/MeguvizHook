package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** B07 is a single explicit install pilot, not a loop-based callback benchmark. */
public class B07RequestTest {
    private static final String RUN = "f83b50a7-cda5-4d58-9249-d37e2da9929d";
    private static final String PAIR = "184b0505-57a1-4075-8e33-c75f4247ebbc";
    private static final String SESSION = "a215634c-75bc-4202-9893-8a291abda755";

    @Test public void b07OnlyAcceptsOneUnobservedA2Install() {
        PilotRequest implicit = PilotRequest.create(RUN, PAIR, SESSION, "A", "B07", "A2", 0, 1, 1);
        assertNotNull(implicit);
        assertFalse(implicit.observed);
        assertEquals("install_wall_and_thread_cpu_ns", implicit.measurementKind());
        assertNotNull(PilotRequest.create(RUN, PAIR, SESSION, "B", "B07", "A2", 0, 1, 1, "unobserved"));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B07", "A0", 0, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B07", "A2", 0, 1, 1, "observed"));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B07", "A2", 1, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B07", "A2", 0, 2, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B07", "A2", 0, 1, 2));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B11", "A2", 0, 1, 1));
    }

    @Test public void olderMeasurementKindsStayUnchanged() {
        assertEquals("thread_cpu_envelope_ns",
                PilotRequest.create(RUN, PAIR, SESSION, "A", "B06", "A2", 0, 1, 1).measurementKind());
        assertEquals("thread_cpu_envelope_observed_ns",
                PilotRequest.create(RUN, PAIR, SESSION, "A", "B06", "A2", 0, 1, 1, "observed").measurementKind());
    }

    @Test public void b07RejectsAnyRepeatedIdentityWithoutChangingOlderWorkloads() {
        String[][] repeated = {
                {RUN, RUN, SESSION}, {RUN, PAIR, RUN}, {RUN, PAIR, PAIR}, {RUN, RUN, RUN}
        };
        for (String[] ids : repeated) {
            assertNull(PilotRequest.create(ids[0], ids[1], ids[2], "A", "B07", "A2", 0, 1, 1));
            for (String workload : new String[]{"B01", "B02", "B03", "B04", "B05", "B06"}) {
                assertNotNull(PilotRequest.create(ids[0], ids[1], ids[2], "A", workload, "A2", 0, 1, 1));
            }
        }
    }

    @Test public void dedicatedTargetCountsOriginalCalls() {
        long before = B07Target.originalCount();
        assertEquals(42L, B07Target.value());
        assertEquals(before + 1, B07Target.originalCount());
    }

    @Test public void defaultBridgeCannotReportSuccessOrRunTheSample() {
        AtomicInteger sampled = new AtomicInteger();
        PilotExecution.Outcome outcome = PilotExecution.run(new PilotExecution.Steps() {
            @Override public boolean configure() {
                return BenchmarkControl.configure(RUN, PAIR, SESSION, "A", "B07", "A2", 0, 1, 1);
            }
            @Override public String sample() { sampled.incrementAndGet(); return "completed"; }
            @Override public boolean finish() { return BenchmarkControl.finish(RUN); }
        });
        assertFalse(outcome.completed);
        assertFalse(outcome.cleanupReported);
        assertEquals(0, sampled.get());
    }
}
