package io.github.jichuo1.meguviz.hook.cpubodyhost;

/** Explicit bridge; absent module means the diagnostic does not run. */
public final class ProbeControl {
    private ProbeControl() { }
    public static boolean configure(String runId, String backend, String workload, String control,
                                    int warmup, int samples) { return false; }
    public static boolean finish(String runId) { return false; }
}
