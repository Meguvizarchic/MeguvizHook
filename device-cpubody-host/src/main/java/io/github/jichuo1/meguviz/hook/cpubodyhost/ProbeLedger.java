package io.github.jichuo1.meguviz.hook.cpubodyhost;

import java.util.concurrent.atomic.AtomicInteger;

/** Per-run bounded, same-thread ledger; the module receives only these JDK arrays. */
public final class ProbeLedger {
    // state: ordinal, phase, before total, original total, after total, workload.
    static int[] state;
    static int[] beforeCounts, originalCounts, afterCounts, present;
    static long[] beforeStart, beforeEnd, afterStart, afterEnd;
    static AtomicInteger fault;
    static Thread owner;

    private ProbeLedger() { }

    static void initialize(int count, boolean after) {
        owner = Thread.currentThread();
        state = new int[6];
        state[5] = after ? 1 : 0;
        beforeCounts = new int[count]; originalCounts = new int[count]; afterCounts = new int[count];
        present = new int[count]; beforeStart = new long[count]; beforeEnd = new long[count];
        afterStart = new long[count]; afterEnd = new long[count]; fault = new AtomicInteger();
    }

    /** Reflection occurs once during module setup, never within a measured callback. */
    public static Object[] export() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("wrong setup thread");
        return new Object[]{state, beforeCounts, originalCounts, afterCounts, present,
                beforeStart, beforeEnd, afterStart, afterEnd, fault, owner};
    }

    static void begin(int ordinal) {
        if (Thread.currentThread() != owner || state[1] != 0 || ordinal < 0 || ordinal >= beforeCounts.length) {
            fault.compareAndSet(0, 1);
            return;
        }
        state[0] = ordinal;
    }

    /** Called from the controlled target, outside every measured callback-body interval. */
    static void original() {
        if (Thread.currentThread() != owner) { fault.compareAndSet(0, 2); return; }
        int i = state[0];
        if (i < 0 || i >= originalCounts.length) { fault.compareAndSet(0, 1); return; }
        originalCounts[i]++; state[3]++;
        if (originalCounts[i] != 1 || state[1] != 1) { fault.compareAndSet(0, 3); return; }
        state[1] = state[5] == 1 ? 2 : 0;
    }

    private static String faultReason() {
        int error = fault.get();
        if (error == 1) return "phase_mismatch";
        if (error == 2) return "cross_thread";
        if (error == 3) return "original_mismatch";
        if (error == 4) return "hook_mismatch";
        if (error == 5) return "cpu_unavailable";
        if (error == 6) return "cpu_backward";
        if (error == 7) return "clock_exception";
        if (error != 0) return "unknown_fault";
        return "";
    }

    static String reason(int i) {
        String error = faultReason();
        if (!error.isEmpty()) return error;
        if (state[1] != 0 || beforeCounts[i] != 1 || originalCounts[i] != 1
                || afterCounts[i] != (state[5] == 1 ? 1 : 0)) return "ledger_mismatch";
        if ((present[i] & 3) != 3 || (state[5] == 1 && (present[i] & 12) != 12)) return "missing_clock";
        return "";
    }

    /** Snapshot faults again after teardown, including faults after the last call check. */
    static String finalReason(String prior, boolean cleanup) {
        if (!prior.isEmpty()) return prior;
        String error = faultReason();
        if (!error.isEmpty()) return error;
        return cleanup ? "" : "cleanup_failed";
    }
}
