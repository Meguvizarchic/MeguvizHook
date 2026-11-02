package io.github.jichuo1.meguviz.hook.benchmarkhost;

/** Preserve the first operation failure and report cleanup without hiding a fatal event. */
final class PilotCompletion {
    private PilotCompletion() { }

    static String cleanupFailure(String firstDetail, boolean completed, String cleanupDetail) {
        return completed ? cleanupDetail : firstDetail + ";" + cleanupDetail;
    }

    static Error errorToRethrow(Error first, Throwable cleanup) {
        if (!(cleanup instanceof Error)) return first;
        if (first == null || (isFatal(cleanup) && !isFatal(first))) return (Error) cleanup;
        return first;
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }
}
