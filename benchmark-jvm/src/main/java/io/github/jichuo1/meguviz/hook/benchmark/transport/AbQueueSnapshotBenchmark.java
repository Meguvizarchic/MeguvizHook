package io.github.jichuo1.meguviz.hook.benchmark.transport;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Same-run A/B of the tracker's queue algorithm on a plain ArrayBlockingQueue with N pending:
 * legacy = iterator snapshot + one poll per accepted record; current = toArray snapshot + one
 * drainTo with identity check (a copy of CallbackExceptionTracker's private helpers). Each
 * operation accepts {@code accepted} records and re-offers them, keeping N pending.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
public class AbQueueSnapshotBenchmark {
    @Param({"legacy", "current"})
    public String impl;

    @Param({"16", "128"})
    public int pending;

    @Param({"1", "all"})
    public String accepted;

    private ArrayBlockingQueue<Object> queue;
    private boolean legacy;
    private static final Object[] NONE = new Object[0];

    @Setup(Level.Trial)
    public void setUp() {
        legacy = "legacy".equals(impl);
        queue = new ArrayBlockingQueue<Object>(1024);
        for (int i = 0; i < pending; i++) queue.offer(new Object());
    }

    private static final class PrefixCheck extends AbstractCollection<Object> {
        private final Object[] expected;
        private int next;
        PrefixCheck(Object[] expected) { this.expected = expected; }
        @Override public boolean add(Object event) {
            if (event != expected[next]) throw new IllegalStateException("trace queue ownership lost");
            next++;
            return true;
        }
        @Override public Iterator<Object> iterator() { throw new UnsupportedOperationException(); }
        @Override public int size() { return next; }
    }

    @Benchmark
    public int snapshotAndRemove() {
        int limit = pending;
        int count;
        if (legacy) {
            List<Object> batch = LegacyTransport.snapshotByIteration(queue, limit);
            count = "all".equals(accepted) ? batch.size() : 1;
            LegacyTransport.removeByPolling(queue, batch, count);
            for (int i = 0; i < count; i++) queue.offer(batch.get(i));
        } else {
            Object[] batch = queue.toArray(NONE);
            if (batch.length > limit) batch = Arrays.copyOf(batch, limit);
            List<Object> view = Collections.unmodifiableList(Arrays.asList(batch));
            count = "all".equals(accepted) ? view.size() : 1;
            if (queue.drainTo(new PrefixCheck(batch), count) != count) throw new IllegalStateException();
            for (int i = 0; i < count; i++) queue.offer(batch[i]);
        }
        return count;
    }
}
