package io.github.jichuo1.meguviz.hook.benchmarkhost;

/** Explicit bridge for an optional measurement module; the host alone never starts measurements. */
public final class BenchmarkControl {
    private BenchmarkControl() { }

    /** Returns true only if the module installed the selected target hook. */
    public static boolean configure(String runId, String pairId, String sessionId, String side,
                                    String workload, String control, int warmup, int samples, int iterations) {
        return false;
    }

    /** Returns true only when the module reports successful teardown of the selected target hook. */
    public static boolean finish(String runId) {
        return false;
    }
}
