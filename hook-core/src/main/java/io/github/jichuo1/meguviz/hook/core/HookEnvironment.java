package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.api.StringArgumentCondition;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BatchMemberResolver;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer entry point. Install order is fixed: validate descriptor, prune by process, check
 * capabilities, bind against the actual loader, apply match policy and static return checks, build
 * immutable plans, install, then publish the receipt. Resolution, pruning and capability checks
 * never run inside a hot callback.
 *
 * <p>{@link MatchPolicy#ALL} creates one logical registration with one plan and receipt per target.
 * Ordinary per-target failure does not roll back successful siblings: the result is explicitly
 * PARTIALLY_INSTALLED and no cross-target atomicity is claimed. An identity change is different: every
 * entry installed by the unpublished attempt is closed and no stale registration is published.
 * Ordinary installation has no resolution cache. The explicit preparation API retains a bounded
 * positive snapshot for one checked commit, never a reusable negative lookup. {@link #installAll}
 * lets a {@link BatchMemberResolver} reuse positive work across the targets of that one call only.
 */
public final class HookEnvironment implements AutoCloseable {
    private final HookBackend backend;
    private final MemberResolver resolver;
    private final DiagnosticsSink diagnostics;
    private final Map<HookId, CoreRegistration> registrations = new LinkedHashMap<HookId, CoreRegistration>();
    private final Set<HookId> interruptedInstalls = new HashSet<HookId>();
    // The monitor is reentrant: reject same-id mutation before invoking any external code.
    private final Set<HookId> activeMutations = new HashSet<HookId>();
    private final ThreadLocal<Set<HookId>> activePreparations = new ThreadLocal<Set<HookId>>();
    // Preserve the former monitor-based reentry rejection while external checks run outside it.
    private final ThreadLocal<Boolean> preparationCheckActive = new ThreadLocal<Boolean>();
    // Independent of registrations: it remains reachable if publishing there fails before/after put.
    private final Map<HookId, CoreRegistration> interruptedInstallPublications = new LinkedHashMap<HookId, CoreRegistration>();
    // Anchors both generations until publication; retained for an indeterminate replacement.
    private final Map<HookId, CoreRegistration> interruptedReplacements = new LinkedHashMap<HookId, CoreRegistration>();
    private final AtomicLong generations = new AtomicLong(1L);
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private boolean closed;
    private Thread closeOwner;

    private HookEnvironment(HookBackend backend, MemberResolver resolver, DiagnosticsSink diagnostics) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static HookEnvironment create(HookBackend backend) {
        return new HookEnvironment(backend, new ReflectiveMemberResolver(), DiagnosticsSink.NONE);
    }

    /** Create an environment with a custom resolver and the default no-op diagnostics sink. */
    public static HookEnvironment create(HookBackend backend, MemberResolver resolver) {
        return create(backend, resolver, DiagnosticsSink.NONE);
    }

    public static HookEnvironment create(HookBackend backend, MemberResolver resolver, DiagnosticsSink diagnostics) {
        return new HookEnvironment(backend, resolver, diagnostics);
    }

    public BackendCapabilities capabilities() {
        return backend.capabilities();
    }

    /**
     * Install with a per-environment, per-id reentry guard. A synchronous nested mutation of the
     * same id throws before invoking the backend; different ids may be nested. A backend or
     * resolver must not synchronously close this environment during an active mutation.
     */
    public synchronized RegistrationReceipt install(HookSpec spec) {
        return guardedInstall(spec, null);
    }

    /**
     * Install several specs in order, each with exactly the checks, ordering and receipt of
     * {@link #install(HookSpec)}, under one acquisition of the environment monitor. Specs are
     * independent: one failing, conflicting or missing target never skips or rolls back another,
     * and no cross-spec atomicity is claimed. A {@code close()} from another thread waits until the
     * whole call returns.
     *
     * <p>When the environment's resolver is a {@link BatchMemberResolver}, descriptor targets that
     * observe the same loader and code epoch share one {@link BatchMemberResolver.Batch}. A target
     * that observes a different loader or epoch closes it and opens a new one, and the last one is
     * closed before this method returns or throws, so nothing is reused after the call and no
     * missing target is remembered. The default {@link ReflectiveMemberResolver} then loads each
     * class and copies its declared members once per call instead of once per spec; specs on one
     * class therefore receive the same {@code Method}/{@code Constructor} instances, which their
     * callbacks see through {@code HookInvocation.target()} (as with several
     * {@code TargetSpec.ofMember} specs sharing one object). Batch reuse relies on the backend's
     * code epoch never returning to an earlier value. If {@code openBatch} throws
     * {@link BackendInstallException}, or a batch's {@code close} throws a non-fatal throwable,
     * the event goes to the environment's {@link DiagnosticsSink} as {@code BACKEND_FAILURE} and
     * the affected targets resolve through {@link BatchMemberResolver#resolve} as {@code install}
     * would; fatal VM events propagate.
     *
     * <p>An unchecked exception or error from one install propagates as it would from
     * {@code install}, and the remaining specs are not attempted. Registrations installed by
     * earlier specs stay installed and owned by this environment (closed by {@link #close()}); use
     * {@link #installAll(Iterable, Consumer)} to receive each receipt as soon as it exists.
     *
     * @return one receipt per spec, in iteration order
     */
    public synchronized List<RegistrationReceipt> installAll(Iterable<HookSpec> specs) {
        final List<RegistrationReceipt> receipts = new ArrayList<RegistrationReceipt>();
        installAll(specs, new Consumer<RegistrationReceipt>() {
            @Override public void accept(RegistrationReceipt receipt) { receipts.add(receipt); }
        });
        return receipts;
    }

    /**
     * {@link #installAll(Iterable)} that hands each receipt to {@code receipts} right after that
     * spec's installation returns, before the next spec starts. The consumer runs under the
     * environment monitor and must not wait for another thread that uses this environment. If it
     * throws, the exception propagates and the remaining specs are not attempted.
     */
    public synchronized void installAll(Iterable<HookSpec> specs, Consumer<? super RegistrationReceipt> receipts) {
        Objects.requireNonNull(specs, "specs");
        Objects.requireNonNull(receipts, "receipts");
        InstallBatch batch = resolver instanceof BatchMemberResolver
                ? new InstallBatch((BatchMemberResolver) resolver, diagnostics) : null;
        Throwable primary = null;
        try {
            for (HookSpec spec : specs) {
                receipts.accept(guardedInstall(spec, null, null, null, batch));
            }
        } catch (Throwable failure) {
            primary = failure;
            throw TypeChecks.rethrow(failure);
        } finally {
            if (batch != null) batch.finish(primary);
        }
    }

    /**
     * Resolution through at most one open batch at a time, bound to the loader and epoch observed
     * by the target that opened it. Used only by installAll, under the environment monitor.
     */
    private static final class InstallBatch {
        private final BatchMemberResolver source;
        private final DiagnosticsSink diagnostics;
        private BatchMemberResolver.Batch current;
        // No batch for the bound identity: openBatch declined or failed; resolve per target.
        private boolean unavailable;
        private ClassLoader loader;
        private long epoch;
        private HookId lastId;

        InstallBatch(BatchMemberResolver source, DiagnosticsSink diagnostics) {
            this.source = source;
            this.diagnostics = diagnostics;
        }

        List<Executable> resolve(HookId id, TargetSpec.DescriptorTarget target, ClassLoader targetLoader, long targetEpoch)
                throws BackendInstallException {
            lastId = id;
            if ((current != null || unavailable) && (targetLoader != loader || targetEpoch != epoch)) {
                closeCurrent(null);
            }
            if (current == null && !unavailable) {
                loader = targetLoader;
                epoch = targetEpoch;
                try {
                    current = source.openBatch(targetLoader);
                } catch (BackendInstallException failure) {
                    report(id, failure, "member batch could not be opened; resolving each target separately");
                    current = null;
                }
                unavailable = current == null;
            }
            return current == null ? source.resolve(target, targetLoader) : current.resolve(target);
        }

        /** Closes the open batch; a non-fatal failure is reported, a fatal one propagates. */
        private void closeCurrent(Throwable primary) {
            BatchMemberResolver.Batch closing = current;
            current = null;
            unavailable = false;
            if (closing == null) return;
            try {
                closing.close();
            } catch (Throwable cleanup) {
                if (TypeChecks.isFatal(cleanup)) {
                    if (primary == null) throw TypeChecks.fatal(cleanup);
                    if (!TypeChecks.isFatal(primary)) {
                        suppressPreservingFatal(cleanup, primary);
                        throw TypeChecks.fatal(cleanup);
                    }
                    suppressPreservingFatal(primary, cleanup);
                    return;
                }
                if (primary != null) {
                    suppressPreservingFatal(primary, cleanup);
                } else {
                    report(lastId, cleanup, "member batch close failed; the call continues");
                }
            }
        }

        void finish(Throwable primary) {
            closeCurrent(primary);
        }

        private void report(HookId id, Throwable failure, String message) {
            try {
                diagnostics.record(new DiagnosticsSink.Event(DiagnosticsSink.Event.Kind.BACKEND_FAILURE,
                        id, -1L, failure, message));
            } catch (Throwable sinkFailure) {
                if (TypeChecks.isFatal(sinkFailure)) throw TypeChecks.fatal(sinkFailure);
                // A failing diagnostics sink never changes an installation outcome.
            }
        }
    }

    /**
     * Install with the same ordering and receipt as {@link #install(HookSpec)}, while explicitly
     * retaining a checked exception thrown by the member resolver for this request. No diagnostic
     * sink or other callback is invoked by this method. A stale-identity receipt can also carry
     * the exception that occurred before the identity check. Unchecked and fatal failures, including
     * failures from the identity check itself, propagate as they do for ordinary installation.
     *
     * <p>The returned object can retain a throwable graph, sensitive details and class loaders. The
     * caller owns its retention and should release it after inspection. A null resolution failure
     * says nothing about other failure stages or whether installation succeeded.
     */
    public synchronized InstallResolutionResult installWithResolutionDiagnostics(HookSpec spec) {
        ResolutionFailureCapture capture = new ResolutionFailureCapture();
        RegistrationReceipt receipt = guardedInstall(spec, null, null, capture);
        return new InstallResolutionResult(receipt, capture.failure);
    }

    /** Caller-owned result of an installation with explicit resolver-failure capture. */
    public static final class InstallResolutionResult {
        private final RegistrationReceipt receipt;
        private final BackendInstallException resolutionFailure;

        private InstallResolutionResult(RegistrationReceipt receipt, BackendInstallException resolutionFailure) {
            this.receipt = receipt;
            this.resolutionFailure = resolutionFailure;
        }

        public RegistrationReceipt receipt() { return receipt; }

        /** Exact checked resolver failure, or null when this attempt did not catch one. */
        public BackendInstallException resolutionFailure() { return resolutionFailure; }
    }

    private static final class ResolutionFailureCapture {
        private BackendInstallException failure;
    }


    /**
     * Explicit opt-in only: the caller must ensure the resolver, context and all providers can run
     * concurrently. CLOSED includes a pending close request and does not prove backend cleanup.
     */
    public enum PreparationStatus {
        READY, CLOSED, FILTERED, UNSUPPORTED, TARGET_UNSUPPORTED, NOT_FOUND_NOW,
        TOO_MANY, INVALID_RESULT, STALE_IDENTITY, RESOLUTION_FAILED, IDENTITY_CHECK_FAILED
    }

    /** The status is a preparation outcome, not an installation receipt. */
    public static final class Preparation {
        private final PreparationStatus status;
        private final PreparedInstall prepared;
        private final Throwable failure;
        private final Throwable checkFailure;
        private Preparation(PreparationStatus status, PreparedInstall prepared, Throwable failure) {
            this(status, prepared, failure, null);
        }
        private Preparation(PreparationStatus status, PreparedInstall prepared, Throwable failure, Throwable checkFailure) {
            this.status = status; this.prepared = prepared; this.failure = failure; this.checkFailure = checkFailure;
        }
        public PreparationStatus status() { return status; }
        public PreparedInstall prepared() { return prepared; }
        public Throwable failure() { return failure; }
        public Throwable checkFailure() { return checkFailure; }
    }

    private static Preparation notReady(PreparationStatus status) {
        return new Preparation(status, null, null);
    }

    private static final class PreparedPayload {
        final HookEnvironment owner;
        final HookSpec spec;
        final ClassLoader loader;
        final long epoch;
        final List<Executable> candidates;
        PreparedPayload(HookEnvironment owner, HookSpec spec, ClassLoader loader, long epoch,
                        List<Executable> candidates) {
            this.owner = owner; this.spec = spec; this.loader = loader; this.epoch = epoch;
            this.candidates = candidates;
        }
    }

    /** A single-use, caller-owned handle. Closing it abandons the prepared candidate references. */
    public static final class PreparedInstall implements AutoCloseable {
        private final Object lock = new Object();
        private PreparedPayload payload;
        private PreparedInstall(PreparedPayload payload) { this.payload = payload; }

        /** Claim once, then revalidate and publish under the originating environment's lock. */
        public RegistrationReceipt commit() {
            PreparedPayload claimed;
            synchronized (lock) {
                if (payload == null) throw new IllegalStateException("prepared install already consumed or closed");
                claimed = payload;
                payload = null;
            }
            return claimed.owner.commitPrepared(claimed);
        }

        /** Does not interrupt a commit that has already claimed this handle. */
        @Override public void close() { synchronized (lock) { payload = null; } }
    }

    private synchronized RegistrationReceipt commitPrepared(PreparedPayload prepared) {
        if (prepared.owner != this) throw new IllegalArgumentException("wrong environment");
        return guardedInstall(prepared.spec, null, prepared);
    }

    /**
     * Resolve on the caller's thread without holding the environment lock. Calling this method is
     * explicit acceptance of concurrent resolver, backend context and provider invocations. No
     * thread, queue, retry, timeout, negative cache or cross-target atomicity is supplied. Context,
     * capability, resolver or provider calls may already have started when close() wins; their
     * effects and the sampled preparation result are not an atomic snapshot. This method never calls
     * backend.install; caller-provided context, resolver or provider code may reenter and install.
     *
     * <p>The upper bound applies only to this method's retained copy; a resolver may itself allocate
     * or enumerate more. Results stay bound to this exact spec, environment, loader and epoch.
     */
    public Preparation prepareConcurrently(HookSpec spec, int maxCandidates) {
        if (Thread.holdsLock(this)) {
            throw new IllegalStateException("cannot prepare while holding the environment monitor");
        }
        if (Boolean.TRUE.equals(preparationCheckActive.get())) {
            throw new IllegalStateException("cannot recursively prepare during preparation checks");
        }
        Objects.requireNonNull(spec, "spec");
        if (maxCandidates < 1 || maxCandidates > 4096) throw new IllegalArgumentException("candidate limit must be 1..4096");
        if (!(spec.target() instanceof TargetSpec.DescriptorTarget)) return notReady(PreparationStatus.TARGET_UNSUPPORTED);
        HookId id = spec.id();
        Set<HookId> active = activePreparations.get();
        if (active != null && active.contains(id)) throw new IllegalStateException("recursive preparation for hook id " + id);
        boolean newSet = active == null;
        if (newSet) active = new HashSet<HookId>();
        boolean registered = false;
        try {
            if (newSet) activePreparations.set(active);
            // Establish the finally cleanup before mutating the set; removal is harmless if add fails.
            registered = true;
            active.add(id);
            return prepareConcurrentInternal(spec, maxCandidates);
        } finally {
            if (registered) active.remove(id);
            if (active.isEmpty()) activePreparations.remove();
        }
    }

    private Preparation prepareConcurrentInternal(HookSpec spec, int maxCandidates) {
        final long epoch;
        final ClassLoader loader;
        Boolean previousCheck = preparationCheckActive.get();
        try {
            preparationCheckActive.set(Boolean.TRUE);
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            io.github.jichuo1.meguviz.hook.core.spi.BackendContext processContext = backend.context();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            String processName = processContext.processName();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            boolean matches = spec.processFilter().matches(processName);
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            if (!matches) return notReady(PreparationStatus.FILTERED);
            for (Capability capability : spec.requiredCapabilities()) {
                BackendCapabilities capabilities = backend.capabilities();
                if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
                CapabilityResult answer = capabilities.lookup(capability);
                if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
                if (!answer.isSupportedInDeclaredCell()) {
                    return notReady(PreparationStatus.UNSUPPORTED);
                }
            }
            if (spec.errorPolicy() == ErrorPolicy.FRAMEWORK_PROTECTIVE) {
                BackendCapabilities capabilities = backend.capabilities();
                if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
                CapabilityResult answer = capabilities.lookup(Capability.FRAMEWORK_PROTECTIVE);
                if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
                if (!answer.isSupportedInDeclaredCell()) return notReady(PreparationStatus.UNSUPPORTED);
            }
            BackendCapabilities capabilities = backend.capabilities();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            ChainSemantics chain = capabilities.chainSemantics();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            if (chain != ChainSemantics.FRAMEWORK_CHAIN) return notReady(PreparationStatus.UNSUPPORTED);
            io.github.jichuo1.meguviz.hook.core.spi.BackendContext epochContext = backend.context();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            epoch = epochContext.codeEpoch();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            io.github.jichuo1.meguviz.hook.core.spi.BackendContext loaderContext = backend.context();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            loader = loaderContext.classLoader();
            if (preparationClosed()) return notReady(PreparationStatus.CLOSED);
            PreparationStatus beforeResolve = preparationIdentityStatus(loader, epoch);
            if (beforeResolve != null) return notReady(beforeResolve);
        } finally {
            if (previousCheck == null) preparationCheckActive.remove();
            else preparationCheckActive.set(previousCheck);
        }
        final List<Executable> resolved;
        try {
            resolved = resolver.resolve((TargetSpec.DescriptorTarget) spec.target(), loader);
        } catch (BackendInstallException failure) {
            final PreparationStatus identity;
            try { identity = preparationIdentityStatus(loader, epoch); }
            catch (Throwable checkFailure) { return preparationRecheckFailure(failure, checkFailure); }
            synchronized (this) {
                if (closed || closeRequested.get()) return new Preparation(PreparationStatus.CLOSED, null, failure);
                if (identity != null) return new Preparation(identity, null, failure);
                return new Preparation(PreparationStatus.RESOLUTION_FAILED, null, failure);
            }
        }
        List<Executable> snapshot = new ArrayList<Executable>();
        PreparationStatus rejected = resolved == null ? PreparationStatus.INVALID_RESULT : null;
        if (resolved != null) {
            for (Executable candidate : resolved) {
                if (candidate == null) { rejected = PreparationStatus.INVALID_RESULT; break; }
                if (snapshot.size() == maxCandidates) { rejected = PreparationStatus.TOO_MANY; break; }
                snapshot.add(candidate);
            }
        }
        if (rejected == null && snapshot.isEmpty()) rejected = PreparationStatus.NOT_FOUND_NOW;
        List<Executable> immutable = Collections.unmodifiableList(snapshot);
        final PreparationStatus identity;
        try { identity = preparationIdentityStatus(loader, epoch); }
        catch (Throwable checkFailure) { return preparationRecheckFailure(null, checkFailure); }
        synchronized (this) {
            if (closed || closeRequested.get()) return notReady(PreparationStatus.CLOSED);
            if (identity != null) return notReady(identity);
            if (rejected != null) return notReady(rejected);
            return new Preparation(PreparationStatus.READY,
                    new PreparedInstall(new PreparedPayload(this, spec, loader, epoch, immutable)), null);
        }
    }

    /** Fatal recheck errors take priority over a checked query error. */
    private Preparation preparationRecheckFailure(Throwable original, Throwable checkFailure) {
        if (TypeChecks.isFatal(checkFailure)) {
            if (original != null) suppressPreservingFatal(checkFailure, original);
            throw TypeChecks.fatal(checkFailure);
        }
        return new Preparation(preparationClosed() ? PreparationStatus.CLOSED : PreparationStatus.IDENTITY_CHECK_FAILED,
                null, original, checkFailure);
    }

    /** Short state read only; no caller-provided code runs under the monitor. */
    private synchronized boolean preparationClosed() { return closed || closeRequested.get(); }

    /** Each context observation runs outside the monitor; a sampled close wins over stale identity. */
    private PreparationStatus preparationIdentityStatus(ClassLoader loader, long epoch) {
        Boolean previousCheck = preparationCheckActive.get();
        try {
            preparationCheckActive.set(Boolean.TRUE);
            if (preparationClosed()) return PreparationStatus.CLOSED;
            io.github.jichuo1.meguviz.hook.core.spi.BackendContext current = backend.context();
            if (preparationClosed()) return PreparationStatus.CLOSED;
            ClassLoader observed = current.classLoader();
            if (preparationClosed()) return PreparationStatus.CLOSED;
            if (observed != loader) return PreparationStatus.STALE_IDENTITY;
            current = backend.context();
            if (preparationClosed()) return PreparationStatus.CLOSED;
            long observedEpoch = current.codeEpoch();
            if (preparationClosed()) return PreparationStatus.CLOSED;
            return observedEpoch != epoch ? PreparationStatus.STALE_IDENTITY : null;
        } finally {
            if (previousCheck == null) preparationCheckActive.remove();
            else preparationCheckActive.set(previousCheck);
        }
    }

    /**
     * Adopts one framework-delivered old hook into this new module generation. The caller supplies
     * a backend-owned bridge valid only for the current framework reload callback, with an explicit
     * and trustworthy mapping between that old handle and {@code spec}; neither old SDK objects nor
     * guessed matches are accepted as a migration mechanism. The module remains responsible for
     * retiring old-generation resources and for handling every old hook not adopted here.
     *
     * <p>Only a single resolved target is supported; this does not promise atomicity across hooks or
     * class loaders. On an indeterminate backend outcome the new channel is retired and this logical
     * id is quarantined. The old framework entry may or may not have been replaced.
     */
    public synchronized RegistrationReceipt adopt(HookSpec spec, BackendAdoption adoption) {
        return guardedInstall(spec, Objects.requireNonNull(adoption, "adoption"));
    }

    private RegistrationReceipt guardedInstall(HookSpec spec, BackendAdoption adoption) {
        return guardedInstall(spec, adoption, null);
    }

    private RegistrationReceipt guardedInstall(HookSpec spec, BackendAdoption adoption, PreparedPayload prepared) {
        return guardedInstall(spec, adoption, prepared, null);
    }

    private RegistrationReceipt guardedInstall(HookSpec spec, BackendAdoption adoption, PreparedPayload prepared,
                                                ResolutionFailureCapture capture) {
        return guardedInstall(spec, adoption, prepared, capture, null);
    }

    private RegistrationReceipt guardedInstall(HookSpec spec, BackendAdoption adoption, PreparedPayload prepared,
                                                ResolutionFailureCapture capture, InstallBatch batch) {
        HookId id = checkMutation(spec);
        try {
            activeMutations.add(id);
            return installInternal(spec, adoption, prepared, capture, batch);
        }
        finally { activeMutations.remove(id); }
    }

    private HookId checkMutation(HookSpec spec) {
        HookId id = Objects.requireNonNull(spec, "spec").id();
        if (activeMutations.contains(id)) {
            throw new IllegalStateException("reentrant mutation for hook id " + id);
        }
        return id;
    }

    private RegistrationReceipt installInternal(HookSpec spec, BackendAdoption adoption, PreparedPayload prepared,
                                                 ResolutionFailureCapture capture, InstallBatch batch) {
        Objects.requireNonNull(spec, "spec");
        HookId id = spec.id();
        String backendId = backend.id();
        if (closed) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, -1L, "environment is closed");
        }
        if (closeRequested.get()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, -1L, "environment close is requested");
        }
        if (adoption != null && adoption.backend() != backend) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, -1L, "adoption bridge belongs to a different backend instance");
        }
        if (adoption != null && spec.target().matchPolicy() == MatchPolicy.ALL) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, -1L, "hot-reload adoption requires one target; ALL is not supported");
        }
        CoreRegistration interruptedPublication = interruptedInstallPublications.get(id);
        if (interruptedPublication != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedPublication.generation(),
                    "previous install publication was interrupted; id is quarantined; "
                            + "bound-handle cleanup: " + interruptedPublication.closeReport());
        }
        CoreRegistration interruptedReplacement = interruptedReplacements.get(id);
        if (interruptedReplacement != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedReplacement.generation(), "replacement publication is indeterminate; id is quarantined");
        }
        if (interruptedInstalls.contains(id)) {
            CoreRegistration interrupted = registrations.get(id);
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interrupted.generation(),
                    "previous install was interrupted; backend effects are unknown and this id "
                            + "is quarantined in this environment; "
                            + "bound-handle cleanup: " + interrupted.closeReport());
        }
        if (!spec.processFilter().matches(backend.context().processName())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FILTERED,
                    backendId, -1L, "process " + backend.context().processName() + " pruned at install time");
        }
        for (Capability capability : spec.requiredCapabilities()) {
            CapabilityResult result = backend.capabilities().lookup(capability);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "required " + capability + ": " + result);
            }
        }
        if (spec.errorPolicy() == ErrorPolicy.FRAMEWORK_PROTECTIVE) {
            CapabilityResult result = backend.capabilities().lookup(Capability.FRAMEWORK_PROTECTIVE);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "framework protective policy is not supported: " + result);
            }
        }
        if (backend.capabilities().chainSemantics() != ChainSemantics.FRAMEWORK_CHAIN) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "this slice dispatches framework chains only; backend declares "
                            + backend.capabilities().chainSemantics());
        }

        long epochBefore = backend.context().codeEpoch();
        ClassLoader loaderBefore = backend.context().classLoader();
        CoreRegistration previous = registrations.get(id);
        if (previous != null && retirementBlocksReuse(previous)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, previous.generation(), "previous registration entry restoration is not confirmed");
        }
        if (previous != null && previous.state() == RegistrationState.INSTALLED
                && (previous.plan().loader() != loaderBefore || previous.plan().codeEpoch() != epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, previous.generation(), "previous registration has a different loader or code epoch; close before retry");
        }
        List<Executable> candidates;
        if (prepared != null) {
            if (prepared.owner != this || prepared.spec != spec) throw new IllegalArgumentException("wrong prepared install");
            if (prepared.loader != loaderBefore || prepared.epoch != epochBefore) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                        backendId, -1L, "prepared loader or code epoch changed; plan discarded");
            }
            candidates = prepared.candidates;
        } else if (spec.target() instanceof TargetSpec.MemberTarget) {
            candidates = Collections.singletonList(((TargetSpec.MemberTarget) spec.target()).executable());
        } else {
            try {
                TargetSpec.DescriptorTarget descriptor = (TargetSpec.DescriptorTarget) spec.target();
                candidates = batch != null
                        ? batch.resolve(id, descriptor, loaderBefore, epochBefore)
                        : resolver.resolve(descriptor, loaderBefore);
            } catch (BackendInstallException failure) {
                if (capture != null) capture.failure = failure;
                if (identityChanged(loaderBefore, epochBefore)) {
                    return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                            backendId, -1L, "loader or code epoch changed during resolution; plan discarded");
                }
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED, backendId, -1L, failure.getMessage());
            }
        }
        if (identityChanged(loaderBefore, epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "loader or code epoch changed during resolution; plan discarded");
        }
        if (candidates.isEmpty()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TARGET_MISSING,
                    backendId, -1L, "no target matched " + spec.target());
        }
        if (candidates.size() > 1 && spec.target().matchPolicy() == MatchPolicy.UNIQUE) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.AMBIGUOUS_TARGET,
                    backendId, -1L, candidates.size() + " targets matched " + spec.target() + " under UNIQUE");
        }
        List<Executable> targets = spec.target().matchPolicy() == MatchPolicy.ALL
                ? candidates : Collections.singletonList(candidates.get(0));

        String invalidConstant = invalidConstantReplacement(spec, targets);
        if (spec.stringArgumentCondition() != null && identityChanged(loaderBefore, epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "loader or code epoch changed during conditional validation; plan discarded");
        }
        if (invalidConstant != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED,
                    backendId, -1L, invalidConstant);
        }

        CoreRegistration existing = registrations.get(id);
        if (existing != null && existing.state() == RegistrationState.RETIRING) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, existing.generation(), "previous generation is still retiring; retry after close completes");
        }
        if (existing != null && existing.state() != RegistrationState.INSTALLED) {
            if (retirementBlocksReuse(existing)) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                        backendId, existing.generation(), "previous registration entry restoration is not confirmed");
            }
            // A fully closed generation must not block a fresh logical registration forever. The old
            // immutable plan may still be draining in flight, but it never receives new SDK work.
            registrations.remove(id);
            existing = null;
        }
        if (existing != null) {
            if (adoption != null) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                        backendId, existing.generation(), "adoption requires an unused logical id in the new environment");
            }
            if (sameTargets(existing, targets) && existing.plan().spec().hasSameSemantics(spec)
                    && existing.isComplete()) {
                if (existing.state() != RegistrationState.INSTALLED) {
                    return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                            backendId, existing.generation(), "registration closed during install resolution");
                }
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.ALREADY_INSTALLED,
                        backendId, existing.generation(), "identical registration already installed");
            }
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "logical id is already used; close a partial/old registration before retry");
        }

        if (identityChanged(loaderBefore, epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "loader or code epoch changed before install; plan discarded");
        }

        long generation = generations.getAndIncrement();
        List<BoundPlan> plans = new ArrayList<BoundPlan>(targets.size());
        for (Executable target : targets) {
            plans.add(new BoundPlan(spec, target, generation, loaderBefore, epochBefore));
        }
        CoreRegistration registration = new CoreRegistration(plans, diagnostics);
        List<TargetInstallReceipt> targetReceipts = new ArrayList<TargetInstallReceipt>(plans.size());
        int installed = 0;
        if (adoption != null) {
            // Anchor the new generation before the external operation may commit.
            interruptedInstallPublications.put(id, registration);
        }
        for (int index = 0; index < plans.size(); index++) {
            BoundPlan plan = plans.get(index);
            boolean backendReturned = false;
            try {
                BackendHandle handle = Objects.requireNonNull(adoption == null
                                ? backend.install(plan, registration.channel(plan, index))
                                : adoption.replace(plan, registration.channel(plan, index)),
                        "backend returned a null install handle; installation outcome is unknown");
                backendReturned = true;
                if (adoption == null) {
                    registration.bind(plan, handle);
                } else {
                    registration.bindAdopted(plan, handle);
                }
                targetReceipts.add(TargetInstallReceipt.installed(plan.target()));
                installed++;
            } catch (BackendInstallException failure) {
                if (backendReturned) {
                    // The declared no-commit promise applies to the backend call, not later work.
                    if (adoption == null) quarantineInterruptedInstall(id, registration, failure, false);
                    else retireAdoptionAfterFailure(registration, failure);
                    throw TypeChecks.rethrow(failure);
                }
                try {
                    targetReceipts.add(TargetInstallReceipt.failed(plan.target(), failure.getMessage()));
                } catch (Throwable diagnosticFailure) {
                    // This target is guaranteed uncommitted, but earlier targets may be bound.
                    // Do not let an overridden getMessage() bypass their cleanup and isolation.
                    suppressPreservingFatal(diagnosticFailure, failure);
                    if (adoption == null) {
                        quarantineInstallPublication(id, registration, diagnosticFailure);
                    } else {
                        // Adoption is single-target and the checked failure guarantees no commit.
                        try { interruptedInstallPublications.remove(id); }
                        catch (Throwable anchorFailure) {
                            if (TypeChecks.isFatal(anchorFailure) && !TypeChecks.isFatal(diagnosticFailure)) {
                                suppressPreservingFatal(anchorFailure, diagnosticFailure);
                                throw TypeChecks.fatal(anchorFailure);
                            }
                            suppressPreservingFatal(diagnosticFailure, anchorFailure);
                        }
                    }
                    throw TypeChecks.rethrow(diagnosticFailure);
                }
            } catch (Throwable failure) {
                if (adoption == null) quarantineInterruptedInstall(id, registration, failure, false);
                else retireAdoptionAfterFailure(registration, failure);
                throw TypeChecks.rethrow(failure);
            }
        }

        if (adoption != null && installed == 0) {
            // A checked failure is guaranteed uncommitted by the bridge contract.
            interruptedInstallPublications.remove(id);
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.FAILED,
                    backendId, generation, "adoption was not committed; old hook was not changed by this attempt", null, targetReceipts);
        }

        final boolean identityChangedAfterInstall;
        try {
            identityChangedAfterInstall = identityChanged(loaderBefore, epochBefore);
        } catch (Throwable failure) {
            if (adoption == null) quarantineInterruptedInstall(id, registration, failure, false);
            else retireAdoptionAfterFailure(registration, failure);
            throw TypeChecks.rethrow(failure);
        }
        if (identityChangedAfterInstall) {
            if (adoption != null) {
                registration.retireUnpublishedAdoption();
                return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.STALE_IDENTITY,
                        backendId, generation, "identity changed after adoption committed; old hook cannot be restored; "
                                + "new channel retired and handle retained; id quarantined", null, targetReceipts);
            }
            final CloseReport closeReport;
            try {
                closeReport = registration.close();
            } catch (Throwable failure) {
                quarantineInterruptedInstall(id, registration, failure, true);
                throw TypeChecks.rethrow(failure);
            }
            if (installed > 0 && closeReport.entryRestoration() != CloseReport.EntryRestoration.RESTORED) {
                // Keep ownership of the closed handles and forbid reuse of this id in this environment.
                // close() is idempotent, so this does not imply that uninstall can be retried.
                registrations.put(id, registration);
            }
            List<TargetInstallReceipt> rolledBack = new ArrayList<TargetInstallReceipt>(targetReceipts.size());
            for (TargetInstallReceipt targetReceipt : targetReceipts) {
                rolledBack.add(targetReceipt.status() == TargetInstallReceipt.Status.INSTALLED
                        ? TargetInstallReceipt.rolledBack(targetReceipt.target(),
                                "loader or code epoch changed before publication; aggregate close: " + closeReport)
                        : targetReceipt);
            }
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, generation, "loader or code epoch changed during install; " + closeReport, null, rolledBack);
        }
        if (installed == 0) {
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.FAILED,
                    backendId, generation, "no target could be installed", null, targetReceipts);
        }

        try {
            // Establish an independent anchor before building the receipt or touching registrations.
            // It survives both a write-before-throw and a throw-before-write from publication.
            interruptedInstallPublications.put(id, registration);
            RegistrationReceipt.Status status = installed == plans.size()
                    ? RegistrationReceipt.Status.INSTALLED : RegistrationReceipt.Status.PARTIALLY_INSTALLED;
            String message = (adoption == null ? "installed " : "adopted ") + installed + "/" + plans.size() + " targets"
                    + (status == RegistrationReceipt.Status.PARTIALLY_INSTALLED
                            ? "; successful entries remain registered; no cross-target atomicity" : "");
            RegistrationReceipt receipt = RegistrationReceipt.withTargets(id, status, backendId, generation,
                    message, registration, targetReceipts);
            registrations.put(id, registration);
            interruptedInstallPublications.remove(id);
            return receipt;
        } catch (Throwable failure) {
            if (adoption == null) quarantineInstallPublication(id, registration, failure);
            else retireAdoptionAfterFailure(registration, failure);
            throw TypeChecks.rethrow(failure);
        }
    }

    /** Keep a possibly adopted handle quarantined without replacing an earlier fatal failure. */
    private static void retireAdoptionAfterFailure(CoreRegistration registration, Throwable primary) {
        try {
            registration.retireUnpublishedAdoption();
        } catch (Throwable cleanupFailure) {
            if (TypeChecks.isFatal(cleanupFailure) && !TypeChecks.isFatal(primary)) {
                suppressPreservingFatal(cleanupFailure, primary);
                throw TypeChecks.fatal(cleanupFailure);
            }
            suppressPreservingFatal(primary, cleanupFailure);
        }
    }

    private boolean identityChanged(ClassLoader loader, long epoch) {
        return backend.context().classLoader() != loader || backend.context().codeEpoch() != epoch;
    }

    private void quarantineInstallPublication(HookId id, CoreRegistration registration, Throwable failure) {
        // Re-establish the anchor if an asynchronous failure arrived after its successful removal.
        // If anchoring or cleanup itself is interrupted by resource exhaustion, no absolute atomicity
        // is claimed; a fatal cleanup failure has priority over a nonfatal publication failure.
        try {
            interruptedInstallPublications.put(id, registration);
        } catch (Throwable anchorFailure) {
            if (TypeChecks.isFatal(anchorFailure) && !TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(anchorFailure, failure);
                failure = anchorFailure;
            } else {
                suppressPreservingFatal(failure, anchorFailure);
            }
        }
        try {
            CloseReport report = registration.close();
            if (!TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(failure, new IllegalStateException(
                        "install publication failed; bound-handle cleanup: " + report));
            }
        } catch (Throwable cleanupFailure) {
            if (TypeChecks.isFatal(cleanupFailure) && !TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(cleanupFailure, failure);
                throw TypeChecks.fatal(cleanupFailure);
            }
            suppressPreservingFatal(failure, cleanupFailure);
        }
        if (TypeChecks.isFatal(failure)) {
            throw TypeChecks.fatal(failure);
        }
    }

    private void quarantineInterruptedInstall(HookId id, CoreRegistration registration, Throwable failure,
                                               boolean cleanupAlreadyAttempted) {
        // The interrupted call may have installed an entry without returning a handle. Closing only
        // bound handles cannot prove that unknown effects were removed; never turn it into a receipt.
        registrations.put(id, registration);
        interruptedInstalls.add(id);
        if (cleanupAlreadyAttempted) {
            // close() is one-shot. A second call cannot retry an interrupted uninstall or prove
            // restoration, and may return no report. Keep the registration quarantined instead.
            if (!TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(failure, new IllegalStateException(
                        "install rollback was interrupted; backend effects remain unknown; "
                                + "cleanup report: " + registration.closeReport()));
            }
            return;
        }
        try {
            CloseReport cleanup = registration.close();
            if (!TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(failure, new IllegalStateException(
                        "install aborted; unreturned backend handles and their effects are unknown; "
                                + "bound-handle cleanup: " + cleanup));
            }
        } catch (Throwable cleanupFailure) {
            if (TypeChecks.isFatal(cleanupFailure) && !TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(cleanupFailure, failure);
                throw TypeChecks.fatal(cleanupFailure);
            }
            suppressPreservingFatal(failure, cleanupFailure);
        }
    }

    private static void suppressPreservingFatal(Throwable primary, Throwable secondary) {
        if (primary == secondary) {
            return;
        }
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable suppressionFailure) {
            if (!TypeChecks.isFatal(primary) && TypeChecks.isFatal(suppressionFailure)) {
                throw TypeChecks.fatal(suppressionFailure);
            }
            // Preserve a fatal primary even when attaching suppressed diagnostics is not possible.
        }
    }

    private static boolean retirementBlocksReuse(CoreRegistration registration) {
        RegistrationState state = registration.state();
        if (state == RegistrationState.INSTALLED) {
            return false;
        }
        CloseReport report = registration.closeReport();
        return report == null || report.entryRestoration() != CloseReport.EntryRestoration.RESTORED;
    }

    /**
     * Atomically replaces one complete single-target registration with the same logical id.
     * Owner, priority, resolved executable, loader and code epoch must match. MatchPolicy.ALL,
     * partial registrations and implicit close+install are deliberately not supported.
     */
    public synchronized RegistrationReceipt replace(HookSpec spec) {
        return guardedReplace(spec, false);
    }

    /**
     * Atomically replaces the existing single-target registration even when its full semantics
     * are identical. Unlike an idempotent {@link #replace(HookSpec)}, a successful call publishes
     * a new registration and retires the previous one, so closing the old registration cannot
     * unhook the new generation. All target, owner, priority, error-policy, loader, epoch and
     * backend-capability checks of {@code replace} still apply; this never falls back to a
     * non-atomic close and reinstall. Already admitted callbacks retain their original generation.
     * A failed or indeterminate backend replacement keeps the same contracts as {@code replace}.
     */
    public synchronized RegistrationReceipt forceReplace(HookSpec spec) {
        return guardedReplace(spec, true);
    }

    private RegistrationReceipt guardedReplace(HookSpec spec, boolean evenIfIdentical) {
        HookId id = checkMutation(spec);
        try {
            activeMutations.add(id);
            return replaceInternal(spec, evenIfIdentical);
        }
        finally { activeMutations.remove(id); }
    }

    private RegistrationReceipt replaceInternal(HookSpec spec, boolean evenIfIdentical) {
        Objects.requireNonNull(spec, "spec");
        HookId id = spec.id();
        String backendId = backend.id();
        if (closed) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, -1L, "environment is closed");
        }
        if (closeRequested.get()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, -1L, "environment close is requested");
        }
        CoreRegistration interruptedPublication = interruptedInstallPublications.get(id);
        if (interruptedPublication != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedPublication.generation(), "install publication is indeterminate; id is quarantined");
        }
        CoreRegistration interruptedReplacement = interruptedReplacements.get(id);
        if (interruptedReplacement != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedReplacement.generation(), "replacement publication is indeterminate; id is quarantined");
        }
        CapabilityResult replacementCapability = backend.capabilities().lookup(Capability.ATOMIC_REPLACE);
        if (!replacementCapability.isSupportedInDeclaredCell()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "ATOMIC_REPLACE is not supported: " + replacementCapability);
        }
        if (!spec.processFilter().matches(backend.context().processName())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FILTERED,
                    backendId, -1L, "process " + backend.context().processName() + " pruned before replacement");
        }
        for (Capability capability : spec.requiredCapabilities()) {
            CapabilityResult result = backend.capabilities().lookup(capability);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "required " + capability + ": " + result);
            }
        }
        if (spec.errorPolicy() == ErrorPolicy.FRAMEWORK_PROTECTIVE) {
            CapabilityResult result = backend.capabilities().lookup(Capability.FRAMEWORK_PROTECTIVE);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "framework protective policy is not supported: " + result);
            }
        }
        if (backend.capabilities().chainSemantics() != ChainSemantics.FRAMEWORK_CHAIN) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "replacement requires a framework-chain backend");
        }

        CoreRegistration existing = registrations.get(id);
        if (existing == null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, -1L, "no installed registration with this logical id");
        }
        if (existing.replacementOutcomeUnknown()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, existing.generation(), "previous replacement outcome is unknown; id is quarantined");
        }
        if (existing.state() == RegistrationState.RETIRING) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, existing.generation(), "previous generation is still retiring");
        }
        if (existing.state() != RegistrationState.INSTALLED || !existing.isComplete() || existing.plans().size() != 1) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "only a complete single-target registration can be replaced");
        }
        BoundPlan previous = existing.plan();
        if (previous.spec().target().matchPolicy() == MatchPolicy.ALL || spec.target().matchPolicy() == MatchPolicy.ALL) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "MatchPolicy.ALL is not an atomic single-target replacement");
        }
        if (!previous.spec().owner().equals(spec.owner()) || previous.spec().priority() != spec.priority()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "owner and priority must be preserved by replacement");
        }
        if (previous.spec().errorPolicy() != spec.errorPolicy()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "error policy must be preserved by replacement");
        }
        if (previous.loader() != backend.context().classLoader()
                || previous.codeEpoch() != backend.context().codeEpoch()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, existing.generation(), "loader or code epoch changed before replacement");
        }

        final List<Executable> candidates;
        if (spec.target() instanceof TargetSpec.MemberTarget) {
            candidates = Collections.singletonList(((TargetSpec.MemberTarget) spec.target()).executable());
        } else {
            try {
                candidates = resolver.resolve((TargetSpec.DescriptorTarget) spec.target(), previous.loader());
            } catch (BackendInstallException failure) {
                if (identityChanged(previous.loader(), previous.codeEpoch())) {
                    return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                            backendId, existing.generation(), "loader or code epoch changed during resolution");
                }
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED,
                        backendId, existing.generation(), "target resolution failed: " + failure.getMessage());
            }
        }
        if (identityChanged(previous.loader(), previous.codeEpoch())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, existing.generation(), "loader or code epoch changed during resolution");
        }
        if (candidates.isEmpty()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TARGET_MISSING,
                    backendId, existing.generation(), "no target matched " + spec.target());
        }
        if (candidates.size() > 1 && spec.target().matchPolicy() == MatchPolicy.UNIQUE) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.AMBIGUOUS_TARGET,
                    backendId, existing.generation(), candidates.size() + " targets matched under UNIQUE");
        }
        Executable target = candidates.get(0);
        if (!previous.target().equals(target)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "replacement must keep the resolved executable");
        }
        String invalidConstant = invalidConstantReplacement(spec, Collections.singletonList(target));
        if (spec.stringArgumentCondition() != null && identityChanged(previous.loader(), previous.codeEpoch())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, existing.generation(), "loader or code epoch changed during conditional validation");
        }
        if (invalidConstant != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED,
                    backendId, existing.generation(), invalidConstant);
        }
        if (!evenIfIdentical && previous.spec().hasSameSemantics(spec)) {
            // Resolver/context callbacks can close the old registration reentrantly. The volatile
            // state read is the linearization point and does not acquire the registration monitor.
            if (existing.state() != RegistrationState.INSTALLED) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                        backendId, existing.generation(), "registration closed during replacement resolution");
            }
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.ALREADY_INSTALLED,
                    backendId, existing.generation(), "identical replacement registration already installed");
        }

        long generation = generations.getAndIncrement();
        BoundPlan plan = new BoundPlan(spec, target, generation, previous.loader(), previous.codeEpoch());
        CoreRegistration registration = new CoreRegistration(plan, diagnostics);
        // Keep direct old.close() serialized through publication, not only through the backend swap.
        synchronized (existing) {
            if (!existing.prepareReplacement(registration)) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                        backendId, existing.generation(), "previous registration is no longer installed");
            }
            // This guard is recorded before the backend may commit and remains on unknown outcomes.
            try {
                interruptedReplacements.put(id, existing);
            } catch (Throwable failure) {
                // The backend has not been invoked. A possibly written anchor remains conservative,
                // but the unused replacement must not be kept through pendingReplacement.
                existing.finishPreparedReplacement();
                throw TypeChecks.rethrow(failure);
            }
            try {
                existing.replaceWith(plan, registration);
                RegistrationReceipt receipt = RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.INSTALLED,
                        backendId, generation, "atomically replaced 1 target", registration,
                        Collections.singletonList(TargetInstallReceipt.installed(target)));
                registrations.put(id, registration);
                interruptedReplacements.remove(id);
                existing.finishPreparedReplacement();
                return receipt;
            } catch (BackendInstallException failure) {
                if (existing.replacementOutcomeUnknown() || existing.state() != RegistrationState.INSTALLED) {
                    // A failure after the backend commit cannot claim the old handle is valid.
                    existing.retireInterruptedPublication(registration, failure);
                    throw TypeChecks.rethrow(failure);
                }
                // This checked failure guarantees that the backend did not commit the replacement.
                try {
                    registration.close();
                } catch (Throwable cleanupFailure) {
                    // The new registration has no returned handle. Keep the old generation valid
                    // and clear the pre-commit guard, even if local cleanup itself fails.
                    suppressPreservingFatal(cleanupFailure, failure);
                    try {
                        existing.finishPreparedReplacement();
                        interruptedReplacements.remove(id);
                    } catch (Throwable anchorFailure) {
                        if (TypeChecks.isFatal(anchorFailure) && !TypeChecks.isFatal(cleanupFailure)) {
                            suppressPreservingFatal(anchorFailure, cleanupFailure);
                            throw TypeChecks.fatal(anchorFailure);
                        }
                        suppressPreservingFatal(cleanupFailure, anchorFailure);
                    }
                    throw TypeChecks.rethrow(cleanupFailure);
                }
                existing.finishPreparedReplacement();
                interruptedReplacements.remove(id);
                return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.FAILED,
                        backendId, existing.generation(), "backend replacement failed: " + failure.getMessage(), null,
                        Collections.singletonList(TargetInstallReceipt.failed(target, failure.getMessage())));
            } catch (Throwable failure) {
                existing.retireInterruptedPublication(registration, failure);
                throw TypeChecks.rethrow(failure);
            }
        }
    }

    private static boolean sameTargets(CoreRegistration registration, List<Executable> targets) {
        if (registration.plans().size() != targets.size()) {
            return false;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (!registration.plans().get(i).target().equals(targets.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String invalidConstantReplacement(HookSpec spec, List<Executable> targets) {
        StringArgumentCondition condition = spec.stringArgumentCondition();
        if (!spec.hasConstantReplacement() && !spec.hasSuccessReplacement() && condition == null) return null;
        for (Executable target : targets) {
            if (condition != null) {
                final Class<?>[] parameters;
                try {
                    parameters = target.getParameterTypes();
                } catch (SecurityException | LinkageError failure) {
                    return "cannot inspect conditional replacement parameter types: " + failure;
                }
                int index = condition.argumentIndex();
                if (index >= parameters.length || parameters[index] != String.class) {
                    return "string argument replacement requires a String parameter at index " + index + ": " + target;
                }
            }
            try {
                Object result = condition != null ? condition.result()
                        : spec.hasSuccessReplacement() ? spec.successResult() : spec.constantResult();
                TypeChecks.checkResultValue(target, result);
            } catch (IllegalArgumentException invalid) {
                return "invalid constant replacement: " + invalid.getMessage();
            } catch (SecurityException | LinkageError failure) {
                if (condition == null) throw failure;
                return "cannot inspect conditional replacement return type: " + failure;
            }
        }
        return null;
    }

    /**
     * Request shutdown without waiting for the environment or registration monitors. This only
     * closes the gate for subsequent operations: it never invokes the backend, stops installed
     * callbacks, schedules work or waits for already admitted operations. A caller-owned worker
     * must later invoke {@link #close()} to perform cleanup. Repeated requests are harmless.
     */
    public void requestClose() { closeRequested.set(true); }

    /** True after a shutdown request or after an ordinary close has been claimed, not proof of cleanup. */
    public boolean isCloseRequested() { return closeRequested.get(); }

    public synchronized int activeRegistrationCount() {
        return registrations.size();
    }

    /**
     * Marks the environment closed under its monitor, then uninstalls outside that monitor.
     * Calls reentering while already holding the monitor retain the legacy synchronous path.
     * An interrupt already present at entry or observed after a registration close is restored
     * on exit, even if a later backend clears it. Backend code can still consume an interrupt
     * entirely within one call before the environment observes it.
     */
    @Override
    public void close() {
        if (Thread.holdsLock(this)) {
            closeUnderMonitor();
            return;
        }
        Map<HookId, CoreRegistration> snapshot;
        Thread current = Thread.currentThread();
        boolean interrupted = current.isInterrupted();
        boolean ownsClose = false;
        try {
            synchronized (this) {
                if (closed) {
                    awaitCloseCompletion();
                    return;
                }
                if (!activeMutations.isEmpty() || anyClosingOn(current)) {
                    throw new IllegalStateException("cannot close environment during a reentrant mutation or registration close");
                }
                snapshot = new LinkedHashMap<HookId, CoreRegistration>(registrations);
                // The ownership claim is protected by the outer finally even if publication fails.
                ownsClose = true;
                closeOwner = current;
                closeRequested.set(true);
                closed = true;
            }
            List<Throwable> failures = new ArrayList<Throwable>();
            List<HookId> restored = new ArrayList<HookId>();
            Error firstError = null;
            for (Map.Entry<HookId, CoreRegistration> entry : snapshot.entrySet()) {
                try {
                    CloseReport report = entry.getValue().close();
                    if (report != null && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED) {
                        restored.add(entry.getKey());
                    }
                } catch (RuntimeException failure) {
                    failures.add(failure);
                } catch (Error failure) {
                    if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                    failures.add(failure);
                    if (firstError == null) firstError = failure;
                } finally {
                    if (current.isInterrupted()) interrupted = true;
                }
            }
            synchronized (this) {
                for (HookId id : restored) registrations.remove(id);
                if (registrations.isEmpty()) interruptedInstalls.clear();
            }
            if (!failures.isEmpty()) {
                Throwable primary = firstError == null ? failures.get(0) : firstError;
                for (Throwable failure : failures) suppressPreservingFatal(primary, failure);
                if (firstError != null) throw firstError;
                throw (RuntimeException) primary;
            }
        } finally {
            try {
                if (ownsClose) {
                    synchronized (this) {
                        if (closeOwner == current) {
                            closeOwner = null;
                            notifyAll();
                        }
                    }
                }
            } finally {
                if (interrupted) current.interrupt();
            }
        }
    }

    /** Called under this environment's monitor; reads only volatile owner fields. */
    private boolean anyClosingOn(Thread thread) {
        for (CoreRegistration registration : registrations.values()) {
            if (registration.closingOn(thread)) return true;
        }
        return false;
    }

    /** Called under this monitor; wait releases all reentrant holds until completion. */
    private void awaitCloseCompletion() {
        Thread current = Thread.currentThread();
        // An uninstall owner must not await an environment close waiting for that registration.
        if (closeOwner == current || anyClosingOn(current)) return;
        boolean interrupted = false;
        while (closeOwner != null) {
            try { wait(); }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) current.interrupt();
    }

    /** Legacy reentrant close; the caller already owns this monitor. */
    private void closeUnderMonitor() {
        Thread current = Thread.currentThread();
        boolean interrupted = current.isInterrupted();
        try {
            if (closed) {
                awaitCloseCompletion();
                return;
            }
            if (!activeMutations.isEmpty() || anyClosingOn(current)) {
                throw new IllegalStateException("cannot close environment during a reentrant install, adoption or replacement");
            }
            closeRequested.set(true);
            closed = true;
            List<Throwable> failures = new ArrayList<Throwable>();
            List<HookId> restored = new ArrayList<HookId>();
            Error firstError = null;
            for (Map.Entry<HookId, CoreRegistration> entry : registrations.entrySet()) {
                try {
                    CloseReport report = entry.getValue().close();
                    if (report != null && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED) {
                        restored.add(entry.getKey());
                    }
                } catch (RuntimeException failure) {
                    failures.add(failure);
                } catch (Error failure) {
                    if (TypeChecks.isFatal(failure)) {
                        throw TypeChecks.fatal(failure);
                    }
                    failures.add(failure);
                    if (firstError == null) {
                        firstError = failure;
                    }
                } finally {
                    if (current.isInterrupted()) interrupted = true;
                }
            }
            for (HookId id : restored) {
                registrations.remove(id);
            }
            if (!failures.isEmpty()) {
                Throwable primary = firstError == null ? failures.get(0) : firstError;
                for (Throwable failure : failures) {
                    suppressPreservingFatal(primary, failure);
                }
                // Retain the registrations and handles when a close did not finish cleanly. close()
                // remains one-shot; it does not silently retry an uncertain backend uninstall.
                if (firstError != null) {
                    throw firstError;
                }
                throw (RuntimeException) primary;
            }
            if (registrations.isEmpty()) {
                interruptedInstalls.clear();
            }
            // Retain any quarantined replacement handles while this environment remains reachable.
        } finally {
            if (interrupted) current.interrupt();
        }
    }

}
