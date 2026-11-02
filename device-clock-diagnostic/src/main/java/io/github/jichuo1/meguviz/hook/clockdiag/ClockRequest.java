package io.github.jichuo1.meguviz.hook.clockdiag;

import java.util.UUID;

/** A bounded, explicitly requested diagnostic; no work starts for invalid input. */
final class ClockRequest {
    static final int MAX_WARMUP = 100;
    static final int MAX_SAMPLES = 200;

    final String runId;
    final int warmup;
    final int samples;

    private ClockRequest(String runId, int warmup, int samples) {
        this.runId = runId;
        this.warmup = warmup;
        this.samples = samples;
    }

    static ClockRequest create(String runId, int warmup, int samples) {
        if (!canonicalUuid(runId) || warmup < 0 || warmup > MAX_WARMUP
                || samples < 1 || samples > MAX_SAMPLES) {
            return null;
        }
        return new ClockRequest(runId, warmup, samples);
    }

    private static boolean canonicalUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
