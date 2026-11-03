package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.StringArgumentCondition;
import io.github.jichuo1.meguviz.hook.api.MatchObserver;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One logical registration and its immutable per-target plans. Every backend entry gets a channel
 * bound to its own plan, while close state and in-flight accounting are shared by the whole logical
 * registration. Closing never replays a downstream call and never waits for arbitrary host code.
 */
final class CoreRegistration implements HookRegistration {
    private final List<BoundPlan> plans;
    private final DiagnosticsSink diagnostics;
    private final Map<BoundPlan, BackendHandle> handles = new LinkedHashMap<BoundPlan, BackendHandle>();
    private final InFlightCounter inFlight = new InFlightCounter();
    private final AtomicBoolean closeOnce = new AtomicBoolean();
    private volatile RegistrationState state = RegistrationState.INSTALLED;
    private volatile CloseReport lastCloseReport;
    // When replacement outcome is unknown, retain both plans/channels and any returned handle.
    private volatile CoreRegistration unknownReplacement;
    private CoreRegistration pendingReplacement;
    // Same-thread monitor reentry must not unhook the old handle inside backend.replace().
    private boolean replacingBackend;
    private boolean closingInProgress;
    private volatile Thread closeOwner;
    private BackendHandle uncertainOldHandle;
    private BackendHandle pendingReplacementHandle;
    // An adoption may have committed before publication; retain ownership even if binding fails.
    private BackendHandle pendingAdoptionHandle;

    CoreRegistration(BoundPlan plan, DiagnosticsSink diagnostics) {
        this(Collections.singletonList(plan), diagnostics);
    }

    CoreRegistration(List<BoundPlan> plans, DiagnosticsSink diagnostics) {
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("at least one bound plan is required");
        }
        this.plans = Collections.unmodifiableList(new ArrayList<BoundPlan>(plans));
        this.diagnostics = diagnostics;
    }

    DispatchChannel channel(BoundPlan plan, int index) {
        if (index < 0 || index >= plans.size() || plans.get(index) != plan) {
            throw new IllegalArgumentException("plan does not belong to this registration");
        }
        if (plan.stringArgumentCondition() != null) return new ConditionalPlanChannel(plan);
        if (plan.spec().hasSuccessReplacement()) return new SuccessResultPlanChannel(plan.spec().successResult());
        DispatchResult constant = plan.constantResult();
        if (constant != null) return new ConstantPlanChannel(constant);
        AfterCallback singleAfter = plan.singleAfterOnly();
        return singleAfter == null ? new PlanChannel(plan) : new SingleAfterPlanChannel(plan, singleAfter);
    }

    void bind(BoundPlan plan, BackendHandle backendHandle) {
        handles.put(plan, backendHandle);
    }

    void bindAdopted(BoundPlan plan, BackendHandle backendHandle) {
        pendingAdoptionHandle = backendHandle;
        bind(plan, backendHandle);
        pendingAdoptionHandle = null;
    }

    /** Quarantine without unhooking: old-generation restoration is no longer possible to promise. */
    synchronized void retireUnpublishedAdoption() {
        state = RegistrationState.RETIRED_RESIDENT;
        closeOnce.set(true);
        lastCloseReport = new CloseReport(state, true, CloseReport.EntryRestoration.UNKNOWN,
                inFlight.sum(), CloseReport.PhysicalRelease.UNKNOWN,
                "adoption committed or indeterminate; new channel retired, handles retained, no unhook attempted");
    }

    /** Called under this monitor before invoking the backend, while the old handle is still known. */
    boolean prepareReplacement(CoreRegistration replacement) {
        if (state != RegistrationState.INSTALLED) return false;
        BackendHandle oldHandle = handles.get(plan());
        if (oldHandle == null) return false;
        pendingReplacement = replacement;
        uncertainOldHandle = oldHandle;
        return true;
    }

    /** Called under this monitor only after publication, or after a guaranteed uncommitted failure. */
    void finishPreparedReplacement() {
        pendingReplacement = null;
        uncertainOldHandle = null;
    }

    /** Quarantines both plans if publication fails after the backend may have committed. */
    void retireInterruptedPublication(CoreRegistration replacement, Throwable failure) {
        if (unknownReplacement == replacement) return;
        retireUnknownReplacement(replacement, uncertainOldHandle, failure);
    }

    /** Serializes replacement with direct close() and transfers handle ownership only after commit. */
    synchronized void replaceWith(BoundPlan plan, CoreRegistration replacement) throws BackendInstallException {
        if (state != RegistrationState.INSTALLED) {
            throw new IllegalStateException("registration is not installed");
        }
        BoundPlan previous = plan();
        BackendHandle previousHandle = handles.get(previous);
        if (previousHandle == null) {
            throw new IllegalStateException("installed registration has no backend handle");
        }
        final BackendHandle replacementHandle;
        try {
            try {
                replacingBackend = true;
                replacementHandle = previousHandle.replace(plan, replacement.channel(plan, 0));
            } finally {
                replacingBackend = false;
            }
        } catch (BackendInstallException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            retireUnknownReplacement(replacement, previousHandle, failure);
            throw failure;
        } catch (Error failure) {
            retireUnknownReplacement(replacement, previousHandle, failure);
            throw failure;
        } catch (Throwable failure) {
            // An undeclared checked failure can still follow a committed framework swap.
            retireUnknownReplacement(replacement, previousHandle, failure);
            throw TypeChecks.rethrow(failure);
        }
        try {
            if (replacementHandle == null) {
                throw new IllegalStateException("backend returned a null replacement handle; outcome is unknown");
            }
            // Retain the returned handle even if subsequent bookkeeping is interrupted.
            replacement.pendingReplacementHandle = replacementHandle;
            replacement.bind(plan, replacementHandle);
            handles.remove(previous);
            state = RegistrationState.CLOSED;
            lastCloseReport = new CloseReport(state, true, CloseReport.EntryRestoration.NOT_RESTORED,
                    inFlight.sum(), CloseReport.PhysicalRelease.NOT_CLAIMED,
                    "superseded by generation " + plan.generation() + "; backend handle ownership transferred");
            closeOnce.set(true);
            replacement.pendingReplacementHandle = null;
        } catch (RuntimeException failure) {
            retireUnknownReplacement(replacement, previousHandle, failure);
            throw failure;
        } catch (Error failure) {
            retireUnknownReplacement(replacement, previousHandle, failure);
            throw failure;
        } catch (Throwable failure) {
            // The backend already returned a replacement handle; publication is indeterminate.
            retireUnknownReplacement(replacement, previousHandle, failure);
            throw TypeChecks.rethrow(failure);
        }
    }

    /** Called under the old registration monitor; neither possibly invalid handle is unhooked. */
    private void retireUnknownReplacement(CoreRegistration replacement, BackendHandle oldHandle, Throwable failure) {
        uncertainOldHandle = oldHandle;
        unknownReplacement = replacement;
        replacement.unknownReplacement = this;
        state = RegistrationState.RETIRED_RESIDENT;
        replacement.state = RegistrationState.RETIRED_RESIDENT;
        closeOnce.set(true);
        replacement.closeOnce.set(true);
        try {
            lastCloseReport = new CloseReport(state, true, CloseReport.EntryRestoration.UNKNOWN,
                    inFlight.sum(), CloseReport.PhysicalRelease.UNKNOWN,
                    "replacement outcome is unknown; handles retained and no unhook attempted");
            replacement.lastCloseReport = new CloseReport(replacement.state, true, CloseReport.EntryRestoration.UNKNOWN,
                    replacement.inFlight.sum(), CloseReport.PhysicalRelease.UNKNOWN,
                    "replacement outcome is unknown; handles retained and no unhook attempted");
        } catch (Throwable reportFailure) {
            if (TypeChecks.isFatal(reportFailure) && !TypeChecks.isFatal(failure)) {
                addSuppressedPreservingFatal(reportFailure, failure);
                throw TypeChecks.fatal(reportFailure);
            }
            addSuppressedPreservingFatal(failure, reportFailure);
        }
    }

    private static void addSuppressedPreservingFatal(Throwable primary, Throwable secondary) {
        if (primary == secondary) return;
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable suppressionFailure) {
            if (!TypeChecks.isFatal(primary) && TypeChecks.isFatal(suppressionFailure)) {
                throw TypeChecks.fatal(suppressionFailure);
            }
        }
    }

    boolean replacementOutcomeUnknown() {
        return unknownReplacement != null;
    }

    List<BoundPlan> plans() {
        return plans;
    }

    BoundPlan plan() {
        return plans.get(0);
    }

    boolean isComplete() {
        return handles.size() == plans.size();
    }

    boolean closingOn(Thread thread) { return closeOwner == thread; }

    /** Null while close has not finished; callers must not assume entry restoration in that case. */
    CloseReport closeReport() {
        return lastCloseReport;
    }

    @Override
    public HookId id() {
        return plan().id();
    }

    @Override
    public long generation() {
        return plan().generation();
    }

    @Override
    public RegistrationState state() {
        return state;
    }

    @Override
    public CloseReport close() {
        Thread current = Thread.currentThread();
        boolean claimed = false;
        boolean interrupted = false;
        try {
            Map<BoundPlan, BackendHandle> claimedHandles;
            synchronized (this) {
                if (replacingBackend && !closeOnce.get()) {
                    throw new IllegalStateException("cannot close registration reentrantly during backend replacement");
                }
                while (closingInProgress) {
                    if (closeOwner == current) {
                        throw new IllegalStateException("cannot close registration reentrantly during backend uninstall");
                    }
                    try {
                        wait();
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
                if (!closeOnce.compareAndSet(false, true)) return lastCloseReport;
                claimed = true;
                closingInProgress = true;
                closeOwner = current;
                state = RegistrationState.RETIRING;
                // Replacement cannot change these handles after RETIRING is published.
                claimedHandles = new LinkedHashMap<BoundPlan, BackendHandle>(handles);
            }
            return closeBoundHandles(claimedHandles);
        } finally {
            if (claimed) {
                synchronized (this) {
                    closeOwner = null;
                    closingInProgress = false;
                    notifyAll();
                }
            }
            if (interrupted) current.interrupt();
        }
    }

    private CloseReport closeBoundHandles(Map<BoundPlan, BackendHandle> claimedHandles) {
        if (claimedHandles.isEmpty()) {
            synchronized (this) {
                state = RegistrationState.CLOSED;
                lastCloseReport = new CloseReport(state, true, CloseReport.EntryRestoration.UNKNOWN,
                        inFlight.sum(), CloseReport.PhysicalRelease.NOT_CLAIMED, "no backend handle was bound");
                return lastCloseReport;
            }
        }

        CloseReport.EntryRestoration restoration = CloseReport.EntryRestoration.RESTORED;
        CloseReport.PhysicalRelease physical = CloseReport.PhysicalRelease.RELEASED;
        boolean resident = false;
        List<String> messages = new ArrayList<String>();
        List<Throwable> failures = new ArrayList<Throwable>();
        Error firstError = null;
        boolean restoreInterrupt = false;
        try {
            for (Map.Entry<BoundPlan, BackendHandle> entry : claimedHandles.entrySet()) {
                BackendCloseReport report;
                try {
                    report = entry.getValue().uninstall();
                    if (report == null || report.entryRestoration() == null || report.physicalRelease() == null) {
                        restoration = worstRestoration(restoration, CloseReport.EntryRestoration.UNKNOWN);
                        physical = CloseReport.PhysicalRelease.UNKNOWN;
                        resident = true;
                        messages.add(safeTargetDescription(entry.getKey())
                                + ": uninstall returned an incomplete report; entry restoration is unknown");
                        continue;
                    }
                } catch (RuntimeException failure) {
                    // An unchecked failure can occur before or after the backend removes the entry.
                    restoration = worstRestoration(restoration, CloseReport.EntryRestoration.UNKNOWN);
                    physical = CloseReport.PhysicalRelease.UNKNOWN;
                    resident = true;
                    failures.add(failure);
                    messages.add(safeTargetDescription(entry.getKey()) + ": uninstall failed: " + safeThrowableDescription(failure));
                    continue;
                } catch (Error failure) {
                    if (TypeChecks.isFatal(failure)) {
                        // Leave RETIRING and no completed report: the remaining handles were not tried.
                        throw TypeChecks.fatal(failure);
                    }
                    restoration = worstRestoration(restoration, CloseReport.EntryRestoration.UNKNOWN);
                    physical = CloseReport.PhysicalRelease.UNKNOWN;
                    resident = true;
                    failures.add(failure);
                    if (firstError == null) firstError = failure;
                    messages.add(safeTargetDescription(entry.getKey()) + ": uninstall error: " + safeThrowableDescription(failure));
                    continue;
                } catch (Throwable failure) {
                    // JVM callers (including Kotlin) can throw checked failures despite the SPI's
                    // Java signature. Treat their unknown uninstall outcome like a RuntimeException.
                    if (failure instanceof InterruptedException) restoreInterrupt = true;
                    restoration = worstRestoration(restoration, CloseReport.EntryRestoration.UNKNOWN);
                    physical = CloseReport.PhysicalRelease.UNKNOWN;
                    resident = true;
                    failures.add(failure);
                    messages.add(safeTargetDescription(entry.getKey()) + ": uninstall failed: " + safeThrowableDescription(failure));
                    continue;
                }
                restoration = worstRestoration(restoration, report.entryRestoration());
                physical = worstPhysical(physical, report.physicalRelease());
                resident |= report.resident();
                messages.add(safeTargetDescription(entry.getKey()) + ": " + report.message());
            }
            synchronized (this) {
                state = resident ? RegistrationState.RETIRED_RESIDENT : RegistrationState.CLOSED;
                lastCloseReport = new CloseReport(state, true, restoration, inFlight.sum(), physical,
                        messages.toString());
            }
            if (firstError != null) {
                for (Throwable failure : failures) {
                    if (failure != firstError) firstError.addSuppressed(failure);
                }
                throw firstError;
            }
            return lastCloseReport;
        } finally {
            // Later backends or diagnostic code may clear it while processing remaining handles.
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    /** Diagnostic formatting must not prevent remaining known handles from being uninstalled. */
    private static String safeTargetDescription(BoundPlan plan) {
        try {
            return plan.target().toGenericString();
        } catch (Throwable failure) {
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            try {
                return plan.target().getDeclaringClass().getName() + "#" + plan.target().getName();
            } catch (Throwable fallbackFailure) {
                if (TypeChecks.isFatal(fallbackFailure)) throw TypeChecks.fatal(fallbackFailure);
                return "<target unavailable>";
            }
        }
    }

    /** A backend Throwable may itself throw while being rendered; keep the original failure. */
    private static String safeThrowableDescription(Throwable failure) {
        try {
            return failure.toString();
        } catch (Throwable formattingFailure) {
            if (TypeChecks.isFatal(formattingFailure)) throw TypeChecks.fatal(formattingFailure);
            try {
                return failure.getClass().getName() + " [description unavailable]";
            } catch (Throwable fallbackFailure) {
                if (TypeChecks.isFatal(fallbackFailure)) throw TypeChecks.fatal(fallbackFailure);
                return "<failure unavailable>";
            }
        }
    }

    private static CloseReport.EntryRestoration worstRestoration(CloseReport.EntryRestoration current,
                                                                  CloseReport.EntryRestoration next) {
        if (current == CloseReport.EntryRestoration.NOT_RESTORED || next == CloseReport.EntryRestoration.NOT_RESTORED) {
            return CloseReport.EntryRestoration.NOT_RESTORED;
        }
        if (current == CloseReport.EntryRestoration.UNKNOWN || next == CloseReport.EntryRestoration.UNKNOWN) {
            return CloseReport.EntryRestoration.UNKNOWN;
        }
        return CloseReport.EntryRestoration.RESTORED;
    }

    private static CloseReport.PhysicalRelease worstPhysical(CloseReport.PhysicalRelease current,
                                                              CloseReport.PhysicalRelease next) {
        if (current == CloseReport.PhysicalRelease.UNKNOWN || next == CloseReport.PhysicalRelease.UNKNOWN) {
            return CloseReport.PhysicalRelease.UNKNOWN;
        }
        if (current == CloseReport.PhysicalRelease.NOT_CLAIMED || next == CloseReport.PhysicalRelease.NOT_CLAIMED) {
            return CloseReport.PhysicalRelease.NOT_CLAIMED;
        }
        return CloseReport.PhysicalRelease.RELEASED;
    }

    /**
     * Generic plan channel (before callbacks, several callbacks, around). Backends that bind the
     * optional {@link CoreAfterBridge} use {@link #dispatchDirect}; others keep {@link #dispatch}.
     * Both run the same state machine under the same admission and in-flight lease.
     */
    private final class PlanChannel implements DirectAfterDispatchChannel {
        private final BoundPlan plan;

        private PlanChannel(BoundPlan plan) {
            this.plan = plan;
        }

        @Override
        public Object dispatchDirect(BackendInvocation invocation) throws Throwable {
            if (state != RegistrationState.INSTALLED) return DirectAfterDispatchChannel.NOT_ENTERED;
            int lease = inFlight.enter();
            try {
                if (state != RegistrationState.INSTALLED) return DirectAfterDispatchChannel.NOT_ENTERED;
                return DirectAfterDispatchChannel.escape(Dispatcher.dispatchValue(plan, invocation, diagnostics));
            } finally {
                inFlight.exit(lease);
            }
        }

        @Override
        public DispatchResult dispatch(BackendInvocation invocation) {
            if (state != RegistrationState.INSTALLED) {
                return DispatchResult.passthrough();
            }
            int lease = inFlight.enter();
            try {
                if (state != RegistrationState.INSTALLED) {
                    return DispatchResult.passthrough();
                }
                return Dispatcher.dispatch(plan, invocation, diagnostics);
            } finally {
                inFlight.exit(lease);
            }
        }
    }

    /** Single-after specialization selected at bind; all other plans keep their original dispatch. */
    private final class SingleAfterPlanChannel implements DirectAfterDispatchChannel {
        private final BoundPlan plan;
        private final AfterCallback callback;

        private SingleAfterPlanChannel(BoundPlan plan, AfterCallback callback) {
            this.plan = plan;
            this.callback = callback;
        }

        @Override
        public Object dispatchDirect(BackendInvocation invocation) throws Throwable {
            if (state != RegistrationState.INSTALLED) return DirectAfterDispatchChannel.NOT_ENTERED;
            int lease = inFlight.enter();
            try {
                if (state != RegistrationState.INSTALLED) return DirectAfterDispatchChannel.NOT_ENTERED;
                return DirectAfterDispatchChannel.escape(
                        Dispatcher.dispatchSingleAfterValue(plan, callback, invocation, diagnostics));
            } finally {
                inFlight.exit(lease);
            }
        }

        @Override
        public DispatchResult dispatch(BackendInvocation invocation) {
            if (state != RegistrationState.INSTALLED) return DispatchResult.passthrough();
            int lease = inFlight.enter();
            try {
                if (state != RegistrationState.INSTALLED) return DispatchResult.passthrough();
                return Dispatcher.dispatchSingleAfter(plan, callback, invocation, diagnostics);
            } finally {
                inFlight.exit(lease);
            }
        }
    }

    /** A constant plan needs no invocation or per-call plan-kind lookup. */
    private final class ConstantPlanChannel implements FusedConstantDispatchChannel {
        // Keep the registration owner (and its lifetime) while avoiding a repeated outer-field
        // lookup for the one counter shared by every target of this registration.
        private final InFlightCounter counter;
        private final DispatchResult result;
        private final Object constant;

        private ConstantPlanChannel(DispatchResult result) {
            this.counter = inFlight;
            this.result = result;
            this.constant = result.value();
        }

        @Override
        public boolean requiresInvocation() {
            return false;
        }

        @Override
        public boolean tryEnter() {
            if (state != RegistrationState.INSTALLED) {
                return false;
            }
            int lease = counter.enter();
            if (state != RegistrationState.INSTALLED) {
                counter.exit(lease);
                return false;
            }
            return true;
        }

        @Override
        public Object constantValue() {
            return constant;
        }

        @Override
        public void exit() {
            counter.exitUntracked();
        }

        @Override
        public Object dispatchValueOrNotEntered() {
            if (state != RegistrationState.INSTALLED) return NOT_ENTERED;
            int lease = counter.enter();
            try {
                return state == RegistrationState.INSTALLED ? constant : NOT_ENTERED;
            } finally {
                counter.exit(lease);
            }
        }

        @Override
        public DispatchResult dispatch(BackendInvocation invocation) {
            if (!tryEnter()) return DispatchResult.passthrough();
            try {
                return result;
            } finally {
                exit();
            }
        }
    }

    /** Lease covers the entire downstream call, including its exceptional completion. */
    private final class SuccessResultPlanChannel implements SuccessResultDispatchChannel {
        private final Object value;
        private final DispatchResult result;
        private SuccessResultPlanChannel(Object value) {
            this.value = value;
            this.result = DispatchResult.returned(value);
        }
        @Override public boolean tryEnter() {
            if (state != RegistrationState.INSTALLED) return false;
            int lease = inFlight.enter();
            if (state != RegistrationState.INSTALLED) {
                inFlight.exit(lease);
                return false;
            }
            return true;
        }
        @Override public Object constantValue() { return value; }
        @Override public void exit() { inFlight.exitUntracked(); }
        @Override public DispatchResult dispatch(BackendInvocation invocation) {
            if (!tryEnter()) return DispatchResult.passthrough();
            try {
                Objects.requireNonNull(invocation, "invocation").proceed(null);
                return result;
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                return DispatchResult.threw(failure);
            } finally {
                exit();
            }
        }
    }

    /** Conditional path keeps its lease through the observer or the complete downstream call. */
    private final class ConditionalPlanChannel implements ConditionalDispatchChannel {
        private final int index;
        private final String expected;
        private final Object constant;
        private final MatchObserver observer;
        private final DispatchResult result;

        private ConditionalPlanChannel(BoundPlan plan) {
            StringArgumentCondition condition = plan.stringArgumentCondition();
            this.index = condition.argumentIndex();
            this.expected = condition.expected();
            this.constant = condition.result();
            this.observer = condition.observer();
            this.result = plan.conditionalResult();
        }

        @Override public int argumentIndex() { return index; }

        @Override public boolean tryEnter() {
            if (state != RegistrationState.INSTALLED) return false;
            int lease = inFlight.enter();
            if (state != RegistrationState.INSTALLED) {
                inFlight.exit(lease);
                return false;
            }
            return true;
        }

        @Override public boolean matches(Object value) {
            return Objects.equals(value, expected);
        }

        @Override public void notifyMatch() throws Throwable {
            if (observer != null) observer.onMatch();
        }

        @Override public Object constantValue() { return constant; }

        @Override public void exit() { inFlight.exitUntracked(); }

        @Override public DispatchResult dispatch(BackendInvocation invocation) {
            if (!tryEnter()) return DispatchResult.passthrough();
            try {
                // List acquisition itself is not treated as an absent argument.
                List<?> arguments = Objects.requireNonNull(invocation.readOnlyArguments(), "readOnlyArguments");
                Object value;
                try {
                    value = arguments.get(argumentIndex());
                } catch (IndexOutOfBoundsException missing) {
                    value = null;
                }
                if (matches(value)) {
                    notifyMatch();
                    return result;
                }
                return DispatchResult.returned(invocation.proceed(null));
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                return DispatchResult.threw(failure);
            } finally {
                exit();
            }
        }
    }
}
