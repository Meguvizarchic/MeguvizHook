package io.github.jichuo1.meguviz.hook.cpubodyhost;

/** Retain the original failure unless cleanup reports a fatal VM event. */
final class FailurePriority {
    private FailurePriority() { }
    static Throwable combine(Throwable original, Throwable cleanup) {
        if (original == null) return cleanup;
        if (cleanup == original) return original;
        if (isFatal(cleanup) && !isFatal(original)) {
            cleanup.addSuppressed(original);
            return cleanup;
        }
        original.addSuppressed(cleanup);
        return original;
    }
    static boolean isFatal(Throwable value) {
        return value instanceof VirtualMachineError || value instanceof ThreadDeath;
    }
}
