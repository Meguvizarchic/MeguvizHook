package io.github.jichuo1.meguviz.hook.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An optional, bounded owner for module resources and explicitly leased work. The caller controls
 * all threads and framework lifecycle callbacks. This class never creates threads, waits for work,
 * closes a HookEnvironment, transfers framework handles, or infers that native code is quiescent.
 *
 * <p>To prepare a reload, stop new work with {@link #requestQuiescence()}, then call
 * {@link #tryCleanup()} after all explicitly acquired leases have closed. A BUSY result is not
 * success. The caller must independently stop or account for all work that did not acquire a lease,
 * including hook callbacks, threads, native hooks and external callbacks. Never register an old
 * HookEnvironment as a resource if its framework handles need to be adopted by a new generation.
 */
public final class ModuleLifecycle {
    public enum State { OPEN, DRAINING, CLEANING, QUIESCED, FAILED }
    public enum CleanupStatus { BUSY, IN_PROGRESS, QUIESCED, FAILED }

    /** One non-fatal cleanup failure; the resource may already have had external side effects. */
    public static final class Failure {
        private final String resource;
        private final Throwable cause;
        private Failure(String resource, Throwable cause) { this.resource = resource; this.cause = cause; }
        public String resource() { return resource; }
        public Throwable cause() { return cause; }
    }

    /** Result of one cleanup attempt; it does not attest to untracked work or physical release. */
    public static final class CleanupReport {
        private final CleanupStatus status;
        private final int inFlight;
        private final List<Failure> failures;
        private CleanupReport(CleanupStatus status, int inFlight, List<Failure> failures) {
            this.status = status;
            this.inFlight = inFlight;
            this.failures = Collections.unmodifiableList(new ArrayList<Failure>(failures));
        }
        public CleanupStatus status() { return status; }
        public int inFlight() { return inFlight; }
        public List<Failure> failures() { return failures; }
    }

    /** Idempotent lease; it can be closed on a different thread and is not a hook callback view. */
    public final class Lease implements AutoCloseable {
        private boolean released;
        private Lease() { }
        @Override public void close() {
            synchronized (ModuleLifecycle.this) {
                if (!released) {
                    released = true;
                    inFlight--;
                }
            }
        }
    }

    private static final class Resource {
        final String name;
        final AutoCloseable closeable;
        Resource(String name, AutoCloseable closeable) { this.name = name; this.closeable = closeable; }
    }

    private static final CleanupReport INTERRUPTED_REPORT =
            new CleanupReport(CleanupStatus.FAILED, 0, Collections.<Failure>emptyList());

    private final int maxResources;
    private final List<Resource> resources = new ArrayList<Resource>();
    private final Map<AutoCloseable, Boolean> identities = new IdentityHashMap<AutoCloseable, Boolean>();
    private State state = State.OPEN;
    private int inFlight;
    private CleanupReport finalReport;

    public ModuleLifecycle(int maxResources) {
        if (maxResources < 1 || maxResources > 65536) throw new IllegalArgumentException("maxResources must be 1..65536");
        this.maxResources = maxResources;
    }

    /** Register a resource by identity for one reverse-order cleanup attempt. */
    public synchronized void own(String name, AutoCloseable resource) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(resource, "resource");
        if (name.isEmpty() || name.length() > 128) throw new IllegalArgumentException("resource name must be 1..128");
        if (state != State.OPEN) throw new IllegalStateException("lifecycle is not open");
        if (resources.size() == maxResources) throw new IllegalStateException("resource capacity reached");
        if (identities.containsKey(resource)) throw new IllegalArgumentException("resource identity already owned");
        Resource owned = new Resource(name, resource);
        resources.add(owned);
        try {
            identities.put(resource, Boolean.TRUE);
        } catch (RuntimeException | Error failure) {
            resources.remove(resources.size() - 1);
            identities.remove(resource);
            throw failure;
        }
    }

    /** Returns null when new work is no longer admitted; only acquired leases are counted. */
    public synchronized Lease tryEnter() {
        if (state != State.OPEN) return null;
        if (inFlight == Integer.MAX_VALUE) throw new IllegalStateException("too many lifecycle leases");
        Lease lease = new Lease();
        inFlight++;
        return lease;
    }

    /** Reject new resource registrations and leases; existing leases remain valid until closed. */
    public synchronized void requestQuiescence() {
        if (state == State.OPEN) state = State.DRAINING;
    }

    /**
     * Abort a pending quiescence before cleanup has started, for example after a BUSY reload was
     * rejected. Returns false once cleanup begins or finishes because resources may have changed.
     */
    public synchronized boolean resumeAdmissions() {
        if (state != State.DRAINING) return false;
        state = State.OPEN;
        return true;
    }

    public synchronized State state() { return state; }
    public synchronized int inFlight() { return inFlight; }

    /**
     * Attempts cleanup only after quiescence was requested and all leased work has finished.
     * Nonfatal failures do not skip later resources. Cleanup is attempted at most once: a failure
     * can follow a side effect, so the report is retained and no retry or reopen is implied.
     * An interrupted cleanup is recorded, remaining resources are still attempted, and the
     * calling thread's interrupt status is preserved even if a later resource clears it.
     * Fatal VM errors propagate; remaining resources may not have been attempted.
     */
    public CleanupReport tryCleanup() {
        List<Resource> owned;
        List<Failure> failures = new ArrayList<Failure>();
        synchronized (this) {
            if (state == State.OPEN) throw new IllegalStateException("request quiescence before cleanup");
            if (state == State.CLEANING) return new CleanupReport(CleanupStatus.IN_PROGRESS, inFlight, Collections.<Failure>emptyList());
            if (state == State.QUIESCED || state == State.FAILED) return finalReport;
            if (inFlight != 0) return new CleanupReport(CleanupStatus.BUSY, inFlight, Collections.<Failure>emptyList());
            owned = new ArrayList<Resource>(resources);
            state = State.CLEANING;
        }
        CleanupReport report;
        boolean interrupted = Thread.currentThread().isInterrupted();
        try {
            for (int i = owned.size() - 1; i >= 0; i--) {
                Resource item = owned.get(i);
                try {
                    item.closeable.close();
                } catch (Throwable failure) {
                    if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                    if (failure instanceof InterruptedException) interrupted = true;
                    failures.add(new Failure(item.name, failure));
                } finally {
                    if (Thread.currentThread().isInterrupted()) interrupted = true;
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            report = new CleanupReport(failures.isEmpty() ? CleanupStatus.QUIESCED : CleanupStatus.FAILED, 0, failures);
        } catch (RuntimeException | Error fatal) {
            synchronized (this) {
                finalReport = INTERRUPTED_REPORT;
                state = State.FAILED;
            }
            throw fatal;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        synchronized (this) {
            finalReport = report;
            state = failures.isEmpty() ? State.QUIESCED : State.FAILED;
            // Keep failed resources reachable: their external state can be ambiguous. On clean
            // completion the owner no longer needs to retain any closed resource reference.
            if (failures.isEmpty()) { resources.clear(); identities.clear(); }
            return finalReport;
        }
    }
}
