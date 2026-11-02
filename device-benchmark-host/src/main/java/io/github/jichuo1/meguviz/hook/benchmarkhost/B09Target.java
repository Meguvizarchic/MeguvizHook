package io.github.jichuo1.meguviz.hook.benchmarkhost;

import java.util.concurrent.atomic.AtomicLong;

/** One prepared ordinary exception per fresh-process failure-path transaction. */
public final class B09Target {
    private static final State STATE = new State();

    private B09Target() { }

    /** Allocates the diagnostic exception before the measured call; the case cannot be reset. */
    public static void prepare(String failureCase) { STATE.prepare(failureCase); }

    public static long value() { return STATE.value(); }

    public static RuntimeException expectedFailure() { return STATE.expectedFailure(); }

    public static long originalCount() { return STATE.originalCount(); }

    /** Same state implementation used by the public target and independent bounded JVM cases. */
    static final class State {
        private final AtomicLong originals = new AtomicLong();
        private volatile Prepared prepared;

        synchronized void prepare(String failureCase) {
            if (!("before".equals(failureCase) || "after".equals(failureCase)
                    || "original".equals(failureCase))) {
                throw new IllegalArgumentException("invalid B09 failure case");
            }
            if (prepared != null) throw new IllegalStateException("B09 target was already prepared");
            prepared = new Prepared(failureCase);
        }

        long value() {
            Prepared selected = requirePrepared();
            originals.incrementAndGet();
            if ("original".equals(selected.failureCase)) throw selected.failure;
            return 42L;
        }

        RuntimeException expectedFailure() { return requirePrepared().failure; }

        long originalCount() { return originals.get(); }

        private Prepared requirePrepared() {
            Prepared selected = prepared;
            if (selected == null) throw new IllegalStateException("B09 target is not prepared");
            return selected;
        }
    }

    private static final class Prepared {
        final String failureCase;
        final RuntimeException failure;

        Prepared(String failureCase) {
            this.failureCase = failureCase;
            this.failure = new RuntimeException("B09 " + failureCase + " diagnostic sentinel");
        }
    }
}
