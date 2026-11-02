package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import static org.junit.Assert.*;

public class B10RequestTest {
    private static final String RUN = "f83b50a7-cda5-4d58-9249-d37e2da9929d";
    private static final String PAIR = "184b0505-57a1-4075-8e33-c75f4247ebbc";
    private static final String SESSION = "a215634c-75bc-4202-9893-8a291abda755";

    @Test public void acceptsOnlyExplicitOneTransactionResourceRequests() {
        for (String control : new String[]{"A0", "A2"}) {
            for (String mode : new String[]{null, "unobserved"}) {
                PilotRequest request = PilotRequest.create(RUN, PAIR, SESSION, "A", "B10", control,
                        0, 1, 1, mode);
                assertNotNull(request);
                assertEquals("B10", request.bridgeWorkload());
                assertEquals("process_resource_snapshots", request.measurementKind());
                assertFalse(request.observed);
            }
            assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B10", control, 0, 1, 1, "observed"));
            assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B10", control, 1, 1, 1));
            assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B10", control, 0, 2, 1));
            assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B10", control, 0, 1, 2));
            assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B10", control,
                    0, 1, 1, "unobserved", "before"));
        }
    }

    @Test public void rejectsAliasedIdentitiesAndUnsupportedControls() {
        for (String[] ids : new String[][]{{RUN, RUN, SESSION}, {RUN, PAIR, RUN}, {RUN, PAIR, PAIR}}) {
            assertNull(PilotRequest.create(ids[0], ids[1], ids[2], "A", "B10", "A2", 0, 1, 1));
        }
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B10", "A1", 0, 1, 1));
        assertNull(PilotRequest.create(RUN, PAIR, SESSION, "A", "B10_OBSERVED", "A2", 0, 1, 1));
    }
}
