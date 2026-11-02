package io.github.jichuo1.meguviz.hook.benchmarkhost;

/** Validates counts for the instrumented workload; it is not a performance threshold. */
final class ObservedCounts {
    private ObservedCounts() { }

    static boolean matches(String workload, int iterations, long[] counts) {
        if (counts == null || counts.length != 4 || iterations < 0) return false;
        long calls = "B06".equals(workload) ? 4L * iterations : iterations;
        long after = "B01".equals(workload) || "B03".equals(workload)
                || "B05".equals(workload) || "B06".equals(workload) ? calls : 0L;
        return ("B01".equals(workload) || "B02".equals(workload) || "B03".equals(workload)
                || "B04".equals(workload) || "B05".equals(workload) || "B06".equals(workload))
                && counts[0] == calls && counts[1] == after
                && counts[2] == calls && counts[3] == 0L;
    }

    static String fields(long[] counts) {
        return ",\"original_calls\":" + counts[2] + ",\"hook_before_calls\":" + counts[0]
                + ",\"hook_after_calls\":" + counts[1] + ",\"off_thread_calls\":" + counts[3];
    }
}
