package io.github.jichuo1.meguviz.hook.benchmarkhost;

import java.util.UUID;

/** Validated one-process pilot request. Bounds prevent accidental unbounded work. */
final class PilotRequest {
    private static final int MAX_WARMUP = 1_000_000;
    private static final int MAX_SAMPLES = 200;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final long MAX_TOTAL_ITERATIONS = 10_000_000L;

    final String runId;
    final String pairId;
    final String sessionId;
    final String side;
    final String workload;
    final String control;
    final int warmup;
    final int samples;
    final int iterations;
    final boolean observed;
    final String failureCase;

    private PilotRequest(String runId, String pairId, String sessionId, String side,
                         String workload, String control, int warmup, int samples, int iterations, boolean observed,
                         String failureCase) {
        this.runId = runId;
        this.pairId = pairId;
        this.sessionId = sessionId;
        this.side = side;
        this.workload = workload;
        this.control = control;
        this.warmup = warmup;
        this.samples = samples;
        this.iterations = iterations;
        this.observed = observed;
        this.failureCase = failureCase;
    }

    static PilotRequest create(String runId, String pairId, String sessionId, String side,
                               String workload, String control, int warmup, int samples, int iterations) {
        return create(runId, pairId, sessionId, side, workload, control, warmup, samples, iterations, null);
    }

    static PilotRequest create(String runId, String pairId, String sessionId, String side,
                               String workload, String control, int warmup, int samples, int iterations,
                               String measurementMode) {
        return create(runId, pairId, sessionId, side, workload, control, warmup, samples, iterations,
                measurementMode, null);
    }

    static PilotRequest create(String runId, String pairId, String sessionId, String side,
                               String workload, String control, int warmup, int samples, int iterations,
                               String measurementMode, String failureCase) {
        if (!canonicalUuid(runId) || !canonicalUuid(pairId) || !canonicalUuid(sessionId)
                || (measurementMode != null && !"observed".equals(measurementMode)
                    && !"unobserved".equals(measurementMode))
                || !("A".equals(side) || "B".equals(side))
                || !("B01".equals(workload) || "B02".equals(workload) || "B03".equals(workload)
                    || "B04".equals(workload) || "B05".equals(workload) || "B06".equals(workload)
                    || "B07".equals(workload) || "B08".equals(workload) || "B09".equals(workload)
                    || "B10".equals(workload))
                || !("A0".equals(control) || "A2".equals(control))
                || (!"B09".equals(workload) && failureCase != null)
                || ("B09".equals(workload)
                    && (!("before".equals(failureCase) || "after".equals(failureCase)
                        || "original".equals(failureCase)) || "observed".equals(measurementMode)
                    || warmup != 0 || samples != 1 || iterations != 1
                    || runId.equals(pairId) || runId.equals(sessionId) || pairId.equals(sessionId)))
                || (("B07".equals(workload) || "B08".equals(workload))
                    && (!"A2".equals(control) || "observed".equals(measurementMode)
                    || warmup != 0 || samples != 1 || iterations != 1
                    || runId.equals(pairId) || runId.equals(sessionId) || pairId.equals(sessionId)))
                || ("B10".equals(workload) && ("observed".equals(measurementMode)
                    || warmup != 0 || samples != 1 || iterations != 1
                    || runId.equals(pairId) || runId.equals(sessionId) || pairId.equals(sessionId)))
                || warmup < 0 || warmup > MAX_WARMUP || samples < 1 || samples > MAX_SAMPLES
                || iterations < 1 || iterations > MAX_ITERATIONS
                || warmup + (long) samples * iterations > MAX_TOTAL_ITERATIONS
                || ("B06".equals(workload) && 4L * (warmup + (long) samples * iterations) > MAX_TOTAL_ITERATIONS)) {
            return null;
        }
        return new PilotRequest(runId, pairId, sessionId, side, workload, control, warmup, samples, iterations,
                "observed".equals(measurementMode), failureCase);
    }

    String measurementKind() {
        if ("B07".equals(workload)) return "install_wall_and_thread_cpu_ns";
        if ("B08".equals(workload)) return "close_with_inflight_wall_ns";
        if ("B09".equals(workload)) return "failure_call_wall_and_thread_cpu_ns";
        if ("B10".equals(workload)) return "process_resource_snapshots";
        return observed ? "thread_cpu_envelope_observed_ns" : "thread_cpu_envelope_ns";
    }

    String bridgeWorkload() {
        if ("B09".equals(workload)) {
            if ("before".equals(failureCase)) return "B09_BEFORE";
            if ("after".equals(failureCase)) return "B09_AFTER";
            if ("original".equals(failureCase)) return "B09_ORIGINAL";
            throw new IllegalStateException("invalid B09 failure case");
        }
        return observed ? workload + "_OBSERVED" : workload;
    }

    private static boolean canonicalUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
