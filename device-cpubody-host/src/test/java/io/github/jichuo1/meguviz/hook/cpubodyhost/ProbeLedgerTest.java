package io.github.jichuo1.meguviz.hook.cpubodyhost;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ProbeLedgerTest {
    @Test public void cleanupFailureCannotReplaceOriginalExceptForFatalVmEvents() {
        RuntimeException original = new RuntimeException("original");
        assertSame(original, FailurePriority.combine(original, new AssertionError("cleanup")));
        ThreadDeath fatalOriginal = new ThreadDeath();
        assertSame(fatalOriginal, FailurePriority.combine(fatalOriginal, new AssertionError("cleanup")));
        ThreadDeath fatalCleanup = new ThreadDeath();
        assertSame(fatalCleanup, FailurePriority.combine(original, fatalCleanup));
        assertSame(original, FailurePriority.combine(original, original));
    }
    @Test public void originalWithoutMatchingBeforeFailsAndIsNotReplayed() {
        ProbeLedger.initialize(1, true);
        ProbeLedger.begin(0);
        assertEquals(42L, ProbeTarget.noArgs());
        assertEquals(1, ProbeLedger.originalCounts[0]);
        assertEquals("original_mismatch", ProbeLedger.reason(0));
    }

    @Test public void duplicateOriginalAndWrongThreadAreRejected() throws Exception {
        ProbeLedger.initialize(1, false);
        ProbeLedger.begin(0);
        ProbeLedger.beforeCounts[0] = 1; ProbeLedger.state[1] = 1;
        assertEquals(7, ProbeTarget.add(3, 4));
        assertEquals(7, ProbeTarget.add(3, 4));
        assertEquals("original_mismatch", ProbeLedger.reason(0));

        ProbeLedger.initialize(1, false);
        Thread other = new Thread(() -> ProbeTarget.add(3, 4));
        other.start(); other.join();
        assertEquals("cross_thread", ProbeLedger.reason(0));
    }

    @Test public void lateCrossThreadFaultAfterLastCallIsNotReportedAsSuccess() throws Exception {
        ProbeLedger.initialize(1, false);
        ProbeLedger.begin(0);
        ProbeLedger.beforeCounts[0] = 1; ProbeLedger.state[1] = 1;
        ProbeLedger.present[0] = 3;
        assertEquals(7, ProbeTarget.add(3, 4));
        assertEquals("", ProbeLedger.reason(0));
        Thread late = new Thread(() -> ProbeTarget.add(3, 4));
        late.start(); late.join();
        assertEquals("cross_thread", ProbeLedger.finalReason("", true));
    }

    @Test public void unknownNonzeroFaultCannotBecomeSuccess() {
        ProbeLedger.initialize(1, false);
        ProbeLedger.fault.set(99);
        assertEquals("unknown_fault", ProbeLedger.finalReason("", true));
    }
}
