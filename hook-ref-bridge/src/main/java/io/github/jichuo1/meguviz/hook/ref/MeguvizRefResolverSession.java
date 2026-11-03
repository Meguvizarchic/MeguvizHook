package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit, optional reuse of one newly owned MeguvizRef scope for one backend generation.
 * No global cache or callback-path work is introduced. The supplied context must report its
 * actual loader and a reliable monotonically changing code epoch without ABA reuse; a getter
 * is evidence supplied by the caller, not independent authentication of the runtime.
 *
 * <p>Each resolve obtains a lease before class loading, class initialization, query factories or
 * providers can run. Close rejects later leases without waiting for admitted work, but synchronous
 * scope cleanup may block. Already admitted work may run after close returns if preempted. A result
 * is accepted only if retirement has not won the lock before its completion linearization point.
 * Any observed identity change, scope mutation or unavailable identity permanently retires this
 * session. HookEnvironment still independently checks identity after the resolver returns: the
 * session cannot make the interval between its return and installation atomic.
 *
 * <p>The factory transfers a fresh scope and its providers to this session. Do not retain, close,
 * advance or otherwise mutate that scope externally. No monitor is held while class loading,
 * factories, providers or scope cleanup run. Raw reflective members intentionally escape into
 * the Hook SDK and may retain a loader after this session closes.
 */
public final class MeguvizRefResolverSession implements MemberResolver, AutoCloseable {
    public enum State { OPEN, DRAINING, CLOSED, FAILED }

    /** Default behavior is fail-closed; waiter isolation is explicit and capability-gated. */
    public enum WaiterInterruptionPolicy { RETIRE_SESSION, ISOLATE_VERIFIED_WAITER }

    /** A point-in-time session snapshot, not proof of physical class-loader release. */
    public static final class CloseReport {
        private final State state;
        private final int inFlight;
        private final String reason;
        private final Throwable failure;
        private CloseReport(State state, int inFlight, String reason, Throwable failure) {
            this.state = state;
            this.inFlight = inFlight;
            this.reason = reason;
            this.failure = failure;
        }
        public State state() { return state; }
        public int inFlight() { return inFlight; }
        public String reason() { return reason; }
        public Throwable failure() { return failure; }
    }

    private static class Lease implements MeguvizRefMemberResolver.Checkpoint {
        final MeguvizRefResolverSession session;
        final BackendContext context;
        final ClassLoader loader;
        final CodeScope scope;
        final MeguvizRefMemberResolver delegate;
        boolean released;
        Lease(MeguvizRefResolverSession session, BackendContext context, ClassLoader loader,
              CodeScope scope, MeguvizRefMemberResolver delegate) {
            this.session = session; this.context = context; this.loader = loader; this.scope = scope; this.delegate = delegate;
        }
        @Override public void check() throws BackendInstallException { session.checkCurrent(this); }
    }

    /** Extra per-resolution state exists only for the explicit isolation policy. */
    private static final class IsolatingLease extends Lease {
        BackendInstallException verifiedWaiterFailure;
        boolean verifiedWaiterInterrupt;
        IsolatingLease(MeguvizRefResolverSession session, BackendContext context, ClassLoader loader,
                       CodeScope scope, MeguvizRefMemberResolver delegate) {
            super(session, context, loader, scope, delegate);
        }
        @Override public void checkResult(ResolutionResult result) throws BackendInstallException {
            // This is the actual scope.resolve result, sampled before any context callback.
            boolean interrupted = Thread.currentThread().isInterrupted();
            boolean waiter = interrupted && session.waiterEvidence != null && session.waiterEvidence.isWaiter(result);
            if (!waiter) { check(); return; }
            verifiedWaiterInterrupt = true;
            try {
                check();
                BackendInstallException failure = MeguvizRefMemberResolver.incomplete(result);
                verifiedWaiterFailure = failure;
                throw failure;
            } finally {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Optional capability probe. Unknown or unavailable APIs keep fail-closed behavior. */
    private static final class WaiterEvidence {
        final Method method;
        final Object waiter;
        private WaiterEvidence(Method method, Object waiter) { this.method = method; this.waiter = waiter; }
        static WaiterEvidence probe() {
            try {
                Method method = ResolutionReceipt.class.getMethod("interruptionOrigin");
                Class<?> type = method.getReturnType();
                if (!type.isEnum() || !type.getName().equals(
                        "io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt$InterruptionOrigin")) return null;
                Object[] constants = type.getEnumConstants();
                if (constants == null) return null;
                for (Object constant : constants) {
                    if (constant instanceof Enum<?> && "SINGLE_FLIGHT_WAITER".equals(((Enum<?>) constant).name())) {
                        return new WaiterEvidence(method, constant);
                    }
                }
                return null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
                return null;
            }
        }
        boolean isWaiter(ResolutionResult result) {
            if (result.status() != ResolutionStatus.CANCELLED) return false;
            try { return method.invoke(result.receipt()) == waiter; }
            catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (isFatal(cause)) throw (Error) cause;
                return false;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return false;
            }
        }
    }

    private final Object lock = new Object();
    private final ThreadLocal<Boolean> resolving = new ThreadLocal<Boolean>();
    private final boolean isolateWaiterCancellation;
    private final WaiterEvidence waiterEvidence;
    private MeguvizRefMemberResolver delegate;
    private final long expectedEpoch;
    private final long scopeEpoch;
    private final String scopeIdentity;
    private BackendContext context;
    private ClassLoader loader;
    private CodeScope scope;
    private State state = State.OPEN;
    private int active;
    private boolean closing;
    private String reason = "open";
    private Throwable closeFailure;

    /** Uses declared-member matching and a fresh scope. */
    public static MeguvizRefResolverSession open(BackendContext context) throws BackendInstallException {
        return create(context, CodeScope::open, new MeguvizRefMemberResolver());
    }

    /** Opens with an explicit interruption policy; unsupported evidence remains fail-closed. */
    public static MeguvizRefResolverSession open(BackendContext context, WaiterInterruptionPolicy policy)
            throws BackendInstallException {
        return create(context, CodeScope::open, new MeguvizRefMemberResolver(), isolate(policy));
    }

    /** Explicitly enables the chosen reflection hierarchy; inherited hooks affect the declaring class. */
    public static MeguvizRefResolverSession withHierarchy(BackendContext context, Hierarchy hierarchy)
            throws BackendInstallException {
        return create(context, CodeScope::open, MeguvizRefMemberResolver.withHierarchy(hierarchy));
    }

    /** Opens with a selected hierarchy and interruption policy. */
    public static MeguvizRefResolverSession withHierarchy(BackendContext context, Hierarchy hierarchy,
            WaiterInterruptionPolicy policy) throws BackendInstallException {
        return create(context, CodeScope::open, MeguvizRefMemberResolver.withHierarchy(hierarchy), isolate(policy));
    }

    /** Transfer a fresh configured scope to a session using the default descriptor filters. */
    public static MeguvizRefResolverSession withScope(BackendContext context,
            MeguvizRefMemberResolver.ScopeFactory scopes, Hierarchy hierarchy) throws BackendInstallException {
        return create(context, scopes, MeguvizRefMemberResolver.withScope(scopes, hierarchy));
    }

    /** Same configured-scope descriptor matching with an explicit interruption policy. */
    public static MeguvizRefResolverSession withScope(BackendContext context,
            MeguvizRefMemberResolver.ScopeFactory scopes, Hierarchy hierarchy, WaiterInterruptionPolicy policy)
            throws BackendInstallException {
        return create(context, scopes, MeguvizRefMemberResolver.withScope(scopes, hierarchy), isolate(policy));
    }

    /** Creates a session with a fresh default scope and an explicit structural query factory. */
    public static MeguvizRefResolverSession withQueries(BackendContext context,
            MeguvizRefMemberResolver.QueryFactory queries) throws BackendInstallException {
        return withQueries(context, CodeScope::open, queries);
    }

    /** Uses a fresh default scope with an explicit query factory and interruption policy. */
    public static MeguvizRefResolverSession withQueriesAndWaiterPolicy(BackendContext context,
            MeguvizRefMemberResolver.QueryFactory queries, WaiterInterruptionPolicy policy)
            throws BackendInstallException {
        return withQueries(context, CodeScope::open, queries, policy);
    }

    /** Creates a session whose fresh scope and query factory may provide structural/provider queries. */
    public static MeguvizRefResolverSession withQueries(BackendContext context,
            MeguvizRefMemberResolver.ScopeFactory scopes, MeguvizRefMemberResolver.QueryFactory queries)
            throws BackendInstallException {
        Objects.requireNonNull(scopes, "scopes");
        return create(context, scopes, MeguvizRefMemberResolver.withQueries(scopes, queries));
    }

    /** Explicit policy; Ref 1.0 and missing evidence retain fail-closed behavior. */
    public static MeguvizRefResolverSession withQueries(BackendContext context,
            MeguvizRefMemberResolver.ScopeFactory scopes, MeguvizRefMemberResolver.QueryFactory queries,
            WaiterInterruptionPolicy policy) throws BackendInstallException {
        Objects.requireNonNull(scopes, "scopes");
        return create(context, scopes, MeguvizRefMemberResolver.withQueries(scopes, queries), isolate(policy));
    }

    private static boolean isolate(WaiterInterruptionPolicy policy) {
        return Objects.requireNonNull(policy, "policy") == WaiterInterruptionPolicy.ISOLATE_VERIFIED_WAITER;
    }

    /**
     * Whether the opt-in session detected the expected reflection API shape at creation. False
     * also means the default policy did not probe. This does not authenticate a Ref fork, establish
     * future call success, or prove that a particular interruption was a verified waiter.
     */
    public boolean isWaiterInterruptionEvidenceAvailable() {
        return waiterEvidence != null;
    }

    private static MeguvizRefResolverSession create(BackendContext context,
            MeguvizRefMemberResolver.ScopeFactory factory, MeguvizRefMemberResolver delegate)
            throws BackendInstallException {
        return create(context, factory, delegate, false);
    }

    private static MeguvizRefResolverSession create(BackendContext context,
            MeguvizRefMemberResolver.ScopeFactory factory, MeguvizRefMemberResolver delegate,
            boolean isolateWaiterCancellation) throws BackendInstallException {
        Objects.requireNonNull(context, "context");
        CodeScope opened = null;
        try {
            long epoch = context.codeEpoch();
            ClassLoader loader = context.classLoader();
            opened = Objects.requireNonNull(factory.open(loader), "scope");
            Map<String, Long> diagnostics = opened.diagnostics();
            if (context.classLoader() != loader || context.codeEpoch() != epoch || !opened.usesLoader(loader)
                    || opened.state() != CodeScope.State.OPEN || opened.codeEpoch() != 0
                    || !Long.valueOf(0L).equals(diagnostics.get("lookups"))
                    || !Long.valueOf(0L).equals(diagnostics.get("cachedQueries"))
                    || !Long.valueOf(0L).equals(diagnostics.get("bindings"))) {
                throw new BackendInstallException("session requires a fresh scope and stable loader/epoch");
            }
            return new MeguvizRefResolverSession(context, loader, epoch, opened, delegate,
                    isolateWaiterCancellation, isolateWaiterCancellation ? WaiterEvidence.probe() : null);
        } catch (BackendInstallException failure) {
            closeRejected(opened, failure);
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            BackendInstallException wrapped = new BackendInstallException("cannot create MeguvizRef session", failure);
            closeRejected(opened, wrapped);
            throw wrapped;
        } catch (Error failure) {
            closeRejected(opened, failure);
            throw failure;
        } catch (Throwable failure) {
            closeRejected(opened, failure);
            throw rethrow(failure);
        }
    }

    private MeguvizRefResolverSession(BackendContext context, ClassLoader loader, long expectedEpoch,
                                      CodeScope scope, MeguvizRefMemberResolver delegate,
                                      boolean isolateWaiterCancellation, WaiterEvidence waiterEvidence) {
        this.context = context; this.loader = loader; this.expectedEpoch = expectedEpoch;
        this.scope = scope; this.scopeEpoch = scope.codeEpoch(); this.scopeIdentity = scope.identity();
        this.delegate = delegate;
        this.isolateWaiterCancellation = isolateWaiterCancellation;
        this.waiterEvidence = waiterEvidence;
    }

    @Override public List<Executable> resolve(TargetSpec.DescriptorTarget target, ClassLoader requestedLoader)
            throws BackendInstallException {
        Objects.requireNonNull(target, "target");
        if (Boolean.TRUE.equals(resolving.get())) {
            retire("recursive session resolution");
            throw new BackendInstallException("recursive session resolution");
        }
        resolving.set(Boolean.TRUE);
        Lease lease = null;
        Throwable failure = null;
        try {
            lease = acquire(requestedLoader);
            Lease current = lease;
            checkCurrent(current);
            List<Executable> result = current.delegate.resolveInScope(target, requestedLoader, current.scope, current);
            checkCurrent(current);
            complete(current);
            return result;
        } catch (Throwable error) {
            failure = error;
            if (lease instanceof IsolatingLease && error == ((IsolatingLease) lease).verifiedWaiterFailure) {
                try {
                    checkCurrent(lease);
                } catch (Throwable checkFailure) {
                    failure = checkFailure;
                    try { addSuppressedBestEffort(checkFailure, error); }
                    catch (Throwable fatal) { failure = fatal; throw rethrow(fatal); }
                    throw rethrow(checkFailure);
                }
                synchronized (lock) {
                    if (state == State.OPEN) throw rethrow(error);
                }
            }
            // Provider/query failures may follow side effects; do not reuse an uncertain scope.
            try {
                retire("resolution failed");
            } catch (Error cleanupFatal) {
                if (isFatal(error)) addSuppressed(error, cleanupFatal);
                else { addSuppressed(cleanupFatal, error); throw cleanupFatal; }
            }
            throw rethrow(error);
        } finally {
            try {
                if (lease != null && !lease.released) release(failure);
            } finally {
                try { resolving.remove(); }
                finally {
                    if (lease instanceof IsolatingLease && ((IsolatingLease) lease).verifiedWaiterInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private Lease acquire(ClassLoader requestedLoader) throws BackendInstallException {
        synchronized (lock) {
            if (state != State.OPEN) throw new BackendInstallException("MeguvizRef session is retired: " + reason);
            if (requestedLoader == loader) {
                if (active == Integer.MAX_VALUE) throw new BackendInstallException("session lease capacity exceeded");
                // Allocation can fail before a lease is returned; do not count it until it exists.
                Lease lease = isolateWaiterCancellation
                        ? new IsolatingLease(this, context, loader, scope, delegate)
                        : new Lease(this, context, loader, scope, delegate);
                active++;
                return lease;
            }
            state = State.DRAINING;
            reason = "requested loader mismatch";
        }
        drain();
        throw new BackendInstallException("MeguvizRef session loader mismatch");
    }

    private void checkCurrent(Lease lease) throws BackendInstallException {
        synchronized (lock) {
            if (state != State.OPEN) throw new BackendInstallException("MeguvizRef session is retired: " + reason);
        }
        try {
            if (lease.context.classLoader() != lease.loader || lease.context.codeEpoch() != expectedEpoch
                    || !lease.scope.usesLoader(lease.loader) || lease.scope.codeEpoch() != scopeEpoch
                    || !scopeIdentity.equals(lease.scope.identity())) {
                retire("loader, epoch or scope identity changed");
                throw new BackendInstallException("MeguvizRef session identity changed");
            }
        } catch (RuntimeException | LinkageError error) {
            retire("identity check failed");
            throw new BackendInstallException("MeguvizRef session identity check failed", error);
        } catch (Error fatal) {
            retire("identity check failed");
            throw fatal;
        } catch (Throwable failure) {
            retire("identity check failed");
            throw rethrow(failure);
        }
        synchronized (lock) {
            if (state != State.OPEN) throw new BackendInstallException("MeguvizRef session is retired: " + reason);
        }
    }

    private void retire(String why) {
        synchronized (lock) {
            if (state == State.OPEN) { state = State.DRAINING; reason = why; }
        }
        drain();
    }

    /** Reject new work without waiting for admitted work; synchronous scope cleanup may block. */
    public CloseReport requestClose() {
        retire("closed by caller");
        return report();
    }

    /** Snapshot current retirement and provider-cleanup outcome. */
    public CloseReport report() {
        synchronized (lock) { return new CloseReport(state, active, reason, closeFailure); }
    }

    /** Does not wait for admitted work; synchronous scope cleanup may block. Inspect {@link #report()} later. */
    @Override public void close() {
        CloseReport report = requestClose();
        if (report.state() == State.FAILED) throw new IllegalStateException("MeguvizRef session cleanup failed", report.failure());
    }

    // This lock acquisition is the successful resolution's linearization point. A close which
    // wins it first makes the result fail; after it, close may proceed before resolve returns.
    private void complete(Lease lease) throws BackendInstallException {
        synchronized (lock) {
            if (state != State.OPEN) throw new BackendInstallException("MeguvizRef session is retired: " + reason);
            if (active < 1 || lease.released) throw new IllegalStateException("session lease underflow");
            active--;
            lease.released = true;
        }
    }

    private void release(Throwable primary) {
        synchronized (lock) {
            if (active < 1) throw new IllegalStateException("session lease underflow");
            active--;
        }
        try {
            drain();
        } catch (Error fatal) {
            if (primary == null) throw fatal;
            if (isFatal(primary)) { addSuppressed(primary, fatal); return; }
            addSuppressed(fatal, primary);
            throw fatal;
        }
    }

    private void drain() {
        CodeScope closingScope;
        synchronized (lock) {
            if (state != State.DRAINING || active != 0 || closing) return;
            closing = true;
            closingScope = scope;
        }
        Throwable failure = null;
        try {
            closingScope.close();
            if (closingScope.closeFailureType() != null) {
                failure = new IllegalStateException("scope reported cleanup failure: " + closingScope.closeFailureType());
            } else if (closingScope.state() != CodeScope.State.CLOSED) {
                // A dependency-side in-flight failure can prevent the owned scope from draining.
                failure = new IllegalStateException("scope did not finish cleanup: " + closingScope.state());
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            synchronized (lock) {
                closeFailure = failure;
                state = failure == null ? State.CLOSED : State.FAILED;
                if (failure == null) { scope = null; context = null; loader = null; delegate = null; }
                closing = false;
            }
        }
        if (failure instanceof Error && isFatal(failure)) throw (Error) failure;
    }

    private static void closeRejected(CodeScope scope, Throwable primary) {
        if (scope == null) return;
        try { scope.close(); }
        catch (Throwable failure) {
            if (isFatal(failure) && !isFatal(primary)) { addSuppressed(failure, primary); throw (Error) failure; }
            addSuppressed(primary, failure);
        }
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary != secondary) primary.addSuppressed(secondary);
    }

    private static void addSuppressedBestEffort(Throwable primary, Throwable secondary) {
        if (primary == secondary) return;
        try { primary.addSuppressed(secondary); }
        catch (Throwable failure) {
            if (isFatal(failure) && !isFatal(primary)) throw rethrow(failure);
        }
    }

    // JVM callbacks may throw checked exceptions absent from their Java declarations.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
