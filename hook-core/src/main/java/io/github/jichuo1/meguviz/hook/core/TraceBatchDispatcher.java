package io.github.jichuo1.meguviz.hook.core;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/** Opt-in scheduling of a single trace batch on a caller-owned executor. */
public final class TraceBatchDispatcher {
    private TraceBatchDispatcher() { }

    /**
     * Submit one attempt. The executor may run it inline, so this call may perform synchronous
     * transport I/O. Supply a suitably bounded executor that never runs on a hook or UI thread.
     * No threads, queue, retry or shutdown policy are supplied by this class.
     *
     * <p>If execute throws a nonfatal exception or error, the handle is still returned with that
     * failure in {@link Attempt#submissionFailure()}. An executor can retain or start a task and
     * then throw, so the failure alone does not prove that transport side effects did not occur.
     * If the task has not started when the failure is handled, it is terminally failed and cannot
     * run later. If it already started, its actual result or failure remains observable. Fatal VM
     * events propagate on the calling thread.
     */
    public static Attempt submit(Executor executor, CallbackExceptionTracker tracker,
                                 CallbackExceptionTracker.BatchTransport transport, int limit) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(transport, "transport");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Attempt attempt = new Attempt(tracker, transport, limit);
        try {
            executor.execute(attempt.done);
        } catch (Throwable failure) {
            attempt.submissionFailed(failure);
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
        }
        return attempt;
    }

    /**
     * A single attempt, not a durable receipt. A successful cancel guarantees this task will not
     * invoke the transport. Once execution starts cancel returns false, even when interruption is
     * requested. The caller must observe the eventual result or failure; no worker is interrupted.
     */
    public static final class Attempt implements Future<CallbackExceptionTracker.BatchAttempt> {
        private enum State { WAITING, RUNNING, CANCELLED, FINISHED }

        // Owned only until this attempt finishes or is cancelled before it starts.
        private CallbackExceptionTracker tracker;
        private CallbackExceptionTracker.BatchTransport transport;
        private final int limit;
        private final Completion done = new Completion(this);
        private State state = State.WAITING;
        private CallbackExceptionTracker.BatchAttempt result;
        private Throwable failure;
        private volatile Throwable submissionFailure;

        private Attempt(CallbackExceptionTracker tracker, CallbackExceptionTracker.BatchTransport transport, int limit) {
            this.tracker = tracker;
            this.transport = transport;
            this.limit = limit;
        }

        /** Nonfatal failure from execute, independent of the eventual attempt outcome. */
        public Throwable submissionFailure() { return submissionFailure; }

        private synchronized void submissionFailed(Throwable problem) {
            submissionFailure = problem;
            if (state == State.WAITING) {
                failure = problem;
                tracker = null;
                transport = null;
                state = State.FINISHED;
                done.countDown();
            }
        }

        private void run() {
            CallbackExceptionTracker activeTracker;
            CallbackExceptionTracker.BatchTransport activeTransport;
            synchronized (this) {
                if (state != State.WAITING) return;
                state = State.RUNNING;
                activeTracker = tracker;
                activeTransport = transport;
            }
            try {
                result = activeTracker.tryDeliverBatch(activeTransport, limit);
            } catch (Throwable problem) {
                failure = problem;
                if (TypeChecks.isFatal(problem)) throw TypeChecks.fatal(problem);
            } finally {
                synchronized (this) {
                    tracker = null;
                    transport = null;
                    state = State.FINISHED;
                    done.countDown();
                }
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (this) {
                if (state != State.WAITING) return false;
                tracker = null;
                transport = null;
                state = State.CANCELLED;
                done.countDown();
            }
            return true;
        }

        @Override public synchronized boolean isCancelled() { return state == State.CANCELLED; }
        @Override public synchronized boolean isDone() {
            return state == State.CANCELLED || state == State.FINISHED;
        }

        @Override public CallbackExceptionTracker.BatchAttempt get() throws InterruptedException, ExecutionException {
            done.acquireSharedInterruptibly(1);
            return outcome();
        }

        @Override public CallbackExceptionTracker.BatchAttempt get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (!done.tryAcquireSharedNanos(1, unit.toNanos(timeout))) throw new TimeoutException();
            return outcome();
        }

        private CallbackExceptionTracker.BatchAttempt outcome() throws ExecutionException {
            synchronized (this) {
                if (state == State.CANCELLED) throw new CancellationException();
                if (failure != null) throw new ExecutionException(failure);
                return result;
            }
        }
    }

    /**
     * The task handed to the executor and the attempt's completion latch in one object (one
     * allocation instead of a method reference, a CountDownLatch and its synchronizer). Waiting
     * uses exactly CountDownLatch(1)'s calls (acquireSharedInterruptibly, tryAcquireSharedNanos
     * with unit.toNanos), so interrupt, timeout and wake-up behaviour are the latch's. Unlike the
     * latch's countDown, the state can only be cleared by {@link #countDown()} from the attempt's
     * terminal transition: the public release methods inherited from the synchronizer only report
     * it, so code holding the queued task cannot release a waiter early.
     */
    private static final class Completion extends AbstractQueuedSynchronizer implements Runnable {
        private static final long serialVersionUID = 1L;
        private final transient Attempt attempt;

        Completion(Attempt attempt) {
            this.attempt = attempt;
            setState(1);
        }

        /** Called once, with the attempt's monitor held, when it becomes terminal. */
        void countDown() {
            setState(0);
            releaseShared(1);
        }

        @Override protected int tryAcquireShared(int ignored) { return getState() == 0 ? 1 : -1; }
        @Override protected boolean tryReleaseShared(int ignored) { return getState() == 0; }
        @Override public void run() { attempt.run(); }
    }
}
