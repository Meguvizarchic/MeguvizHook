package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The bridge may commit before its result is returned to the host. */
public class PilotExecutionTest {
    @Test
    public void configureThrowAfterCommitStillAttemptsFinishAndCannotPass() {
        Steps steps = new Steps();
        steps.installThenThrow = new IllegalStateException("bridge failed after commit");
        PilotExecution.Outcome outcome = PilotExecution.run(steps);
        assertEquals(1, steps.configures);
        assertEquals(0, steps.samples);
        assertEquals(1, steps.finishes);
        assertFalse(steps.installed);
        assertFalse(outcome.completed);
        assertTrue(outcome.cleanupReported);
        assertEquals("exception:java.lang.IllegalStateException", outcome.detail);
    }

    @Test
    public void configureFalseStillAttemptsFinishAndRemainsFailure() {
        Steps steps = new Steps();
        steps.configured = false;
        steps.finishResult = false;
        PilotExecution.Outcome outcome = PilotExecution.run(steps);
        assertEquals(0, steps.samples);
        assertEquals(1, steps.finishes);
        assertFalse(outcome.completed);
        assertFalse(outcome.cleanupReported);
        assertEquals("not_configured;cleanup_not_confirmed", outcome.detail);
    }

    @Test
    public void configureErrorIsRetainedWhileFinishRuns() {
        Steps steps = new Steps();
        AssertionError failure = new AssertionError("bridge failed after commit");
        steps.installThenThrow = failure;
        PilotExecution.Outcome outcome = PilotExecution.run(steps);
        assertSame(failure, outcome.error);
        assertEquals(1, steps.finishes);
        assertFalse(outcome.completed);
        assertTrue(outcome.cleanupReported);
    }

    @Test
    public void successfulSamplesRequireConfirmedFinish() {
        Steps steps = new Steps();
        PilotExecution.Outcome success = PilotExecution.run(steps);
        assertTrue(success.completed);
        assertTrue(success.cleanupReported);
        assertEquals("completed", success.detail);
        assertEquals(1, steps.samples);
        assertEquals(1, steps.finishes);

        Steps unconfirmed = new Steps();
        unconfirmed.finishResult = false;
        PilotExecution.Outcome failure = PilotExecution.run(unconfirmed);
        assertTrue(failure.completed);
        assertFalse(failure.cleanupReported);
        assertEquals("cleanup_not_confirmed", failure.detail);
    }

    private static final class Steps implements PilotExecution.Steps {
        int configures;
        int samples;
        int finishes;
        boolean configured = true;
        boolean finishResult = true;
        boolean installed;
        Throwable installThenThrow;

        @Override public boolean configure() throws Throwable {
            configures++;
            installed = true;
            if (installThenThrow != null) throw installThenThrow;
            return configured;
        }

        @Override public String sample() {
            samples++;
            return "completed";
        }

        @Override public boolean finish() {
            finishes++;
            if (finishResult) installed = false;
            return finishResult;
        }
    }
}
