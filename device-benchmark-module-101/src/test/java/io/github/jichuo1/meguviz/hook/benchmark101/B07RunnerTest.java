package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Drives the exact transaction used by the module, with deterministic faults. */
public class B07RunnerTest {
    static final class Fixture implements B07Runner.Clock, B07Runner.Steps, B07Runner.Events {
        final List<String> trace = new ArrayList<>();
        String failureAt;
        Throwable installError;
        boolean retained;
        boolean unknownAtFailure;
        boolean wallStartRead, cpuStartRead, cpuEndRead, wallEndRead;
        boolean negativeWall;
        long observedWallStart;
        RegistrationReceipt.Status status = RegistrationReceipt.Status.INSTALLED;
        CloseReport report = new CloseReport(RegistrationState.CLOSED, true,
                CloseReport.EntryRestoration.RESTORED, 0, CloseReport.PhysicalRelease.NOT_CLAIMED, "ok");
        int cpuCalls;
        B07Runner runner() { return new B07Runner(this, this, this); }
        private void fault(String where) {
            if (where.equals(failureAt)) throw new IllegalStateException(where);
        }
        @Override public void prepare() { trace.add("prepare"); fault("prepare"); }
        @Override public long wall() { trace.add("wall"); fault("wall"); return negativeWall ? -1 : 100; }
        @Override public long cpu() {
            trace.add("cpu"); cpuCalls++; if (cpuCalls == 2) fault("cpu_end"); return 20 + cpuCalls;
        }
        @Override public RegistrationReceipt install() throws Throwable {
            trace.add("install");
            if (installError != null) throw installError;
            return RegistrationReceipt.of(HookId.of("test.b07"), status, "fake", 1L, "stub");
        }
        @Override public void retain(RegistrationReceipt r) { trace.add("retain"); retained = true; }
        @Override public void validate(RegistrationReceipt r) {
            trace.add("validate"); fault("validate");
            if (r.status() != RegistrationReceipt.Status.INSTALLED) throw new IllegalStateException("partial");
        }
        @Override public void hookCheck() { trace.add("hook"); fault("hook"); }
        @Override public void checkFramework() { trace.add("framework"); fault("framework"); }
        @Override public CloseReport closeRegistration() { trace.add("close"); fault("close"); return retained ? report : null; }
        @Override public void closeEnvironment() { trace.add("env_close"); fault("env_close"); }
        @Override public void postClose() { trace.add("post"); fault("post"); }
        @Override public void installed(RegistrationReceipt r, long ws, long cs, long ce, long we) {
            trace.add("event_install"); assertEquals(100L, ws); assertEquals(21L, cs);
            assertEquals(22L, ce); assertEquals(100L, we);
        }
        @Override public void hookChecked() { trace.add("event_hook"); }
        @Override public void configured() { trace.add("event_configured"); }
        @Override public void cleaned(CloseReport report) { trace.add("event_cleanup"); }
        @Override public void postClosed() { trace.add("event_post"); }
        @Override public void failed(String stage, Throwable failure, long ws, long cs, long ce, long we,
                                     boolean wsRead, boolean csRead, boolean ceRead, boolean weRead, boolean unknown) {
            trace.add("fail_" + stage); unknownAtFailure = unknown;
            wallStartRead = wsRead; cpuStartRead = csRead; cpuEndRead = ceRead; wallEndRead = weRead;
            observedWallStart = ws;
        }
    }

    @Test public void successUsesOneInstallAndOrderedClockAndCleanup() {
        Fixture f = new Fixture(); B07Runner runner = f.runner();
        assertTrue(runner.configure()); assertFalse(runner.configure());
        assertTrue(runner.finish()); assertFalse(runner.finish());
        assertEquals(Arrays.asList("prepare", "wall", "cpu", "install", "cpu", "wall", "retain", "validate",
                "event_install", "hook", "event_hook", "framework", "event_configured", "close", "env_close",
                "event_cleanup", "post", "event_post"), f.trace);
    }

    @Test public void finishedRunnerCannotStartAnInstallation() {
        Fixture f = new Fixture(); B07Runner runner = f.runner();
        assertFalse(runner.finish()); assertFalse(runner.configure());
        assertFalse(f.trace.contains("prepare")); assertFalse(f.trace.contains("install"));
    }

    @Test public void failedPreparationSkipsInstallAndStillClosesAvailableEnvironment() {
        Fixture f = new Fixture(); f.failureAt = "prepare"; B07Runner runner = f.runner();
        assertFalse(runner.configure()); assertFalse(f.unknownAtFailure); assertFalse(runner.finish());
        assertFalse(f.trace.contains("install")); assertTrue(f.trace.contains("env_close"));
        assertTrue(f.trace.contains("fail_install")); assertFalse(f.trace.contains("event_install"));
    }

    @Test public void installExceptionIsUnknownAndCannotBeCleanedIntoSuccess() {
        Fixture f = new Fixture(); f.installError = new IllegalStateException("unknown"); B07Runner runner = f.runner();
        assertFalse(runner.configure()); assertTrue(f.unknownAtFailure); assertFalse(runner.finish());
        assertTrue(f.trace.contains("close")); assertTrue(f.trace.contains("env_close"));
        assertTrue(f.trace.contains("fail_install")); assertTrue(f.trace.contains("fail_cleanup"));
        assertFalse(f.trace.contains("event_install")); assertFalse(f.trace.contains("post"));
    }

    @Test public void partialReceiptIsRetainedAndClosedButNotPromoted() {
        Fixture f = new Fixture(); f.status = RegistrationReceipt.Status.PARTIALLY_INSTALLED; B07Runner runner = f.runner();
        assertFalse(runner.configure()); assertTrue(f.retained); assertFalse(runner.finish());
        assertTrue(f.trace.contains("close")); assertTrue(f.trace.contains("env_close"));
        assertFalse(f.trace.contains("event_install")); assertFalse(f.trace.contains("post"));
    }

    @Test public void clockFailureAfterInstallRetainsReceiptBeforeCleanup() {
        Fixture f = new Fixture(); f.failureAt = "cpu_end"; B07Runner runner = f.runner();
        assertFalse(runner.configure()); assertTrue(f.retained); assertFalse(runner.finish());
        assertTrue(f.trace.indexOf("retain") < f.trace.indexOf("close"));
        assertTrue(f.wallStartRead); assertTrue(f.cpuStartRead); assertFalse(f.cpuEndRead); assertTrue(f.wallEndRead);
        assertTrue(f.trace.contains("env_close")); assertFalse(f.trace.contains("event_install"));
    }

    @Test public void unreadClocksAreDistinctFromReturnedNegativeReadings() {
        Fixture unread = new Fixture(); unread.failureAt = "wall"; B07Runner first = unread.runner();
        assertFalse(first.configure());
        assertFalse(unread.wallStartRead); assertFalse(unread.cpuStartRead);
        assertFalse(unread.cpuEndRead); assertFalse(unread.wallEndRead);
        assertFalse(first.finish());
        Fixture negative = new Fixture(); negative.negativeWall = true; B07Runner second = negative.runner();
        assertFalse(second.configure());
        assertTrue(negative.wallStartRead); assertEquals(-1L, negative.observedWallStart);
        assertTrue(negative.cpuStartRead); assertFalse(negative.cpuEndRead); assertFalse(negative.wallEndRead);
        assertFalse(second.finish());
    }

    @Test public void closeFailureStillClosesEnvironmentAndStopsPostCheck() {
        Fixture f = new Fixture(); f.failureAt = "close"; B07Runner runner = f.runner();
        assertTrue(runner.configure()); assertFalse(runner.finish());
        assertTrue(f.trace.contains("env_close")); assertTrue(f.trace.contains("fail_cleanup"));
        assertFalse(f.trace.contains("post"));
    }

    @Test public void failedVerificationAndPostCloseCannotYieldSuccess() {
        Fixture before = new Fixture(); before.failureAt = "hook"; B07Runner r1 = before.runner();
        assertFalse(r1.configure()); assertFalse(r1.finish());
        assertTrue(before.trace.contains("fail_hook_check")); assertTrue(before.trace.contains("close"));
        Fixture after = new Fixture(); after.failureAt = "post"; B07Runner r2 = after.runner();
        assertTrue(r2.configure()); assertFalse(r2.finish()); assertTrue(after.trace.contains("fail_post_close"));
    }

    @Test public void fatalInstallFailureKeepsIdentityAndStillRequiresCleanup() {
        Fixture f = new Fixture(); OutOfMemoryError fatal = new OutOfMemoryError("fixture"); f.installError = fatal;
        B07Runner runner = f.runner();
        try { runner.configure(); fail("fatal must escape"); }
        catch (OutOfMemoryError actual) { assertSame(fatal, actual); }
        assertFalse(runner.finish()); assertTrue(f.trace.contains("env_close"));
    }
}
