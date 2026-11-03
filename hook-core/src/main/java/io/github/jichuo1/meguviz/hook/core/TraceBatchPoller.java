package io.github.jichuo1.meguviz.hook.core;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit, caller-executor-owned polling of the existing non-waiting trace delivery API. */
public final class TraceBatchPoller implements AutoCloseable {
    public enum State { ACTIVE, SUSPENDED, CLOSED }
    /** Confirmed prefixes observed by this poller; confirmation does not imply durability. */
    public static final class DeliveryProgress {
        private final long acceptedBatches;
        private final long acceptedRecords;

        private DeliveryProgress(long acceptedBatches, long acceptedRecords) {
            this.acceptedBatches = acceptedBatches;
            this.acceptedRecords = acceptedRecords;
        }

        public long acceptedBatches() { return acceptedBatches; }
        public long acceptedRecords() { return acceptedRecords; }
    }

    /** What an explicit observation learned about the currently installed periodic future. */
    public enum ScheduleObservationStatus { INACTIVE, HANDLE_PENDING, NOT_DONE, DONE, CHANGED, CHECK_FAILED }

    /**
     * A point-in-time observation, not a delivery receipt or a guarantee of future scheduling.
     * The future and the internal fields are sampled at different times and may change again.
     */
    public static final class ScheduleObservation {
        private final ScheduleObservationStatus status;
        private final State state;
        private final boolean inFlight;
        private final Throwable checkFailure;

        private ScheduleObservation(ScheduleObservationStatus status, State state,
                                    boolean inFlight, Throwable checkFailure) {
            this.status = status;
            this.state = state;
            this.inFlight = inFlight;
            this.checkFailure = checkFailure;
        }

        public ScheduleObservationStatus status() { return status; }
        public State state() { return state; }
        public boolean inFlight() { return inFlight; }
        /** A nonfatal error from the sampled future, including when status is CHANGED. */
        public Throwable checkFailure() { return checkFailure; }
    }
    /** A monotonic nanosecond source; called only outside the poller's state lock. */
    public interface Clock { long nanoTime(); }

    /**
     * When records below the count trigger are found to have waited {@code maxObservedWait}.
     * Either way the wait starts when an evaluation first observes the non-empty queue.
     */
    public enum WaitExpiry {
        /** Default: the next evaluation after expiry sends, up to one poll interval late. */
        NEXT_POLL,
        /**
         * Additionally schedule one ordinary evaluation for the moment the observed wait expires,
         * with {@code executor.schedule}, from the executor thread that observed the window.
         */
        DEADLINE
    }

    private static final int PERIODIC = 0;
    private static final int REQUESTED = 1;
    private static final int DEADLINE_DUE = 2;
    private static final long MAX_DURATION_NANOS = TimeUnit.DAYS.toNanos(1);

    private final ScheduledExecutorService executor;
    private CallbackExceptionTracker tracker;
    private CallbackExceptionTracker.BatchTransport transport;
    private final int triggerCount;
    private final int batchLimit;
    private final int maxBatchesPerTick;
    private final long pollNanos;
    private final long waitNanos;
    private final Clock clock;
    private final boolean deadlines;
    // Written under this monitor; volatile so requestPoll can check them without the monitor.
    private volatile State state = State.ACTIVE;
    private volatile long generation;
    private boolean inFlight;
    // Owned by the tick holding inFlight; the monitor hand-off orders successive ticks.
    private boolean observedPending;
    private long observedAt;
    // Opt-in early trigger: at most one requested evaluation is submitted until it starts.
    private final AtomicBoolean pollRequested = new AtomicBoolean();
    private final AtomicReference<Thread> requesting = new AtomicReference<Thread>();
    // Set by the first requestPoll; only then does a tick that hits its batch cap ask for more.
    private volatile boolean requestsUsed;
    private boolean rerunRequested;
    // WaitExpiry.DEADLINE only, guarded by this monitor. At most one one-shot is pending; each
    // observation window arms its deadline at most once (armedDeadline); a one-shot that finds an
    // evaluation in flight records its target so that evaluation can follow up (missedDeadline).
    private ScheduledFuture<?> deadlineFuture;
    private long deadlineSeq;
    private boolean deadlinePending;
    private boolean deadlineArmed;
    private long armedDeadline;
    private boolean deadlineMissed;
    private long missedDeadline;
    private ScheduledFuture<?> future;
    private CallbackExceptionTracker.BatchAttempt lastAttempt;
    private Throwable lastFailure;
    private Throwable schedulingFailure;
    private long acceptedBatches;
    private long acceptedRecords;

    private TraceBatchPoller(ScheduledExecutorService executor, CallbackExceptionTracker tracker,
                             CallbackExceptionTracker.BatchTransport transport, int triggerCount,
                             int batchLimit, int maxBatchesPerTick, long pollNanos, long waitNanos, Clock clock,
                             boolean deadlines) {
        this.executor = executor;
        this.tracker = tracker;
        this.transport = transport;
        this.triggerCount = triggerCount;
        this.batchLimit = batchLimit;
        this.maxBatchesPerTick = maxBatchesPerTick;
        this.pollNanos = pollNanos;
        this.waitNanos = waitNanos;
        this.clock = clock;
        this.deadlines = deadlines;
    }

    /**
     * Start a fixed-delay poll on the caller's functioning background scheduler. Never call from a
     * hook or UI thread: an executor may execute inline. Durations are converted to nanoseconds;
     * zero wait sends on the first observation. No hard delivery deadline is promised.
     */
    public static TraceBatchPoller start(ScheduledExecutorService executor, CallbackExceptionTracker tracker,
                                         CallbackExceptionTracker.BatchTransport transport, int triggerCount,
                                         int batchLimit, long pollInterval, long maxObservedWait, TimeUnit unit) {
        return start(executor, tracker, transport, triggerCount, batchLimit, pollInterval, maxObservedWait,
                unit, System::nanoTime);
    }

    /** Same contract with an explicit monotonic clock, useful for deterministic scheduling tests. */
    public static TraceBatchPoller start(ScheduledExecutorService executor, CallbackExceptionTracker tracker,
                                         CallbackExceptionTracker.BatchTransport transport, int triggerCount,
                                         int batchLimit, long pollInterval, long maxObservedWait, TimeUnit unit,
                                         Clock clock) {
        return startDraining(executor, tracker, transport, triggerCount, batchLimit, 1,
                pollInterval, maxObservedWait, unit, clock);
    }

    /**
     * Start bounded backlog draining after the ordinary count or observed-wait trigger. Each tick
     * attempts at most {@code maxBatchesPerTick} separately acknowledged batches before yielding
     * to the caller's scheduler. Once triggered, a remaining short prefix may be sent before its
     * wait expires. BUSY, EMPTY or failure stops this tick; failures are never retried implicitly.
     */
    public static TraceBatchPoller startDraining(ScheduledExecutorService executor, CallbackExceptionTracker tracker,
            CallbackExceptionTracker.BatchTransport transport, int triggerCount, int batchLimit,
            int maxBatchesPerTick, long pollInterval, long maxObservedWait, TimeUnit unit) {
        return startDraining(executor, tracker, transport, triggerCount, batchLimit, maxBatchesPerTick,
                pollInterval, maxObservedWait, unit, System::nanoTime);
    }

    /** Same draining contract with a caller-supplied monotonic clock. */
    public static TraceBatchPoller startDraining(ScheduledExecutorService executor, CallbackExceptionTracker tracker,
            CallbackExceptionTracker.BatchTransport transport, int triggerCount, int batchLimit,
            int maxBatchesPerTick, long pollInterval, long maxObservedWait, TimeUnit unit, Clock clock) {
        return startDraining(executor, tracker, transport, triggerCount, batchLimit, maxBatchesPerTick,
                pollInterval, maxObservedWait, unit, clock, WaitExpiry.NEXT_POLL);
    }

    /**
     * Same draining contract with an explicit {@link WaitExpiry}. {@link WaitExpiry#NEXT_POLL} is
     * exactly the other factories. With {@link WaitExpiry#DEADLINE}, an evaluation that leaves an
     * observation window open with its wait not yet expired arms one ordinary evaluation through
     * {@code executor.schedule} for the remaining wait, measured from that evaluation's clock
     * reading. Only evaluations arm, so this happens on the executor, never on a hook thread.
     *
     * <p>Rules: at most one such one-shot is pending per poller, and each window arms its deadline
     * at most once. A window that closes early does not cancel the one-shot; it then runs once as
     * an ordinary evaluation and arms the current window's deadline if one is open, so cancelled
     * tasks never pile up in a ScheduledThreadPoolExecutor queue. If the one-shot finds another
     * evaluation in flight that did not yet see the expiry, that evaluation submits one follow-up
     * evaluation when it ends, as for {@link #requestPoll()}. If the one-shot runs before the
     * supplied clock reports the expiry (a clock other than {@code System.nanoTime()}), it is not
     * re-armed and the periodic tick remains responsible. A rejected schedule is kept in
     * {@link #schedulingFailure()} without suspending; that window then waits for the periodic
     * tick. Suspension and close cancel the pending one-shot with {@code cancel(false)}. A zero
     * wait never arms. The count trigger, drain bound, BUSY/EMPTY and failure rules are unchanged,
     * and no hard deadline is promised: executor queueing and transport time still add latency.
     */
    public static TraceBatchPoller startDraining(ScheduledExecutorService executor, CallbackExceptionTracker tracker,
            CallbackExceptionTracker.BatchTransport transport, int triggerCount, int batchLimit,
            int maxBatchesPerTick, long pollInterval, long maxObservedWait, TimeUnit unit, Clock clock,
            WaitExpiry expiry) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(expiry, "expiry");
        if (triggerCount < 1 || batchLimit < 1 || maxBatchesPerTick < 1 || pollInterval < 1 || maxObservedWait < 0) {
            throw new IllegalArgumentException("invalid polling bounds");
        }
        long pollNanos = unit.toNanos(pollInterval);
        long waitNanos = unit.toNanos(maxObservedWait);
        if (pollNanos <= 0 || pollNanos > MAX_DURATION_NANOS || waitNanos < 0
                || waitNanos > MAX_DURATION_NANOS) throw new IllegalArgumentException("duration exceeds one day");
        TraceBatchPoller poller = new TraceBatchPoller(executor, tracker, transport, triggerCount,
                batchLimit, maxBatchesPerTick, pollNanos, waitNanos, clock, expiry == WaitExpiry.DEADLINE);
        poller.schedule();
        return poller;
    }

    /** Explicitly resume after a failure; retries may duplicate remote side effects. */
    public boolean resume() {
        synchronized (this) {
            if (state != State.SUSPENDED || inFlight) return false;
            pollRequested.set(false);
            clearDeadline();
            deadlineArmed = false;
            state = State.ACTIVE;
        }
        schedule();
        return true;
    }

    private void schedule() {
        final long ticket;
        synchronized (this) {
            if (state != State.ACTIVE) return;
            ticket = ++generation;
        }
        ScheduledFuture<?> installed;
        try {
            installed = Objects.requireNonNull(executor.scheduleWithFixedDelay(
                    () -> tick(ticket, PERIODIC, 0L), 0, pollNanos, TimeUnit.NANOSECONDS), "scheduled future");
        } catch (Throwable failure) {
            synchronized (this) {
                schedulingFailure = failure;
                if (state == State.ACTIVE && generation == ticket) {
                    state = State.SUSPENDED;
                    generation++;
                }
            }
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            return;
        }
        boolean cancel;
        synchronized (this) {
            cancel = state != State.ACTIVE || generation != ticket;
            if (!cancel) future = installed;
        }
        if (cancel) cancelFuture(installed);
    }

    /**
     * Opt-in early trigger: ask the caller's executor to run one extra ordinary evaluation now
     * instead of waiting for the next fixed-delay tick. The evaluation applies exactly the count
     * trigger, observed wait, drain bound, BUSY/EMPTY and failure rules of a periodic tick; it does
     * not force a send. Requests are coalesced: until the submitted evaluation starts, further
     * calls return true without submitting again. A request that arrives while an evaluation is
     * running is honoured by one follow-up evaluation, so a record queued after that evaluation
     * read the queue is not left waiting for the next period. Once this method has been used, an
     * evaluation that stops at {@code maxBatchesPerTick} with its last batch accepted and records
     * still pending also requests a follow-up evaluation, so a backlog keeps draining in bounded,
     * executor-yielding steps instead of waiting for the next period. Pollers that never call this
     * method keep exactly the periodic behavior.
     *
     * <p>Cost on the calling thread: two volatile reads and, when not coalesced, one CAS and one
     * {@code executor.execute}. It never waits for the poller's state lock and never performs
     * transport work itself: if the executor runs the task inline, the task is skipped and the
     * periodic tick remains responsible. Only call it from a hook thread when the executor's
     * {@code execute} never blocks and never runs inline (for example ScheduledThreadPoolExecutor).
     *
     * @return false if the poller is not ACTIVE or the executor rejected the request (the failure
     *         is kept in {@link #schedulingFailure()}); true if submitted or already pending
     */
    public boolean requestPoll() {
        if (state != State.ACTIVE) return false;
        if (!requestsUsed) requestsUsed = true;
        return submitRequest();
    }

    /** The coalesced submission behind requestPoll; internal follow-ups do not mark requests used. */
    private boolean submitRequest() {
        if (!pollRequested.compareAndSet(false, true)) return true;
        // A ticket read concurrently with close/suspend/resume is safe: tick rechecks it.
        long ticket = generation;
        if (state != State.ACTIVE) {
            pollRequested.set(false);
            return false;
        }
        Thread caller = Thread.currentThread();
        requesting.set(caller);
        try {
            executor.execute(() -> requestedTick(ticket));
            return true;
        } catch (Throwable failure) {
            pollRequested.set(false);
            synchronized (this) { schedulingFailure = failure; }
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            return false;
        } finally {
            requesting.compareAndSet(caller, null);
        }
    }

    private void requestedTick(long ticket) {
        // Re-arm before evaluating, so a capture after this point can request another evaluation.
        pollRequested.set(false);
        if (requesting.get() == Thread.currentThread()) return; // executed inline by requestPoll
        tick(ticket, REQUESTED, 0L);
    }

    private void deadlineTick(long ticket, long seq, long target) {
        synchronized (this) {
            if (deadlineSeq == seq && deadlinePending) {
                deadlinePending = false;
                deadlineFuture = null;
            }
        }
        tick(ticket, DEADLINE_DUE, target);
    }

    private void tick(long ticket, int kind, long target) {
        CallbackExceptionTracker activeTracker;
        CallbackExceptionTracker.BatchTransport activeTransport;
        synchronized (this) {
            if (state != State.ACTIVE || generation != ticket) return;
            if (inFlight) {
                if (kind == REQUESTED) {
                    rerunRequested = true;
                } else if (kind == DEADLINE_DUE) {
                    deadlineMissed = true;
                    missedDeadline = target;
                }
                return;
            }
            inFlight = true;
            activeTracker = tracker;
            activeTransport = transport;
        }
        boolean backlog = false;
        boolean windowOpen = false;
        long windowDeadline = 0L;
        long now = 0L;
        try {
            int count = activeTracker.pendingCount();
            now = clock.nanoTime();
            boolean send;
            if (count == 0) {
                observedPending = false;
                send = false;
            } else {
                if (!observedPending) {
                    observedPending = true;
                    observedAt = now;
                }
                send = count >= triggerCount || now - observedAt >= waitNanos;
            }
            if (send) {
                for (int batch = 0; batch < maxBatchesPerTick; batch++) {
                    if (batch != 0) {
                        // Passing this check claims the next attempt even if close follows immediately.
                        synchronized (this) {
                            if (state != State.ACTIVE || generation != ticket) break;
                        }
                    }
                    CallbackExceptionTracker.BatchAttempt result = activeTracker.tryDeliverBatch(activeTransport, batchLimit);
                    boolean accepted = result.status() == CallbackExceptionTracker.BatchAttemptStatus.ACCEPTED;
                    boolean emptyAfterDelivery = accepted && activeTracker.pendingCount() == 0;
                    if (result.status() == CallbackExceptionTracker.BatchAttemptStatus.EMPTY || emptyAfterDelivery) {
                        observedPending = false;
                    }
                    synchronized (this) {
                        lastAttempt = result;
                        if (accepted) {
                            acceptedBatches++;
                            acceptedRecords += result.acceptedCount();
                        }
                    }
                    if (!accepted || emptyAfterDelivery) break;
                    if (batch == maxBatchesPerTick - 1) backlog = true;
                }
            }
            if (deadlines && observedPending) {
                windowOpen = true;
                windowDeadline = observedAt + waitNanos;
            }
        } catch (Throwable failure) {
            ScheduledFuture<?> cancel = null;
            ScheduledFuture<?> cancelDeadline = null;
            synchronized (this) {
                lastFailure = failure;
                if (state == State.ACTIVE && generation == ticket) {
                    state = State.SUSPENDED;
                    generation++;
                    cancel = future;
                    future = null;
                    cancelDeadline = clearDeadline();
                }
            }
            cancelOnSuspend(cancel, failure);
            cancelOnSuspend(cancelDeadline, failure);
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
        } finally {
            boolean rerun;
            long armSeq = 0L;
            long armDelay = 0L;
            synchronized (this) {
                inFlight = false;
                rerun = rerunRequested;
                rerunRequested = false;
                boolean missed = deadlineMissed && missedDeadline == windowDeadline;
                deadlineMissed = false;
                if (windowOpen && windowDeadline - now > 0 && state == State.ACTIVE && generation == ticket) {
                    if (missed) {
                        // This window's one-shot ran while this evaluation was in flight with an
                        // earlier clock reading: evaluate once more instead of waiting a period.
                        rerun = true;
                    } else if (!rerun && !deadlinePending && !(deadlineArmed && armedDeadline == windowDeadline)) {
                        deadlinePending = true;
                        deadlineArmed = true;
                        armedDeadline = windowDeadline;
                        armSeq = ++deadlineSeq;
                        armDelay = windowDeadline - now;
                    }
                }
            }
            if (armDelay > 0) scheduleDeadline(ticket, armSeq, windowDeadline, armDelay);
            if ((rerun || (backlog && requestsUsed)) && state == State.ACTIVE) submitRequest();
        }
    }

    /** One-shot evaluation at a window's expiry (WaitExpiry.DEADLINE); see startDraining. */
    private void scheduleDeadline(long ticket, long seq, long target, long delay) {
        ScheduledFuture<?> installed;
        try {
            installed = executor.schedule(() -> deadlineTick(ticket, seq, target), delay, TimeUnit.NANOSECONDS);
        } catch (Throwable failure) {
            synchronized (this) {
                schedulingFailure = failure;
                if (deadlineSeq == seq) deadlinePending = false;
            }
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            return;
        }
        boolean cancel = false;
        synchronized (this) {
            if (state != State.ACTIVE || generation != ticket) {
                cancel = installed != null; // closed or suspended before the handle was installed
            } else if (deadlineSeq == seq && deadlinePending) {
                deadlineFuture = installed;
            }
        }
        if (cancel) cancelFuture(installed);
    }

    /** Must hold the monitor: forget the pending one-shot and return its handle to cancel. */
    private ScheduledFuture<?> clearDeadline() {
        ScheduledFuture<?> pending = deadlineFuture;
        deadlineFuture = null;
        deadlinePending = false;
        deadlineMissed = false;
        return pending;
    }

    /** Cancel a handle after a failed evaluation, keeping the original fatal event if both fail. */
    private void cancelOnSuspend(ScheduledFuture<?> task, Throwable failure) {
        if (task == null) return;
        try {
            cancelFuture(task);
        } catch (Error cancelFailure) {
            if (!TypeChecks.isFatal(failure)) throw cancelFailure;
            if (cancelFailure != failure) {
                try { failure.addSuppressed(cancelFailure); }
                catch (Throwable ignored) { /* Preserve the original fatal event. */ }
            }
        }
    }

    public synchronized State state() { return state; }
    public synchronized boolean inFlight() { return inFlight; }
    public synchronized CallbackExceptionTracker.BatchAttempt lastAttempt() { return lastAttempt; }
    public synchronized Throwable lastFailure() { return lastFailure; }
    public synchronized Throwable schedulingFailure() { return schedulingFailure; }
    /**
     * Snapshot counts accepted prefixes seen by this poller, including those before a later
     * failed attempt. It excludes work performed by other consumers and does not prove delivery
     * durability or absence of side effects from a failed attempt.
     */
    public synchronized DeliveryProgress progress() {
        return new DeliveryProgress(acceptedBatches, acceptedRecords);
    }

    /**
     * Explicitly inspect the current periodic future without changing poller state. Invoke from a
     * caller-owned background thread: a supplied future's isDone() may block, reenter, or throw.
     * NOT_DONE only reflects that future's answer, not worker liveness, eventual execution or a
     * deadline. DONE can coexist with already claimed work. CHANGED means the state, generation,
     * or future changed during inspection; a nonfatal inspection error remains observable there.
     * Fatal VM errors propagate. This operation never waits for a task using Future.get().
     */
    public ScheduleObservation scheduleObservation() {
        ScheduledFuture<?> observed;
        long ticket;
        synchronized (this) {
            if (state != State.ACTIVE) {
                return new ScheduleObservation(ScheduleObservationStatus.INACTIVE, state, inFlight, null);
            }
            observed = future;
            if (observed == null) {
                return new ScheduleObservation(ScheduleObservationStatus.HANDLE_PENDING, state, inFlight, null);
            }
            ticket = generation;
        }
        boolean done = false;
        Throwable failure = null;
        try {
            done = observed.isDone();
        } catch (Throwable error) {
            if (TypeChecks.isFatal(error)) throw TypeChecks.fatal(error);
            failure = error;
        }
        synchronized (this) {
            ScheduleObservationStatus status;
            if (state != State.ACTIVE || generation != ticket || future != observed) {
                status = ScheduleObservationStatus.CHANGED;
            } else if (failure != null) {
                status = ScheduleObservationStatus.CHECK_FAILED;
            } else {
                status = done ? ScheduleObservationStatus.DONE : ScheduleObservationStatus.NOT_DONE;
            }
            return new ScheduleObservation(status, state, inFlight, failure);
        }
    }

    /** Close the claim gate and cancel the periodic task, without interrupting claimed work. */
    @Override public void close() {
        ScheduledFuture<?> cancel;
        ScheduledFuture<?> cancelDeadline;
        synchronized (this) {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
            generation++;
            tracker = null;
            transport = null;
            cancel = future;
            future = null;
            cancelDeadline = clearDeadline();
        }
        if (cancel != null) cancelFuture(cancel);
        if (cancelDeadline != null) cancelFuture(cancelDeadline);
    }

    private void cancelFuture(ScheduledFuture<?> task) {
        try {
            task.cancel(false);
        } catch (Throwable failure) {
            synchronized (this) { schedulingFailure = failure; }
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
        }
    }
}
