package io.github.jichuo1.meguviz.hook.cpubodyhost;

/** Independent controlled targets; never used by the uninstrumented benchmark. */
public final class ProbeTarget {
    private ProbeTarget() { }
    public static long noArgs() { ProbeLedger.original(); return 42L; }
    public static int add(int left, int right) { ProbeLedger.original(); return left + right; }
}
