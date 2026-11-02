package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Explicit single-call failure cases, without changing the nine-argument module bridge. */
public class B09RequestTest {
    private static final String RUN = "f83b50a7-cda5-4d58-9249-d37e2da9929d";
    private static final String PAIR = "184b0505-57a1-4075-8e33-c75f4247ebbc";
    private static final String SESSION = "a215634c-75bc-4202-9893-8a291abda755";

    private static PilotRequest request(String control, String mode, String failureCase) {
        return PilotRequest.create(RUN, PAIR, SESSION, "A", "B09", control, 0, 1, 1, mode, failureCase);
    }

    @Test public void eachControlRequiresAnExplicitSingleFailureCase() {
        String[] cases = {"before", "after", "original"};
        String[] encoded = {"B09_BEFORE", "B09_AFTER", "B09_ORIGINAL"};
        for (String control : new String[]{"A0", "A2"}) {
            for (int index = 0; index < cases.length; index++) {
                for (String mode : new String[]{null, "unobserved"}) {
                    PilotRequest request = request(control, mode, cases[index]);
                    assertNotNull(request);
                    assertFalse(request.observed);
                    assertEquals("B09", request.workload);
                    assertEquals(cases[index], request.failureCase);
                    assertEquals(encoded[index], request.bridgeWorkload());
                    assertEquals("failure_call_wall_and_thread_cpu_ns", request.measurementKind());
                }
            }
        }
    }

    @Test public void failureCaseCannotBeImplicitUnknownOrInjectedIntoOldWorkloads() {
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B09", "A2", 0, 1, 1));
        for (String failureCase : new String[]{null, "", "BEFORE", "before ", "other"}) {
            assertNull(request("A2", "unobserved", failureCase));
        }
        assertNull(request("A0", "observed", "before"));
        assertNull(request("A1", "unobserved", "before"));
        for (String workload : new String[]{"B01", "B02", "B03", "B04", "B05", "B06", "B07", "B08"}) {
            assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", workload, "A2", 0, 1, 1,
                    "unobserved", "before"));
            assertNotNull(PilotRequest.create(RUN, PAIR, SESSION, "A", workload, "A2", 0, 1, 1));
        }
        for (String encoded : new String[]{"B09_BEFORE", "B09_AFTER", "B09_ORIGINAL", "B10"}) {
            assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", encoded, "A2", 0, 1, 1,
                    "unobserved", "before"));
        }
    }

    @Test public void repeatedIdsAndLoopParametersAreRejected() {
        for (String[] ids : new String[][]{{RUN, RUN, SESSION}, {RUN, PAIR, RUN},
                {RUN, PAIR, PAIR}, {RUN, RUN, RUN}}) {
            assertNull(PilotRequest.create(ids[0], ids[1], ids[2], "A", "B09", "A2", 0, 1, 1,
                    "unobserved", "original"));
        }
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B09", "A2", 1, 1, 1,
                "unobserved", "before"));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B09", "A2", 0, 2, 1,
                "unobserved", "after"));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B09", "A2", 0, 1, 2,
                "unobserved", "original"));
    }

    @Test public void oldMeasurementKindsAndBridgeSelectionsRemainAvailable() {
        assertEquals("B01_OBSERVED", PilotRequest.create(RUN, PAIR, SESSION, "A", "B01", "A0",
                0, 1, 1, "observed").bridgeWorkload());
        assertEquals("B06", PilotRequest.create(RUN, PAIR, SESSION, "A", "B06", "A2", 0, 1, 1)
                .bridgeWorkload());
        assertEquals("install_wall_and_thread_cpu_ns", PilotRequest.create(RUN, PAIR, SESSION, "A", "B07",
                "A2", 0, 1, 1).measurementKind());
        assertEquals("close_with_inflight_wall_ns", PilotRequest.create(RUN, PAIR, SESSION, "A", "B08",
                "A2", 0, 1, 1).measurementKind());
    }

    @Test public void defaultBridgeCannotPretendTheExpectedFailureWasMeasured() {
        PilotRequest request = request("A2", null, "before");
        AtomicInteger sampled = new AtomicInteger();
        PilotExecution.Outcome outcome = PilotExecution.run(new PilotExecution.Steps() {
            @Override public boolean configure() {
                return BenchmarkControl.configure(request.runId, request.pairId, request.sessionId,
                        request.side, request.bridgeWorkload(), request.control, 0, 1, 1);
            }
            @Override public String sample() { sampled.incrementAndGet(); return "completed"; }
            @Override public boolean finish() { return BenchmarkControl.finish(RUN); }
        });
        assertFalse(outcome.completed);
        assertFalse(outcome.cleanupReported);
        assertEquals(0, sampled.get());
    }
}
