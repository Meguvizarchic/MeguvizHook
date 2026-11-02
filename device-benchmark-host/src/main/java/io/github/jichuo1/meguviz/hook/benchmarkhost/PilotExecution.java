package io.github.jichuo1.meguviz.hook.benchmarkhost;

/** Runs one pilot and attempts teardown whenever configuration has been attempted. */
final class PilotExecution {
    interface Steps {
        boolean configure() throws Throwable;
        String sample() throws Throwable;
        boolean finish() throws Throwable;
    }

    static final class Outcome {
        final boolean completed;
        final boolean cleanupReported;
        final String detail;
        final Error error;

        Outcome(boolean completed, boolean cleanupReported, String detail, Error error) {
            this.completed = completed;
            this.cleanupReported = cleanupReported;
            this.detail = detail;
            this.error = error;
        }
    }

    private PilotExecution() { }

    static Outcome run(Steps steps) {
        boolean attempted = false;
        boolean completed = false;
        boolean cleanupReported = false;
        String detail = "not_configured";
        Error error = null;
        try {
            // The bridge can install a hook and then throw before returning to the caller.
            attempted = true;
            if (steps.configure()) {
                detail = steps.sample();
                completed = "completed".equals(detail);
            }
        } catch (Throwable failure) {
            detail = "exception:" + failure.getClass().getName();
            if (failure instanceof Error) error = (Error) failure;
        } finally {
            if (attempted) {
                try {
                    cleanupReported = steps.finish();
                    if (!cleanupReported) {
                        detail = PilotCompletion.cleanupFailure(detail, completed, "cleanup_not_confirmed");
                    }
                } catch (Throwable cleanupFailure) {
                    detail = PilotCompletion.cleanupFailure(detail, completed,
                            "cleanup_exception:" + cleanupFailure.getClass().getName());
                    error = PilotCompletion.errorToRethrow(error, cleanupFailure);
                }
            }
        }
        return new Outcome(completed, cleanupReported, detail, error);
    }
}
