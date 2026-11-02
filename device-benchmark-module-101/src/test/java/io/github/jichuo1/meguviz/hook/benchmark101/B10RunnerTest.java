package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Drives the production B10 transaction without Android, devices, or a Hook framework. */
public class B10RunnerTest {
    static final class Failure {
        final String stage;
        final Throwable error;
        final B10Runner.Observation observation;
        Failure(String stage, Throwable error, B10Runner.Observation observation) {
            this.stage = stage; this.error = error; this.observation = observation;
        }
    }
    static final class Fixture implements B10Runner.Steps, B10Runner.Events {
        final String control;
        final List<String> trace = new ArrayList<>();
        final List<B10Runner.Observation> snapshots = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();
        long before, after, original;
        int invokes, reads;
        boolean installed, closed, unavailable, duplicateOriginal, leakedCallback, badResult;
        RegistrationState closeState = RegistrationState.CLOSED;
        CloseReport.PhysicalRelease physical = CloseReport.PhysicalRelease.NOT_CLAIMED;
        String fault, failedStage;
        int clockFaultAt;
        Throwable installError, invokeError, closeError, stateError, clockError;
        B10Runner.Observation failed, cleaned, posted;
        Throwable failure;
        Fixture(String control) { this.control = control; }
        B10Runner runner() { return new B10Runner(control, this, this); }
        private void failAt(String stage) { if (stage.equals(fault)) throw new IllegalStateException(stage); }
        @Override public void prepare() { trace.add("prepare"); failAt("prepare"); }
        @Override public B10Resources.Snapshot resources() {
            trace.add("resource"); reads++;
            B10Resources.Snapshot value = new B10Resources.Snapshot();
            value.startNs = (long) reads * 10; value.endNs = value.startNs + 2;
            if (!unavailable) {
                value.rssBytes = 4096L; value.rssStatus = "observed";
                value.threadCount = 1L; value.threadStatus = "observed"; value.fdCount = 3L; value.fdStatus = "observed";
            }
            if (clockFaultAt == reads) { value.endNs = null; value.failure = clockError == null ? new IllegalStateException("clock") : clockError; }
            return value;
        }
        @Override public void install() throws Throwable { trace.add("install"); installed = true; if (installError != null) throw installError; }
        @Override public void validateInstall() { trace.add("validate"); failAt("validate"); }
        @Override public Object invoke() throws Throwable {
            trace.add("invoke"); invokes++;
            if (!closed || leakedCallback) { before++; after++; }
            original += duplicateOriginal ? 2 : 1;
            if (invokeError != null) throw invokeError;
            return badResult ? 41L : 42L;
        }
        @Override public B10Runner.State state() throws Throwable {
            if (invokes > 0 && stateError != null) throw stateError;
            return new B10Runner.State(before, after, original);
        }
        @Override public void checkFramework() { trace.add("framework"); failAt("framework"); }
        @Override public B09Runner.Close closeRegistration() throws Throwable {
            trace.add("close"); closed = true;
            if (closeError != null) throw closeError;
            if ("A0".equals(control)) return new B09Runner.Close("framework_unhook_contract", installed, null);
            return new B09Runner.Close("sdk_close_report", false, installed
                    ? new CloseReport(closeState, true, CloseReport.EntryRestoration.RESTORED, 0, physical, "controlled") : null);
        }
        @Override public void closeEnvironment() { trace.add("env_close"); failAt("env_close"); }
        @Override public void observed(String stage, B10Runner.Observation value) {
            trace.add("event_" + stage);
            if (stage.startsWith("resource_")) snapshots.add(value);
            if (stage.equals("cleanup")) cleaned = value;
            if (stage.equals("post_close")) posted = value;
        }
        @Override public void failed(String stage, Throwable error, B10Runner.Observation value) {
            trace.add("fail_" + stage); failedStage = stage; failed = value; failure = error;
            failures.add(new Failure(stage, error, value));
        }
        Failure failureAt(String stage) {
            for (Failure value : failures) if (stage.equals(value.stage)) return value;
            return null;
        }
    }

    @Test public void bothControlsUseThreeSnapshotsAndExactlyOneCallPerSideOfClose() {
        for (String control : new String[]{"A0", "A2"}) {
            Fixture f = new Fixture(control); B10Runner r = f.runner(); assertTrue(r.configure()); assertFalse(r.configure());
            assertEquals(1, f.invokes); assertTrue(r.finish()); assertFalse(r.finish()); assertEquals(2, f.invokes);
            assertEquals(3, f.snapshots.size()); assertEquals(0, f.snapshots.get(0).state.original);
            assertNull(f.snapshots.get(0).result); assertEquals(1, f.snapshots.get(1).state.original);
            assertEquals(2, f.snapshots.get(2).state.original); assertEquals(1, f.snapshots.get(2).state.before);
            assertEquals(1, f.snapshots.get(2).state.after); assertEquals(42L, f.posted.result);
            assertEquals(Arrays.asList("prepare", "resource", "event_resource_before", "install", "validate", "event_installed",
                    "invoke", "resource", "event_resource_installed", "framework", "event_configured", "close", "env_close",
                    "event_cleanup", "invoke", "event_post_close", "resource", "event_resource_closed"), f.trace);
        }
    }
    @Test public void unavailableMetricsDoNotSkipFunctionOrCleanupAndStayNull() {
        Fixture f = new Fixture("A2"); f.unavailable = true; B10Runner r = f.runner();
        assertTrue(r.configure()); assertTrue(r.finish()); assertEquals(3, f.snapshots.size());
        for (B10Runner.Observation value : f.snapshots) {
            assertNull(value.resources.rssBytes); assertNull(value.resources.threadCount); assertNull(value.resources.fdCount);
            assertEquals("unavailable", value.resources.rssStatus); assertEquals("unavailable", value.resources.fdStatus);
        }
    }
    @Test public void unknownInstallationIsNeverReplayedOrReportedAsKnownCleanup() {
        Fixture f = new Fixture("A2"); f.installError = new IllegalStateException("unknown"); B10Runner r = f.runner();
        assertFalse(r.configure()); assertTrue(f.failed.outcomeUnknown); assertEquals("installed", f.failedStage);
        assertNotNull(f.failed.resources); assertFalse(r.configure()); assertFalse(r.finish()); assertFalse(r.finish());
        assertEquals(0, f.invokes); assertEquals(1, java.util.Collections.frequency(f.trace, "install"));
        assertTrue(f.trace.contains("env_close")); assertNull(f.cleaned);
    }
    @Test public void preparationReceiptOrFrameworkFailuresDoNotInventLaterEvents() {
        for (String fault : new String[]{"prepare", "validate", "framework"}) {
            Fixture f = new Fixture("A0"); f.fault = fault; B10Runner r = f.runner(); assertFalse(r.configure());
            assertEquals(fault.equals("prepare") ? "resource_before" : fault.equals("validate") ? "installed" : "configured", f.failedStage);
            assertFalse(r.finish()); assertTrue(f.trace.contains("env_close")); assertNull(f.posted);
            assertEquals(fault.equals("framework") ? 1 : 0, f.invokes);
        }
    }
    @Test public void wrongResultDuplicateOriginalAndUnexpectedInitialCallbacksAreRejected() {
        for (String fault : new String[]{"result", "duplicate", "initial"}) {
            Fixture f = new Fixture("A2"); f.badResult = fault.equals("result"); f.duplicateOriginal = fault.equals("duplicate");
            if (fault.equals("initial")) f.before = 1;
            B10Runner r = f.runner(); assertFalse(r.configure()); assertFalse(r.finish()); assertNull(f.posted);
            assertEquals(fault.equals("initial") ? 0 : 1, f.invokes);
        }
    }
    @Test public void clockFailureAtEachResourceStageKeepsPartialSnapshotAndDoesNotRepeatIt() {
        for (int at : new int[]{1, 2, 3}) {
            Fixture f = new Fixture("A2"); f.clockFaultAt = at; B10Runner r = f.runner();
            assertEquals(at == 3, r.configure()); assertFalse(r.finish());
            String stage = at == 1 ? "resource_before" : at == 2 ? "resource_installed" : "resource_closed";
            Failure clock = f.failureAt(stage); assertNotNull(clock); assertEquals(stage, clock.stage);
            assertEquals(Long.valueOf(at * 10L), clock.observation.resources.startNs); assertNull(clock.observation.resources.endNs);
            assertSame(clock.error, clock.observation.resources.failure); assertFalse(clock.observation.outcomeUnknown);
            if (at == 1) {
                assertEquals(2, f.failures.size()); assertEquals("resource_before", f.failures.get(0).stage);
                assertEquals("cleanup", f.failures.get(1).stage); assertNull(f.cleaned);
                Failure cleanup = f.failureAt("cleanup"); assertNotNull(cleanup); assertTrue(cleanup.observation.outcomeUnknown);
                assertSame(clock.observation.resources, cleanup.observation.resources); assertNotNull(cleanup.observation.close);
                assertNull(cleanup.observation.close.report); assertFalse(f.trace.contains("install"));
            } else {
                assertEquals(1, f.failures.size()); assertNull(f.failureAt("cleanup")); assertNotNull(f.cleaned);
            }
            assertFalse(r.configure()); assertFalse(r.finish()); assertEquals(at, f.reads);
            assertEquals(at, java.util.Collections.frequency(f.trace, "resource"));
            assertEquals(at == 1 ? 0 : at == 2 ? 1 : 2, f.invokes); assertTrue(f.trace.contains("env_close"));
        }
    }
    @Test public void closeAndEnvironmentFailuresKeepUnknownAndSkipTheUnhookedCall() {
        for (String fault : new String[]{"close", "env_close"}) {
            Fixture f = new Fixture("A2");
            if (fault.equals("close")) f.closeError = new IllegalStateException("close"); else f.fault = fault;
            B10Runner r = f.runner(); assertTrue(r.configure()); assertFalse(r.finish());
            assertEquals("cleanup", f.failedStage); assertTrue(f.failed.outcomeUnknown); assertEquals(1, f.invokes);
            assertTrue(f.trace.contains("env_close")); assertNotNull(f.failed.resources);
            if (fault.equals("env_close")) assertNotNull(f.failed.close.report);
        }
    }
    @Test public void reportedRetiredResidentIsAcceptedButPhysicalReleaseAndInvalidStatesAreNot() {
        Fixture retired = new Fixture("A2"); retired.closeState = RegistrationState.RETIRED_RESIDENT; B10Runner r = retired.runner();
        assertTrue(r.configure()); assertTrue(r.finish()); assertEquals(RegistrationState.RETIRED_RESIDENT, retired.cleaned.close.report.state());
        for (CloseReport.PhysicalRelease physical : new CloseReport.PhysicalRelease[]{CloseReport.PhysicalRelease.RELEASED, CloseReport.PhysicalRelease.UNKNOWN, null}) {
            Fixture f = new Fixture("A2"); f.physical = physical; r = f.runner(); assertTrue(r.configure()); assertFalse(r.finish());
            assertTrue(f.failed.outcomeUnknown); assertEquals(1, f.invokes);
        }
        Fixture f = new Fixture("A2"); f.closeState = null; r = f.runner(); assertTrue(r.configure()); assertFalse(r.finish());
    }
    @Test public void postCloseCallbacksOrChangedOriginalAreRejectedWithoutAnotherSnapshot() {
        for (boolean callback : new boolean[]{false, true}) {
            Fixture f = new Fixture("A0"); B10Runner r = f.runner(); assertTrue(r.configure());
            f.leakedCallback = callback; f.duplicateOriginal = !callback; assertFalse(r.finish());
            assertEquals("post_close", f.failedStage); assertEquals(2, f.reads); assertEquals(2, f.invokes);
        }
    }
    @Test public void fatalInvocationResourceAndStateErrorsEscapeWithOriginalIdentity() {
        for (String fault : new String[]{"invoke", "resource", "state"}) {
            Fixture f = new Fixture("A2"); OutOfMemoryError fatal = new OutOfMemoryError("controlled runner fixture");
            if (fault.equals("invoke")) f.invokeError = fatal;
            if (fault.equals("resource")) { f.clockFaultAt = 2; f.clockError = fatal; }
            if (fault.equals("state")) f.stateError = fatal;
            B10Runner r = f.runner();
            try { r.configure(); fail("fatal must escape"); } catch (OutOfMemoryError actual) { assertSame(fatal, actual); }
            assertSame(fatal, f.failure); f.stateError = null; assertFalse(r.finish()); assertEquals(1, f.invokes);
        }
    }
    @Test public void finishBeforeConfigureConsumesRunnerAndDoesNotInstall() {
        Fixture f = new Fixture("A0"); B10Runner r = f.runner();
        assertFalse(r.finish()); assertFalse(r.configure()); assertFalse(r.finish());
        assertFalse(f.trace.contains("install")); assertEquals(1, java.util.Collections.frequency(f.trace, "close"));
    }
}
