package io.github.jichuo1.meguviz.hook.benchmarkhost;

import java.util.concurrent.atomic.AtomicLong;

/** Fixed single-registration resource transaction; no allocation or GC request in the target. */
public final class B10Target {
    private static final State STATE = new State();

    private B10Target() { }

    public static void prepare() { STATE.prepare(); }
    public static long call() { return STATE.call(); }
    public static long originalCount() { return STATE.originalCount(); }

    static final class State {
        private final AtomicLong originals = new AtomicLong();
        private volatile boolean prepared;

        synchronized void prepare() {
            if (prepared) throw new IllegalStateException("B10 target already prepared");
            prepared = true;
        }

        long call() {
            if (!prepared) throw new IllegalStateException("B10 target not prepared");
            originals.incrementAndGet();
            return 42L;
        }

        long originalCount() { return originals.get(); }
    }
}
