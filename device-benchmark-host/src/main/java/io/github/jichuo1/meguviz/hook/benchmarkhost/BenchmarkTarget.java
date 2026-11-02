package io.github.jichuo1.meguviz.hook.benchmarkhost;

/** Pilot targets and direct loops; the target bodies intentionally contain no counters or logging. */
public final class BenchmarkTarget {
    private BenchmarkTarget() { }

    public static long noArgs() {
        return 42L;
    }

    public static int add(int left, int right) {
        return left + right;
    }

    /** Separate target for the single-slot writeback workload. */
    public static int mutableAdd(int left, int right) {
        return left + right;
    }

    /** Separate target for the single-proceed around workload. */
    public static long aroundValue() {
        return 42L;
    }

    /** Four active invocations for a top-level depth of three, including depth zero. */
    public static int reentrant(int depth) {
        if (depth < 0 || depth > 3) throw new IllegalArgumentException("invalid reentrant depth");
        return depth == 0 ? 1 : 1 + reentrant(depth - 1);
    }

    public static long snapshot(int[] box, int delta) {
        box[0] += delta;
        return 42L;
    }

    public static long loopB01(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) {
            sum += noArgs();
        }
        return sum;
    }

    public static long loopB02(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) {
            sum += add(3, 4);
        }
        return sum;
    }

    public static long loopB03(int iterations) {
        int[] box = new int[]{0};
        long sum = 0L;
        for (int i = 0; i < iterations; i++) {
            sum += snapshot(box, 1);
        }
        return box[0] == iterations ? sum : Long.MIN_VALUE;
    }

    public static long loopB04(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += mutableAdd(3, 4);
        return sum;
    }

    public static long loopB05(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += aroundValue();
        return sum;
    }

    public static long loopB06(int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) sum += reentrant(3);
        return sum;
    }
}
