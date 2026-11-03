package io.github.jichuo1.meguviz.hook.benchmark.transport;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Interleaved A/B harness for a shared, noisy host: both implementations run in one JVM, in
 * alternating blocks (AB, BA, AB, ...), and each block is timed with the thread's CPU clock, so
 * load from other tenants affects both sides alike and descheduled time is not counted. Reports
 * the median CPU ns/op of each side, the median and interquartile range of the per-round ratio
 * B/A, and allocated bytes/op. Complements (does not replace) the JMH A/B benchmarks.
 * Host JVM only; not ART evidence.
 *
 * <p>Run: java -cp &lt;benchmark-jvm + hook-core + hook-api classes&gt; ...InterleavedAb [case ...]
 */
public final class InterleavedAb {
    private InterleavedAb() { }

    /** One side of a comparison; returns something derived from the work to defeat elimination. */
    interface Op {
        long run() throws Exception;
    }

    static volatile long sink;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        Map<String, Op[]> cases = InterleavedCases.all();
        String[] selected = args.length == 0 ? cases.keySet().toArray(new String[0]) : args;
        int rounds = Integer.getInteger("ab.rounds", 300);
        long blockNanos = Long.getLong("ab.blockMicros", 1000L) * 1000L;
        System.out.println("# interleaved A/B, rounds=" + rounds + ", block~" + blockNanos / 1000 + "us, "
                + System.getProperty("java.version") + " " + System.getProperty("os.arch"));
        System.out.println(String.format(Locale.ROOT, "%-34s %12s %12s %8s %8s %8s %10s %10s",
                "case", "A cpu ns/op", "B cpu ns/op", "B/A p50", "p25", "p75", "A B/op", "B B/op"));
        for (String name : selected) {
            Op[] pair = cases.get(name);
            if (pair == null) throw new IllegalArgumentException("unknown case " + name + ": " + cases.keySet());
            report(name, pair[0], pair[1], rounds, blockNanos);
        }
    }

    private static void report(String name, Op a, Op b, int rounds, long blockNanos) throws Exception {
        // Warm both sides and size a block so that it takes about blockNanos of CPU.
        for (int i = 0; i < 3; i++) { block(a, 20000); block(b, 20000); }
        int ops = calibrate(a, b, blockNanos);
        double[] aNs = new double[rounds];
        double[] bNs = new double[rounds];
        double[] ratio = new double[rounds];
        long aBytes = 0;
        long bBytes = 0;
        for (int round = 0; round < rounds; round++) {
            long[] first;
            long[] second;
            if ((round & 1) == 0) { first = block(a, ops); second = block(b, ops); }
            else { second = block(b, ops); first = block(a, ops); }
            aNs[round] = (double) first[0] / ops;
            bNs[round] = (double) second[0] / ops;
            ratio[round] = bNs[round] / aNs[round];
            aBytes += first[1];
            bBytes += second[1];
        }
        Arrays.sort(aNs);
        Arrays.sort(bNs);
        Arrays.sort(ratio);
        System.out.println(String.format(Locale.ROOT, "%-34s %12.1f %12.1f %8.3f %8.3f %8.3f %10.0f %10.0f",
                name, median(aNs), median(bNs), median(ratio), ratio[rounds / 4], ratio[rounds * 3 / 4],
                (double) aBytes / ((long) ops * rounds), (double) bBytes / ((long) ops * rounds)));
    }

    private static int calibrate(Op a, Op b, long blockNanos) throws Exception {
        int ops = 1000;
        long[] probe = block(a, ops);
        long[] probeB = block(b, ops);
        double perOp = Math.max(1.0, (probe[0] + probeB[0]) / (2.0 * ops));
        return (int) Math.max(1, Math.min(1_000_000, blockNanos / perOp));
    }

    private static long[] block(Op op, int ops) throws Exception {
        long thread = Thread.currentThread().getId();
        long bytes = THREADS.getThreadAllocatedBytes(thread);
        long cpu = THREADS.getCurrentThreadCpuTime();
        long acc = 0;
        for (int i = 0; i < ops; i++) acc += op.run();
        long cpuUsed = THREADS.getCurrentThreadCpuTime() - cpu;
        long allocated = THREADS.getThreadAllocatedBytes(thread) - bytes;
        sink += acc;
        return new long[] {cpuUsed, allocated};
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return (n & 1) == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    static Map<String, Op[]> newCases() { return new LinkedHashMap<String, Op[]>(); }
}
