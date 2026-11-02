package io.github.jichuo1.meguviz.hook.benchmark101;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Invalid or partially installed control hooks never become ready. */
public class ControlHookSetupTest {
    @Test
    public void bothHandlesMustBeReturnedBeforeReady() throws Throwable {
        Steps steps = new Steps();
        ControlHookSetup.install(steps);
        assertTrue(steps.ready);
        assertTrue(steps.unhooked.isEmpty());
    }

    @Test
    public void nullConfigureHandleNeverMarksReady() throws Throwable {
        Steps steps = new Steps();
        steps.configure = null;
        try {
            ControlHookSetup.install(steps);
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("outcome is unknown"));
        }
        assertFalse(steps.ready);
        assertTrue(steps.unhooked.isEmpty());
        assertFalse(steps.finishAttempted);
    }

    @Test
    public void nullFinishHandleCleansUpKnownConfigureHandle() throws Throwable {
        Steps steps = new Steps();
        steps.finish = null;
        try {
            ControlHookSetup.install(steps);
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("outcome is unknown"));
        }
        assertFalse(steps.ready);
        assertEquals(Arrays.asList("configure"), steps.unhooked);
    }

    @Test
    public void failedReadyDisarmsAndCleansBothHandlesInReverseOrder() throws Throwable {
        Steps steps = new Steps();
        RuntimeException failure = new IllegalStateException("ready failed after setting state");
        steps.readyFailure = failure;
        try {
            ControlHookSetup.install(steps);
            fail("expected failure");
        } catch (RuntimeException actual) {
            assertSame(failure, actual);
        }
        assertFalse(steps.ready);
        assertEquals(Arrays.asList("finish", "configure"), steps.unhooked);
    }

    @Test
    public void cleanupErrorDoesNotSkipOtherKnownHandle() throws Throwable {
        Steps steps = new Steps();
        RuntimeException failure = new IllegalStateException("ready failure");
        AssertionError cleanup = new AssertionError("finish unhook failure");
        steps.readyFailure = failure;
        steps.finishCleanupFailure = cleanup;
        try {
            ControlHookSetup.install(steps);
            fail("expected failure");
        } catch (AssertionError actual) {
            assertSame(cleanup, actual);
            assertSame(failure, actual.getSuppressed()[0]);
        }
        assertFalse(steps.ready);
        assertEquals(Arrays.asList("finish", "configure"), steps.unhooked);
    }

    private static final class Steps implements ControlHookSetup.Steps<String> {
        String configure = "configure";
        String finish = "finish";
        boolean finishAttempted;
        boolean ready;
        Throwable readyFailure;
        Throwable finishCleanupFailure;
        final List<String> unhooked = new ArrayList<String>();

        @Override public String installConfigure() { return configure; }
        @Override public String installFinish() { finishAttempted = true; return finish; }
        @Override public void ready() throws Throwable {
            ready = true;
            if (readyFailure != null) throw readyFailure;
        }
        @Override public void disarm() { ready = false; }
        @Override public void unhook(String handle) throws Throwable {
            unhooked.add(handle);
            if ("finish".equals(handle) && finishCleanupFailure != null) throw finishCleanupFailure;
        }
    }
}
