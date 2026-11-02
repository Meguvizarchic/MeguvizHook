package io.github.jichuo1.meguviz.hook.benchmarkhost;
/** Test-only descriptor fixture, not the Android host implementation. */
public final class B07Target {
    private B07Target() { }
    public static long value() { return 42L; }
    public static long originalCount() { return 0L; }
    public static final Error FATAL = new OutOfMemoryError("fixture fatal");
    public static long failsFatal() { throw FATAL; }
}
