package io.github.jichuo1.meguviz.hook.devicehost;

import java.util.concurrent.atomic.AtomicInteger;

/** Stable target and observable original-call counter for device integration tests. */
public final class ProbeTarget {
    private static final AtomicInteger CALLS = new AtomicInteger();

    private ProbeTarget() {
    }

    public static int add(int left, int right) {
        CALLS.incrementAndGet();
        return left + right;
    }

    public static int callAdd() {
        return add(2, 3);
    }

    public static int calls() {
        return CALLS.get();
    }

    public static void reset() {
        CALLS.set(0);
    }
}
