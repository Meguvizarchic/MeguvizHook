package io.github.jichuo1.meguviz.hook.cpubody102;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Only handles actually returned by the framework are eligible for cleanup. */
public class ControlHookSetupTest {
    @Test public void onlyBothReturnedHandlesCanBePublished() throws Throwable {
        Steps success = new Steps();
        ControlHookSetup.install(success);
        assertTrue(success.ready);
        assertEquals(Collections.emptyList(), success.unhooked);
        Steps missing = new Steps(); missing.first = null;
        try { ControlHookSetup.install(missing); fail("expected missing handle"); }
        catch (IllegalStateException failure) { assertTrue(failure.getMessage().contains("unknown")); }
        assertFalse(missing.ready); assertFalse(missing.secondAttempted); assertTrue(missing.unhooked.isEmpty());
    }

    @Test public void nullOrThrownSecondHandleCleansOnlyTheFirst() throws Throwable {
        Steps missing = new Steps(); missing.second = null;
        try { ControlHookSetup.install(missing); fail("expected missing handle"); }
        catch (IllegalStateException failure) { assertTrue(failure.getMessage().contains("unknown")); }
        assertFalse(missing.ready); assertEquals(Arrays.asList("configure"), missing.unhooked);
        Steps failing = new Steps(); RuntimeException original = new RuntimeException("unknown second outcome");
        failing.installFailure = original;
        try { ControlHookSetup.install(failing); fail("expected original"); }
        catch (RuntimeException failure) { assertSame(original, failure); }
        assertEquals(Arrays.asList("configure"), failing.unhooked);
    }

    @Test public void publicationFailureDisarmsAndUnhooksInReverseOrder() throws Throwable {
        Steps steps = new Steps(); RuntimeException original = new RuntimeException("publication");
        steps.readyFailure = original;
        try { ControlHookSetup.install(steps); fail("expected original"); }
        catch (RuntimeException failure) { assertSame(original, failure); }
        assertFalse(steps.ready); assertEquals(Arrays.asList("finish", "configure"), steps.unhooked);
    }

    @Test public void nonfatalCleanupErrorsPreserveOriginalAndContinue() throws Throwable {
        Steps steps = new Steps(); RuntimeException original = new RuntimeException("publication");
        AssertionError cleanup = new AssertionError("finish unhook");
        steps.readyFailure = original; steps.finishCleanup = cleanup;
        try { ControlHookSetup.install(steps); fail("expected original"); }
        catch (RuntimeException failure) { assertSame(original, failure); assertSame(cleanup, failure.getSuppressed()[0]); }
        assertEquals(Arrays.asList("finish", "configure"), steps.unhooked);
    }

    @Test public void fatalOriginalIsRethrownWithoutCleanup() throws Throwable {
        Steps steps = new Steps(); ThreadDeath fatal = new ThreadDeath(); steps.installFailure = fatal;
        try { ControlHookSetup.install(steps); fail("expected fatal"); }
        catch (ThreadDeath failure) { assertSame(fatal, failure); }
        assertFalse(steps.disarmed); assertTrue(steps.unhooked.isEmpty());
    }

    @Test public void fatalCleanupTakesPriorityAndStopsFurtherCleanup() throws Throwable {
        Steps steps = new Steps(); RuntimeException original = new RuntimeException("publication");
        ThreadDeath fatal = new ThreadDeath(); steps.readyFailure = original; steps.finishCleanup = fatal;
        try { ControlHookSetup.install(steps); fail("expected fatal"); }
        catch (ThreadDeath failure) { assertSame(fatal, failure); assertSame(original, failure.getSuppressed()[0]); }
        assertEquals(Arrays.asList("finish"), steps.unhooked);
    }

    @Test public void sameThrowableIsNotSelfSuppressedAndCheckedFailureKeepsIdentity() throws Throwable {
        Steps steps = new Steps(); Exception original = new ReflectiveOperationException("publication");
        steps.readyFailure = original; steps.finishCleanup = original;
        try { ControlHookSetup.install(steps); fail("expected original"); }
        catch (ReflectiveOperationException failure) { assertSame(original, failure); assertEquals(0, failure.getSuppressed().length); }
        assertEquals(Arrays.asList("finish", "configure"), steps.unhooked);
    }

    private static final class Steps implements ControlHookSetup.Steps<String> {
        String first = "configure", second = "finish";
        boolean secondAttempted, ready, disarmed;
        Throwable installFailure, readyFailure, finishCleanup;
        final List<String> unhooked = new ArrayList<>();
        @Override public String installConfigure() { return first; }
        @Override public String installFinish() throws Throwable {
            secondAttempted = true; if (installFailure != null) throw installFailure; return second;
        }
        @Override public void ready() throws Throwable { ready = true; if (readyFailure != null) throw readyFailure; }
        @Override public void disarm() { disarmed = true; ready = false; }
        @Override public void unhook(String handle) throws Throwable {
            unhooked.add(handle); if ("finish".equals(handle) && finishCleanup != null) throw finishCleanup;
        }
    }
}
