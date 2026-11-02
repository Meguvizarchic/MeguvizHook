package io.github.jichuo1.meguviz.hook.benchmark101;

/** One installed call and one unhooked call, with three bounded self-resource snapshots. */
final class B10Runner {
    interface Steps {
        void prepare() throws Throwable;
        B10Resources.Snapshot resources();
        void install() throws Throwable;
        void validateInstall() throws Throwable;
        Object invoke() throws Throwable;
        State state() throws Throwable;
        void checkFramework() throws Throwable;
        B09Runner.Close closeRegistration() throws Throwable;
        void closeEnvironment() throws Throwable;
    }
    interface Events {
        void observed(String stage, Observation observation);
        void failed(String stage, Throwable failure, Observation observation);
    }
    static final class State {
        final long before, after, original;
        State(long before, long after, long original) { this.before = before; this.after = after; this.original = original; }
    }
    static final class Observation {
        State state;
        B10Resources.Snapshot resources;
        B09Runner.Close close;
        Object result;
        boolean outcomeUnknown;
    }

    private final String control;
    private final Steps steps;
    private final Events events;
    private State state;
    private B10Resources.Snapshot resources;
    private B09Runner.Close close;
    private Object result;
    private boolean called, configured, finished, unknown;

    B10Runner(String control, Steps steps, Events events) {
        if (!("A0".equals(control) || "A2".equals(control))) throw new IllegalArgumentException("invalid B10 control");
        this.control = control; this.steps = steps; this.events = events;
    }

    boolean configure() {
        if (called || finished) return false;
        called = true;
        String stage = "resource_before";
        try {
            steps.prepare();
            validateState(0, 0);
            snapshot();
            events.observed(stage, observation());
            stage = "installed";
            try { steps.install(); }
            catch (Throwable failure) { unknown = true; throw failure; }
            steps.validateInstall();
            validateState(0, 0);
            events.observed(stage, observation());
            stage = "resource_installed";
            result = steps.invoke();
            validateResult();
            validateState(1, 1);
            snapshot();
            events.observed(stage, observation());
            stage = "configured";
            steps.checkFramework();
            events.observed(stage, observation());
            configured = true;
            return true;
        } catch (Throwable failure) {
            fail(stage, failure);
            return false;
        }
    }

    boolean finish() {
        if (finished) return false;
        finished = true;
        try {
            Throwable pending = null;
            try { close = steps.closeRegistration(); }
            catch (Throwable failure) { unknown = true; pending = failure; }
            try { steps.closeEnvironment(); }
            catch (Throwable failure) { unknown = true; pending = B09Runner.combine(pending, failure); }
            if (pending != null) throw pending;
            if (unknown || !B09Runner.validClose(control, close)
                    || ("A2".equals(control) && close.report.physicalRelease()
                        != io.github.jichuo1.meguviz.hook.api.CloseReport.PhysicalRelease.NOT_CLAIMED)) {
                unknown = true;
                throw new IllegalStateException("B10 close not confirmed or operation outcome unknown");
            }
            events.observed("cleanup", observation());
        } catch (Throwable failure) {
            fail("cleanup", failure);
            return false;
        }
        if (!configured || unknown) return false;
        String stage = "post_close";
        try {
            result = null;
            result = steps.invoke();
            validateResult();
            validateState(1, 2);
            events.observed(stage, observation());
            stage = "resource_closed";
            snapshot();
            events.observed(stage, observation());
            return true;
        } catch (Throwable failure) {
            fail(stage, failure);
            return false;
        }
    }

    private void snapshot() throws Throwable {
        resources = null;
        resources = steps.resources();
        if (resources == null) throw new IllegalStateException("B10 resource snapshot absent");
        if (resources.failure != null) throw resources.failure;
        if (resources.startNs == null || resources.endNs == null || resources.startNs < 0
                || resources.endNs < resources.startNs) throw new IllegalStateException("B10 invalid resource interval");
    }

    private void validateResult() {
        if (!(result instanceof Long) || (Long) result != 42L) throw new IllegalStateException("B10 target result changed");
    }

    private void validateState(long callbacks, long originals) throws Throwable {
        state = steps.state();
        if (state == null || state.before != callbacks || state.after != callbacks || state.original != originals) {
            throw new IllegalStateException("B10 callback or original count mismatch");
        }
    }

    private Observation observation() {
        Observation value = new Observation();
        value.state = state; value.resources = resources; value.close = close;
        value.result = result; value.outcomeUnknown = unknown;
        return value;
    }

    private void fail(String stage, Throwable failure) {
        state = null;
        try { state = steps.state(); }
        catch (Throwable observed) { failure = B09Runner.combine(failure, observed); }
        try { events.failed(stage, failure, observation()); }
        catch (Throwable logging) { failure = B09Runner.combine(failure, logging); }
        if (failure instanceof Error) throw (Error) failure;
    }
}
