package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Failed cleanup must not erase the initial failure, and fatal errors keep precedence. */
@SuppressWarnings("removal")
public class PilotCompletionTest {
    @Test
    public void retainsOperationAndCleanupFailures() {
        assertEquals("sample_failed;cleanup_not_confirmed",
                PilotCompletion.cleanupFailure("sample_failed", false, "cleanup_not_confirmed"));
        assertEquals("exception:original;cleanup_exception:cleanup",
                PilotCompletion.cleanupFailure("exception:original", false, "cleanup_exception:cleanup"));
        assertEquals("cleanup_not_confirmed",
                PilotCompletion.cleanupFailure("completed", true, "cleanup_not_confirmed"));
    }

    @Test
    public void fatalCleanupTakesPrecedenceOverNonfatalError() {
        Error ordinary = new AssertionError("original");
        Error fatal = new SimulatedVmError();
        assertSame(fatal, PilotCompletion.errorToRethrow(ordinary, fatal));
        assertSame(fatal, PilotCompletion.errorToRethrow(fatal, ordinary));
        assertSame(fatal, PilotCompletion.errorToRethrow(fatal, new ThreadDeath()));
        assertSame(ordinary, PilotCompletion.errorToRethrow(ordinary, new IllegalStateException("cleanup")));
        assertNull(PilotCompletion.errorToRethrow(null, new IllegalStateException("cleanup")));
        assertSame(ordinary, PilotCompletion.errorToRethrow(null, ordinary));
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
