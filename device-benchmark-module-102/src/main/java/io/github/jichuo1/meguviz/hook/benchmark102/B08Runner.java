package io.github.jichuo1.meguviz.hook.benchmark102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded, explicit close-with-inflight transaction used by the actual module. */
final class B08Runner {
    interface Clock { long wall(); long tid(); }
    interface Steps {
        void install() throws Throwable;
        CountDownLatch[] prepareTarget() throws Throwable;
        Object invoke(boolean hold) throws Throwable;
        long[] originalCounts() throws Throwable;
        long[] hookCounts();
        CloseReport closeRegistration() throws Throwable;
        void closeEnvironment() throws Throwable;
        void checkFramework() throws Throwable;
    }
    interface Events {
        void observed(String stage, Observation observation);
        void failed(String stage, Throwable failure, Observation observation);
    }
    static final class Observation {
        Long workerTid, closerTid, postTid, closeStart, closeEnd;
        Object oldResult, postResult;
        long[] originalCounts, hookCounts;
        CloseReport report;
        boolean workerCompleted, gateReleased, workerAlive, closerAlive, postAlive, cleanupAlive;
        boolean cleanupConfirmed;
    }
    private final Clock clock;
    private final Steps steps;
    private final Events events;
    private final long waitMillis;
    private final long controllerTid;
    private CountDownLatch entered, release;
    private Task worker, closer, post, cleanup;
    private volatile Long closeStart, closeEnd;
    private volatile CloseReport closeReport;
    private Throwable failure;
    private boolean started, finished, configured, timedOut, restoreInterrupt;

    B08Runner(Clock clock, Steps steps, Events events, long waitMillis) {
        if (waitMillis < 1 || waitMillis > 5000) throw new IllegalArgumentException("invalid wait bound");
        this.clock = clock; this.steps = steps; this.events = events; this.waitMillis = waitMillis;
        this.controllerTid = clock.tid();
        if (controllerTid <= 0) throw new IllegalArgumentException("invalid controller tid");
    }

    boolean configure() {
        if (started || finished) return false;
        started = true;
        String stage = "installed";
        try {
            steps.install();
            CountDownLatch[] gates = steps.prepareTarget();
            if (gates == null || gates.length != 2 || gates[0] == null || gates[1] == null
                    || gates[0] == gates[1] || gates[0].getCount() != 1 || gates[1].getCount() != 1) {
                throw new IllegalStateException("invalid target gates");
            }
            entered = gates[0]; release = gates[1];
            counts(0, 0, 0, 0);
            events.observed("installed", observation(false));
            stage = "old_entered";
            worker = task("old", () -> steps.invoke(true));
            awaitEntered();
            assertHeld(); counts(1, 0, 1, 0);
            events.observed("old_entered", observation(false));
            stage = "close_returned";
            closer = task("close", () -> {
                Throwable pending = null;
                try { closeStart = clock.wall(); }
                catch (Throwable error) { pending = error; }
                if (pending == null) {
                    try { closeReport = steps.closeRegistration(); }
                    catch (Throwable error) { pending = combine(pending, error); }
                    try { closeEnd = clock.wall(); }
                    catch (Throwable error) { pending = combine(pending, error); }
                }
                if (pending != null) throw pending;
                return null;
            });
            awaitTask(closer, "close"); joinTask(closer);
            if (closer.tid == null || closer.tid.equals(worker.tid)) throw new IllegalStateException("closer tid aliases held worker");
            assertHeld(); counts(1, 0, 1, 0);
            if (closeStart == null || closeEnd == null || closeStart < 0 || closeEnd < closeStart) {
                throw new IllegalStateException("invalid close clock");
            }
            if (!validClose(closeReport)) throw new IllegalStateException("close not confirmed with one inflight call");
            events.observed("close_returned", observation(false));
            stage = "post_close";
            post = task("post", () -> steps.invoke(false));
            awaitTask(post, "post-close call"); joinTask(post);
            if (post.tid == null || post.tid.equals(worker.tid)) throw new IllegalStateException("post tid aliases held worker");
            assertHeld(); requireResult(post.result); counts(1, 0, 1, 1);
            events.observed("post_close", observation(false));
            stage = "old_completed";
            release.countDown();
            awaitTask(worker, "old call");
            requireResult(worker.result); counts(1, 1, 1, 1);
            joinTask(worker); joinTask(closer); joinTask(post);
            events.observed("old_completed", observation(false));
            stage = "configured";
            steps.checkFramework();
        } catch (Throwable error) {
            failure = combine(failure, error);
            restoreInterrupt |= error instanceof InterruptedException;
        } finally {
            if (release != null) release.countDown();
            drainTasks();
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
        if (failure != null) {
            events.failed(stage, failure, observation(false));
            rethrowError(failure);
            return false;
        }
        events.observed("configured", observation(false));
        configured = true;
        return true;
    }

    boolean finish() {
        if (finished) return false;
        finished = true;
        try {
            if (release != null) release.countDown();
            drainTasks();
            if (alive(worker) || alive(closer) || alive(post)) {
                Throwable detail = failure == null ? new IllegalStateException("owned thread still alive") : failure;
                events.failed("cleanup", detail, observation(false));
                rethrowError(detail);
                return false;
            }
            // Never call close synchronously on the controller: even failed install cleanup is bounded.
            cleanup = task("cleanup", () -> { steps.closeEnvironment(); return null; });
            try { awaitTask(cleanup, "environment cleanup"); joinTask(cleanup); }
            catch (Throwable error) { failure = combine(failure, error); restoreInterrupt |= error instanceof InterruptedException; }
            boolean confirmed = configured && failure == null && !timedOut && !alive(cleanup);
            Observation observed = observation(confirmed);
            if (!confirmed) {
                Throwable detail = failure == null ? new IllegalStateException("transaction or cleanup unconfirmed") : failure;
                events.failed("cleanup", detail, observed);
                rethrowError(detail);
                return false;
            }
            events.observed("cleanup", observed);
            return true;
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private void awaitEntered() throws Throwable {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (entered.getCount() != 0) {
            if (worker.done.getCount() == 0) {
                if (worker.error != null) throw worker.error;
                throw new IllegalStateException("old call returned before entered signal");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { timedOut = true; throw new IllegalStateException("old entered timeout"); }
            entered.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS);
        }
        if (worker.error != null) throw worker.error;
    }

    private void awaitTask(Task task, String label) throws Throwable {
        if (!task.done.await(waitMillis, TimeUnit.MILLISECONDS)) {
            timedOut = true; throw new IllegalStateException(label + " timeout");
        }
        if (task.error != null) throw task.error;
    }

    private void assertHeld() {
        if (release == null || release.getCount() != 1 || worker == null
                || worker.done.getCount() == 0 || !worker.thread.isAlive()) {
            throw new IllegalStateException("old call is no longer held");
        }
    }

    private void counts(long before, long after, long oldOriginal, long newOriginal) throws Throwable {
        long[] hooks = steps.hookCounts(), originals = steps.originalCounts();
        if (hooks == null || hooks.length != 2 || originals == null || originals.length != 2
                || hooks[0] != before || hooks[1] != after || originals[0] != oldOriginal || originals[1] != newOriginal) {
            throw new IllegalStateException("callback or original count mismatch");
        }
    }

    private static void requireResult(Object result) {
        if (!(result instanceof Long) || (Long) result != 42L) throw new IllegalStateException("target result mismatch");
    }

    static boolean validClose(CloseReport report) {
        return report != null && report.newCallbacksRejected()
                && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED
                && report.inFlightAtClose() == 1 && report.physicalRelease() != null
                && (report.state() == RegistrationState.CLOSED || report.state() == RegistrationState.RETIRED_RESIDENT);
    }

    private Observation observation(boolean cleanupConfirmed) {
        Observation value = new Observation();
        value.workerTid = worker == null ? null : worker.tid; value.closerTid = closer == null ? null : closer.tid;
        value.postTid = post == null ? null : post.tid; value.closeStart = closeStart; value.closeEnd = closeEnd;
        value.oldResult = worker == null ? null : worker.result; value.postResult = post == null ? null : post.result;
        value.report = closeReport; value.workerCompleted = worker != null && worker.done.getCount() == 0;
        value.gateReleased = release != null && release.getCount() == 0;
        value.workerAlive = alive(worker); value.closerAlive = alive(closer); value.postAlive = alive(post);
        value.cleanupAlive = alive(cleanup); value.cleanupConfirmed = cleanupConfirmed;
        try { value.originalCounts = steps.originalCounts(); } catch (Throwable unavailable) {
            if (unavailable instanceof Error) { failure = combine(failure, unavailable); rethrowError(failure); }
            /* unavailable, never fabricated */
        }
        try { value.hookCounts = steps.hookCounts(); } catch (Throwable unavailable) {
            if (unavailable instanceof Error) { failure = combine(failure, unavailable); rethrowError(failure); }
            /* unavailable, never fabricated */
        }
        return value;
    }

    private interface Action { Object run() throws Throwable; }
    private final class Task implements Runnable {
        final CountDownLatch done = new CountDownLatch(1);
        final Thread thread;
        final Action action;
        volatile Long tid;
        volatile Object result;
        volatile Throwable error;
        Task(String name, Action action) { this.action = action; thread = new Thread(this, "MeguvizHook-B08-" + name); thread.setDaemon(true); }
        @Override public void run() {
            try {
                long observedTid = clock.tid();
                if (observedTid <= 0 || observedTid == controllerTid) throw new IllegalStateException("invalid owned thread tid");
                tid = observedTid;
                result = action.run();
            } catch (Throwable failure) { error = failure; }
            finally { done.countDown(); }
        }
    }
    private Task task(String name, Action action) { Task value = new Task(name, action); value.thread.start(); return value; }
    private static boolean alive(Task value) { return value != null && value.thread.isAlive(); }
    private void joinTask(Task value) throws InterruptedException {
        if (value == null) return;
        value.thread.join(waitMillis);
        if (value.thread.isAlive()) { timedOut = true; throw new IllegalStateException("owned thread join timeout"); }
    }
    private void drainTasks() {
        boolean interrupted = Thread.interrupted();
        for (Task value : new Task[]{worker, closer, post}) {
            if (value == null) continue;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while (value.thread.isAlive()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    timedOut = true; failure = combine(failure, new IllegalStateException("owned thread remains alive")); break;
                }
                try { TimeUnit.NANOSECONDS.timedJoin(value.thread, left); }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (value.error != null) failure = combine(failure, value.error);
        }
        restoreInterrupt |= interrupted;
    }
    private static void rethrowError(Throwable failure) { if (failure instanceof Error) throw (Error) failure; }
    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null || first == next) return next;
        if (next instanceof Error && (!(first instanceof Error) || (fatal(next) && !fatal(first)))) {
            next.addSuppressed(first); return next;
        }
        first.addSuppressed(next); return first;
    }
    @SuppressWarnings("removal") private static boolean fatal(Throwable value) {
        return value instanceof VirtualMachineError || value instanceof ThreadDeath;
    }
}
