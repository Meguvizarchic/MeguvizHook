package io.github.jichuo1.meguviz.hook.benchmarkhost;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Dedicated, one-shot close-with-inflight target; it uses only standard-library coordination. */
public final class B08Target {
    private static final State STATE = new State(30_000L);

    private B08Target() { }

    /** One preparation per fresh process. Indexes mean original entered and original release. */
    public static CountDownLatch[] prepare() {
        return STATE.prepare();
    }

    /** The held original is released by the module; a post-close original never waits. */
    public static long call(boolean hold) throws InterruptedException {
        return STATE.call(hold);
    }

    /** A fresh array containing held-original and post-close-original invocation counts. */
    public static long[] originalCounts() {
        return STATE.originalCounts();
    }

    /** The actual target state is independently constructible for bounded JVM checks. */
    static final class State {
        private final long holdTimeoutMillis;
        private final AtomicBoolean prepared = new AtomicBoolean();
        private final AtomicLong oldOriginal = new AtomicLong();
        private final AtomicLong newOriginal = new AtomicLong();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        State(long holdTimeoutMillis) {
            if (holdTimeoutMillis < 1 || holdTimeoutMillis > 30_000L) {
                throw new IllegalArgumentException("invalid bounded original wait");
            }
            this.holdTimeoutMillis = holdTimeoutMillis;
        }

        CountDownLatch[] prepare() {
            if (!prepared.compareAndSet(false, true)) {
                throw new IllegalStateException("B08 target was already prepared");
            }
            return new CountDownLatch[]{entered, release};
        }

        long call(boolean hold) throws InterruptedException {
            if (!prepared.get()) throw new IllegalStateException("B08 target is not prepared");
            if (hold) {
                oldOriginal.incrementAndGet();
                entered.countDown();
                if (!release.await(holdTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("B08 original release timed out");
                }
            } else {
                newOriginal.incrementAndGet();
            }
            return 42L;
        }

        long[] originalCounts() {
            return new long[]{oldOriginal.get(), newOriginal.get()};
        }
    }
}
