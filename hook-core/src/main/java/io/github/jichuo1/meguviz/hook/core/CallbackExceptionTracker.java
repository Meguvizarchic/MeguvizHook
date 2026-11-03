package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.AroundCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Explicit, bounded tracing for wrapped SDK callbacks. It is not installed by default and does
 * not create threads, perform I/O, connect services, or capture other modules' exceptions.
 * Call {@link #deliver(Transport, int)}, {@link #deliverBatch(BatchTransport, int)} or
 * {@link #tryDeliverBatch(BatchTransport, int)} on a
 * caller-owned background worker, never from a hook callback. The queue is bounded by record
 * count, not by the size of retained Throwable graphs.
 *
 * <p>Wrappers are callback objects with their own identity: retain and reuse each wrapper when
 * relying on idempotent HookSpec installation. VM fatal events are rethrown without recording.
 * Trace delivery is caller supplied; there is no implicit IPC or Remote Files write channel.
 */
public final class CallbackExceptionTracker {
    public enum Stage { BEFORE, AFTER, AROUND, OBSERVED_THROWABLE }

    /** Original throwable and identity; timestamp is process-local monotonic nanoseconds. */
    public static final class Event {
        private final String source;
        private final HookId hookId;
        private final long generation;
        private final long timeNanos;
        private final Stage stage;
        private final Throwable failure;

        private Event(String source, HookId hookId, long generation, Stage stage, Throwable failure) {
            this.source = source;
            this.hookId = hookId;
            this.generation = generation;
            this.timeNanos = System.nanoTime();
            this.stage = stage;
            this.failure = failure;
        }

        public String source() { return source; }
        public HookId hookId() { return hookId; }
        public long generation() { return generation; }
        public long timeNanos() { return timeNanos; }
        public Stage stage() { return stage; }
        public Throwable failure() { return failure; }
    }

    /** Caller-provided sink or transport; returning normally means accepted, not durable. */
    public interface Transport {
        void send(Event event) throws Exception;
    }

    /**
     * Synchronous batch sink. Return the number of records accepted from the beginning of the
     * supplied immutable list. A normal return denotes acceptance, not durable storage. A sink may
     * accept a shorter prefix to enforce its own byte limit; it must not accept later records.
     */
    public interface BatchTransport {
        int sendBatch(List<Event> events) throws Exception;
    }

    /** Result of an explicitly non-waiting attempt to reserve the next batch. */
    public enum BatchAttemptStatus { BUSY, EMPTY, ACCEPTED }

    /** Acceptance means that the transport returned normally, not that data is durable. */
    public static final class BatchAttempt {
        private final BatchAttemptStatus status;
        private final int acceptedCount;
        private BatchAttempt(BatchAttemptStatus status, int acceptedCount) {
            this.status = status;
            this.acceptedCount = acceptedCount;
        }
        public BatchAttemptStatus status() { return status; }
        public int acceptedCount() { return acceptedCount; }
    }

    private static final BatchAttempt BUSY = new BatchAttempt(BatchAttemptStatus.BUSY, 0);
    private static final BatchAttempt EMPTY = new BatchAttempt(BatchAttemptStatus.EMPTY, 0);

    private final String source;
    private final ArrayBlockingQueue<Event> pending;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong trackingFailures = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private boolean delivering;

    public CallbackExceptionTracker(String source, int capacity) {
        this.source = Objects.requireNonNull(source, "source");
        if (source.isEmpty() || source.length() > 128) {
            throw new IllegalArgumentException("source length must be 1..128");
        }
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.pending = new ArrayBlockingQueue<Event>(capacity);
    }

    /** Explicitly wraps only the supplied before callback; ordinary success adds no trace record. */
    public BeforeCallback before(BeforeCallback callback) {
        Objects.requireNonNull(callback, "callback");
        return invocation -> {
            try {
                callback.before(invocation);
            } catch (Throwable failure) {
                capture(invocation, Stage.BEFORE, failure);
                throw failure;
            }
        };
    }

    /**
     * Optionally record the nonfatal throwable in the current result slot before this after runs.
     * It may originate from an earlier callback or an early outcome, not necessarily downstream.
     * Multiple wrapped after callbacks can record the same throwable separately.
     */
    public AfterCallback after(AfterCallback callback, boolean observeThrowable) {
        Objects.requireNonNull(callback, "callback");
        return (invocation, result) -> {
            if (observeThrowable) observeResult(invocation, result);
            try {
                callback.after(invocation, result);
            } catch (Throwable failure) {
                capture(invocation, Stage.AFTER, failure);
                throw failure;
            }
        };
    }

    /** Around records escaping callback failures; handled proceed outcomes are not intercepted. */
    public AroundCallback around(AroundCallback callback) {
        Objects.requireNonNull(callback, "callback");
        return invocation -> {
            try {
                callback.around(invocation);
            } catch (Throwable failure) {
                capture(invocation, Stage.AROUND, failure);
                throw failure;
            }
        };
    }

    private void observeResult(HookInvocation invocation, InvocationResult result) {
        try {
            if (result.hasThrown()) capture(invocation, Stage.OBSERVED_THROWABLE, result.getThrowable());
        } catch (Throwable failure) {
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            trackingFailures.incrementAndGet();
        }
    }

    private void capture(HookInvocation invocation, Stage stage, Throwable failure) {
        if (failure == null) {
            trackingFailures.incrementAndGet();
            return;
        }
        if (TypeChecks.isFatal(failure)) return;
        try {
            Event event = new Event(source, invocation.id(), invocation.generation(), stage, failure);
            // offer never waits for free capacity, although the bounded queue uses a short lock.
            if (!pending.offer(event)) dropped.incrementAndGet();
        } catch (Throwable trackingFailure) {
            if (TypeChecks.isFatal(trackingFailure)) throw TypeChecks.fatal(trackingFailure);
            trackingFailures.incrementAndGet();
        }
    }

    public int pendingCount() { return pending.size(); }
    public long droppedCount() { return dropped.get(); }
    public long trackingFailureCount() { return trackingFailures.get(); }
    public long discardedCount() { return discarded.get(); }

    /**
     * Observe the current head without reserving or removing it, or return null when empty.
     * The returned event may cease to be the head immediately; it retains the original throwable.
     * Use {@link #discardIfNext(Event)} to avoid discarding a different event after inspection.
     */
    public Event peekNext() { return pending.peek(); }

    /**
     * Discard the head only if it is the exact previously observed event. Like
     * {@link #discardNext()}, this waits for legacy delivery and rejects a live non-waiting batch
     * reservation before checking the head. Otherwise, a mismatch leaves the queue unchanged and
     * returns false.
     *
     * @throws NullPointerException if {@code expected} is null
     */
    public boolean discardIfNext(Event expected) {
        Objects.requireNonNull(expected, "expected");
        deliveryLock.lock();
        try {
            if (delivering) throw new IllegalStateException("discard during delivery is not supported");
            if (pending.peek() != expected) return false;
            if (pending.poll() != expected) throw new IllegalStateException("trace queue ownership lost");
            discarded.incrementAndGet();
            return true;
        } finally {
            deliveryLock.unlock();
        }
    }

    /** Explicitly discard and return one pending record for caller-controlled audit. */
    public Event discardNext() {
        deliveryLock.lock();
        try {
            if (delivering) throw new IllegalStateException("discard during delivery is not supported");
            Event event = pending.poll();
            if (event != null) discarded.incrementAndGet();
            return event;
        } finally {
            deliveryLock.unlock();
        }
    }

    /**
     * Deliver up to {@code limit} records from a caller-owned worker. An item is removed only when
     * send returns normally. If send throws after an external side effect, delivery is ambiguous:
     * the item remains queued, and a later explicit retry can duplicate that side effect. No retry
     * or background execution is performed automatically; transport failures propagate unchanged.
     */
    public int deliver(Transport transport, int limit) throws Exception {
        Objects.requireNonNull(transport, "transport");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        deliveryLock.lock();
        try {
            if (delivering) throw new IllegalStateException("recursive delivery is not supported");
            delivering = true;
            try {
                int accepted = 0;
                while (accepted < limit) {
                    Event event = pending.peek();
                    if (event == null) break;
                    transport.send(event);
                    if (pending.poll() != event) throw new IllegalStateException("trace queue ownership lost");
                    accepted++;
                }
                return accepted;
            } finally {
                delivering = false;
            }
        } finally {
            deliveryLock.unlock();
        }
    }

    /**
     * Send one batch of at most {@code limit} records on a caller-owned worker. The tracker keeps
     * every record until the sink returns a valid positive accepted-prefix count, and removes only
     * that prefix. The snapshot is immutable, ordered, and is not a durable queue. A thrown error
     * or invalid count leaves the queue intact, but the sink may already have had side effects:
     * explicit retries can duplicate them. An oversized head must be handled explicitly, for
     * example via the existing single-record delivery or audited discard; this method never skips
     * it. There is no automatic retry, timer, worker, or cross-restart recovery.
     */
    public int deliverBatch(BatchTransport transport, int limit) throws Exception {
        Objects.requireNonNull(transport, "transport");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        deliveryLock.lock();
        try {
            if (delivering) throw new IllegalStateException("recursive delivery is not supported");
            delivering = true;
            try {
                List<Event> batch = new ArrayList<Event>(Math.min(limit, pending.size()));
                // Only capture can run concurrently; all removals hold deliveryLock. Thus the
                // iterator observes a contiguous head even if additional events are appended.
                for (Event event : pending) {
                    if (batch.size() == limit) break;
                    batch.add(event);
                }
                if (batch.isEmpty()) return 0;
                int accepted = transport.sendBatch(Collections.unmodifiableList(batch));
                if (accepted < 1 || accepted > batch.size()) {
                    throw new IllegalStateException("invalid accepted batch prefix");
                }
                for (int i = 0; i < accepted; i++) {
                    if (pending.poll() != batch.get(i)) {
                        throw new IllegalStateException("trace queue ownership lost");
                    }
                }
                return accepted;
            } finally {
                delivering = false;
            }
        } finally {
            deliveryLock.unlock();
        }
    }

    /**
     * Attempts to reserve a single ordered batch without waiting for another delivery or discard.
     * The transport is called synchronously on the caller's worker, outside the delivery lock: this
     * method does not create threads or make Binder asynchronous. A competing attempt returns
     * {@link BatchAttemptStatus#BUSY}; an empty snapshot returns {@link BatchAttemptStatus#EMPTY}.
     *
     * <p>While this opt-in attempt owns the head, legacy delivery and discard calls fail with
     * IllegalStateException instead of waiting; a reentrant try also returns BUSY. When only legacy
     * methods are used, their existing cross-thread waiting and recursive-call behavior is unchanged.
     * Producers may append during the call, but no other delivery or discard can remove the head.
     * A positive, in-range accepted-prefix count after a normal transport return removes only that
     * prefix. A transport exception or invalid count leaves the queue untouched and propagates;
     * external side effects may already have occurred, so an explicit retry may duplicate them.
     * Acceptance is not durability, and process death or arbitrary fatal interruption during the
     * commit does not provide transactional queue recovery.
     */
    public BatchAttempt tryDeliverBatch(BatchTransport transport, int limit) throws Exception {
        Objects.requireNonNull(transport, "transport");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (!deliveryLock.tryLock()) return BUSY;
        boolean reserved = false;
        try {
            if (delivering) return BUSY;
            List<Event> batch = new ArrayList<Event>(Math.min(limit, pending.size()));
            for (Event event : pending) {
                if (batch.size() == limit) break;
                batch.add(event);
            }
            if (batch.isEmpty()) return EMPTY;
            // Establish the finally guard before publishing ownership; no external code runs here.
            reserved = true;
            delivering = true;
            deliveryLock.unlock();
            int accepted = transport.sendBatch(Collections.unmodifiableList(batch));
            // Reacquisition is not interruptible, like the previous monitor contract. A thread's
            // interrupt status is not consumed by the delivery lock.
            deliveryLock.lock();
            if (accepted < 1 || accepted > batch.size()) {
                throw new IllegalStateException("invalid accepted batch prefix");
            }
            for (int i = 0; i < accepted; i++) {
                if (pending.poll() != batch.get(i)) throw new IllegalStateException("trace queue ownership lost");
            }
            return new BatchAttempt(BatchAttemptStatus.ACCEPTED, accepted);
        } finally {
            if (reserved) {
                if (!deliveryLock.isHeldByCurrentThread()) deliveryLock.lock();
                delivering = false;
            }
            deliveryLock.unlock();
        }
    }
}
