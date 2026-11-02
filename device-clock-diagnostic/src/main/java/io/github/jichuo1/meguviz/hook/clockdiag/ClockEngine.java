package io.github.jichuo1.meguviz.hook.clockdiag;

/** Emits raw same-thread adjacent clock reads; it does not estimate callback CPU cost. */
final class ClockEngine {
    interface Clock {
        long cpuNanos();
        long elapsedNanos();
    }

    interface Sink {
        void reading(String phase, int index, long cpuBefore, long cpuAfter,
                     long wallBefore, long wallAfter, String reason);
    }

    private ClockEngine() { }

    /** Returns the first invalid-read reason, or the empty string when all observations are valid. */
    static String run(Clock clock, Sink sink, int warmup, int samples) {
        if (clock == null || sink == null || warmup < 0 || warmup > ClockRequest.MAX_WARMUP
                || samples < 1 || samples > ClockRequest.MAX_SAMPLES) {
            throw new IllegalArgumentException("invalid clock diagnostic request");
        }
        boolean hasPrevious = false;
        long previousCpu = 0L;
        long previousWall = 0L;
        for (int index = 0; index < warmup + samples; index++) {
            String phase = index < warmup ? "warmup" : "sample";
            int phaseIndex = index < warmup ? index : index - warmup;
            // Logging and all validation are outside the adjacent-read interval.
            long wallBefore = clock.elapsedNanos();
            long cpuBefore = clock.cpuNanos();
            long cpuAfter = clock.cpuNanos();
            long wallAfter = clock.elapsedNanos();

            String reason = "";
            if (cpuBefore < 0L || cpuAfter < 0L) {
                reason = "cpu_unavailable";
            } else if (wallBefore < 0L || wallAfter < 0L) {
                reason = "wall_unavailable";
            } else if (cpuAfter < cpuBefore || (hasPrevious && cpuBefore < previousCpu)) {
                reason = "cpu_backward";
            } else if (wallAfter < wallBefore || (hasPrevious && wallBefore < previousWall)) {
                reason = "wall_backward";
            }
            sink.reading(phase, phaseIndex, cpuBefore, cpuAfter, wallBefore, wallAfter, reason);
            if (!reason.isEmpty()) return reason;
            previousCpu = cpuAfter;
            previousWall = wallAfter;
            hasPrevious = true;
        }
        return "";
    }
}
