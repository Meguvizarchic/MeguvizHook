package io.github.jichuo1.meguviz.hook.benchmark102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;

/** One expected ordinary failure call, followed by reported close and an unhooked call. */
final class B09Runner {
    interface Clock { long wall(); long cpu(); }
    interface Steps {
        void prepare() throws Throwable;
        RuntimeException expectedFailure();
        void install() throws Throwable;
        void validateInstall() throws Throwable;
        Object invoke() throws Throwable;
        State state() throws Throwable;
        void checkFramework() throws Throwable;
        Close closeRegistration() throws Throwable;
        void closeEnvironment() throws Throwable;
    }
    interface Events {
        void observed(String stage, Observation observation);
        void failed(String stage, Throwable failure, Observation observation);
    }
    static final class State {
        final long before, after, original;
        final String afterOutcome;
        final Object afterResult;
        final Throwable afterFailure, observationFailure;
        State(long before, long after, long original, String afterOutcome,
              Object afterResult, Throwable afterFailure, Throwable observationFailure) {
            this.before = before; this.after = after; this.original = original;
            this.afterOutcome = afterOutcome; this.afterResult = afterResult;
            this.afterFailure = afterFailure; this.observationFailure = observationFailure;
        }
    }
    static final class Close {
        final String source;
        final boolean rawRestored;
        final CloseReport report;
        Close(String source, boolean rawRestored, CloseReport report) {
            this.source = source; this.rawRestored = rawRestored; this.report = report;
        }
    }
    static final class Observation {
        Long wallStart, cpuStart, cpuEnd, wallEnd;
        State state;
        Close close;
        String outcome;
        Object result;
        boolean identityPreserved, afterIdentityPreserved, outcomeUnknown;
    }

    private final String failureCase, control;
    private final Clock clock;
    private final Steps steps;
    private final Events events;
    private RuntimeException expected;
    private Long wallStart, cpuStart, cpuEnd, wallEnd;
    private State state;
    private Close close;
    private Throwable actualFailure;
    private Object actualResult;
    private String outcome;
    private boolean called, configured, finished, unknown;

    B09Runner(String failureCase, String control, Clock clock, Steps steps, Events events) {
        if (!("before".equals(failureCase) || "after".equals(failureCase) || "original".equals(failureCase))
                || !("A0".equals(control) || "A2".equals(control))) {
            throw new IllegalArgumentException("invalid B09 case or control");
        }
        this.failureCase = failureCase; this.control = control;
        this.clock = clock; this.steps = steps; this.events = events;
    }

    boolean configure() {
        if (called || finished) return false;
        called = true;
        String stage = "installed";
        try {
            steps.prepare();
            expected = steps.expectedFailure();
            if (expected == null) throw new IllegalStateException("expected failure missing");
            state = steps.state();
            if (state == null || state.before != 0 || state.after != 0 || state.original != 0
                    || !"not_called".equals(state.afterOutcome)) {
                throw new IllegalStateException("B09 initial state is not fresh");
            }
            try { steps.install(); }
            catch (Throwable failure) { unknown = true; throw failure; }
            steps.validateInstall();
            events.observed("installed", observation());
            stage = "failure_call";
            measure();
            validateState(false);
            if (!"threw".equals(outcome) || actualFailure != expected) {
                throw new IllegalStateException("expected exception identity changed or original returned");
            }
            events.observed("failure_call", observation());
            stage = "configured";
            steps.checkFramework();
            events.observed("configured", observation());
            configured = true;
            return true;
        } catch (Throwable failure) {
            fail(stage, failure);
            return false;
        }
    }

    private void measure() throws Throwable {
        wallStart = clock.wall();
        cpuStart = clock.cpu();
        if (wallStart < 0 || cpuStart < 0) throw new IllegalStateException("clock unavailable");
        Throwable pending = null;
        try { actualResult = steps.invoke(); outcome = "returned"; }
        catch (Throwable failure) { actualFailure = failure; outcome = "threw"; }
        try { cpuEnd = clock.cpu(); } catch (Throwable failure) { pending = combine(pending, failure); }
        try { wallEnd = clock.wall(); } catch (Throwable failure) { pending = combine(pending, failure); }
        if (actualFailure instanceof Error) pending = combine(pending, actualFailure);
        if (pending != null) throw pending;
        if (cpuEnd < cpuStart || wallEnd < wallStart) throw new IllegalStateException("clock moved backwards");
    }

    boolean finish() {
        if (finished) return false;
        finished = true;
        try {
            Throwable pending = null;
            try { close = steps.closeRegistration(); }
            catch (Throwable failure) { unknown = true; pending = failure; }
            try { steps.closeEnvironment(); }
            catch (Throwable failure) { unknown = true; pending = combine(pending, failure); }
            if (pending != null) throw pending;
            if (unknown || !validClose(control, close)) {
                unknown = true;
                throw new IllegalStateException("B09 close not confirmed or operation outcome unknown");
            }
            events.observed("cleanup", observation());
        } catch (Throwable failure) {
            fail("cleanup", failure);
            return false;
        }
        if (!configured || unknown) return false;
        try {
            actualFailure = null; actualResult = null; outcome = null;
            try { actualResult = steps.invoke(); outcome = "returned"; }
            catch (Throwable failure) { actualFailure = failure; outcome = "threw"; }
            if (actualFailure instanceof Error) throw actualFailure;
            validateState(true);
            if ("original".equals(failureCase)) {
                if (!"threw".equals(outcome) || actualFailure != expected) {
                    throw new IllegalStateException("unhooked original failure identity changed");
                }
            } else if (!"returned".equals(outcome) || !(actualResult instanceof Long)
                    || (Long) actualResult != 42L) {
                throw new IllegalStateException("unhooked original result changed");
            }
            events.observed("post_close", observation());
            return true;
        } catch (Throwable failure) {
            fail("post_close", failure);
            return false;
        }
    }

    private void validateState(boolean post) throws Throwable {
        state = steps.state();
        if (state == null) throw new IllegalStateException("B09 state unavailable");
        if (state.observationFailure != null) throw state.observationFailure;
        long original = "before".equals(failureCase) ? 0 : 1;
        if (state.before != 1 || state.after != ("before".equals(failureCase) ? 0 : 1)
                || state.original != original + (post ? 1 : 0)) {
            throw new IllegalStateException("B09 callback or original count mismatch");
        }
        String after = "before".equals(failureCase) ? "not_called"
                : "after".equals(failureCase) ? "returned" : "threw";
        if (!after.equals(state.afterOutcome)) throw new IllegalStateException("B09 after outcome mismatch");
        if ("returned".equals(after) && (!(state.afterResult instanceof Long) || (Long) state.afterResult != 42L)) {
            throw new IllegalStateException("B09 after did not observe the successful original");
        }
        if ("threw".equals(after) && state.afterFailure != expected) {
            throw new IllegalStateException("B09 after did not observe the original failure identity");
        }
    }

    private Observation observation() {
        Observation value = new Observation();
        value.wallStart = wallStart; value.cpuStart = cpuStart; value.cpuEnd = cpuEnd; value.wallEnd = wallEnd;
        value.state = state; value.close = close; value.outcome = outcome; value.result = actualResult;
        value.identityPreserved = expected != null && actualFailure == expected;
        value.afterIdentityPreserved = expected != null && state != null && state.afterFailure == expected;
        value.outcomeUnknown = unknown;
        return value;
    }

    private void fail(String stage, Throwable failure) {
        state = null;
        try { state = steps.state(); }
        catch (Throwable observed) { failure = combine(failure, observed); }
        try { events.failed(stage, failure, observation()); }
        catch (Throwable logging) { failure = combine(failure, logging); }
        if (failure instanceof Error) throw (Error) failure;
    }

    static boolean validClose(String control, Close value) {
        if (value == null) return false;
        if ("A0".equals(control)) return "framework_unhook_contract".equals(value.source)
                && value.rawRestored && value.report == null;
        CloseReport report = value.report;
        return "A2".equals(control) && "sdk_close_report".equals(value.source) && report != null
                && report.newCallbacksRejected() && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED
                && report.inFlightAtClose() == 0 && report.physicalRelease() != null
                && (report.state() == RegistrationState.CLOSED || report.state() == RegistrationState.RETIRED_RESIDENT);
    }

    static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        if (next instanceof Error && (!(first instanceof Error) || (isFatal(next) && !isFatal(first)))) {
            next.addSuppressed(first); return next;
        }
        first.addSuppressed(next); return first;
    }

    @SuppressWarnings("removal")
    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }
}
