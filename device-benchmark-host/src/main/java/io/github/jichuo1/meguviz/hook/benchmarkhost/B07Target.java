package io.github.jichuo1.meguviz.hook.benchmarkhost;

import java.util.concurrent.atomic.AtomicLong;

/** Dedicated install pilot target; the host does not touch it before the module installs its hook. */
public final class B07Target {
    private static final AtomicLong ORIGINAL_COUNT = new AtomicLong();

    private B07Target() { }

    public static long value() {
        ORIGINAL_COUNT.incrementAndGet();
        return 42L;
    }

    public static long originalCount() {
        return ORIGINAL_COUNT.get();
    }
}
