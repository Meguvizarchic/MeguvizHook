package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One logical registration and its immutable per-target plans. Every backend entry gets a channel
 * bound to its own plan, while close state and in-flight accounting are shared by the whole logical
 * registration. Closing never replays a downstream call and never waits for arbitrary host code.
 */
final class CoreRegistration implements HookRegistration {
    private final List<BoundPlan> plans;
    private final DiagnosticsSink diagnostics;
    private final Map<BoundPlan, BackendHandle> handles = new LinkedHashMap<BoundPlan, BackendHandle>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean closeOnce = new AtomicBoolean();
    private volatile RegistrationState state = RegistrationState.INSTALLED;
    private volatile CloseReport lastCloseReport;

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

    DispatchChannel channel(BoundPlan plan) {
        if (!plans.contains(plan)) {
            throw new IllegalArgumentException("plan does not belong to this registration");
        }
        return new PlanChannel(plan);
    }

    void bind(BoundPlan plan, BackendHandle backendHandle) {
        handles.put(plan, backendHandle);
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
            replacementHandle = previousHandle.replace(plan, replacement.channel(plan));
        } catch (BackendInstallException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BackendInstallException("backend replacement failed", failure);
        }
        if (replacementHandle == null) {
            throw new AssertionError("backend returned a null handle after replacement commit");
        }
        replacement.bind(plan, replacementHandle);
        handles.remove(previous);
        state = RegistrationState.CLOSED;
        lastCloseReport = new CloseReport(state, true, CloseReport.EntryRestoration.NOT_RESTORED,
                inFlight.get(), CloseReport.PhysicalRelease.NOT_CLAIMED,
                "superseded by generation " + plan.generation() + "; backend handle ownership transferred");
        closeOnce.set(true);
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
    public synchronized CloseReport close() {
        if (!closeOnce.compareAndSet(false, true)) {
            return lastCloseReport;
        }
        state = RegistrationState.RETIRING;
        if (handles.isEmpty()) {
            state = RegistrationState.CLOSED;
            lastCloseReport = new CloseReport(state, true, CloseReport.EntryRestoration.UNKNOWN,
                    inFlight.get(), CloseReport.PhysicalRelease.NOT_CLAIMED, "no backend handle was bound");
            return lastCloseReport;
        }

        CloseReport.EntryRestoration restoration = CloseReport.EntryRestoration.RESTORED;
        CloseReport.PhysicalRelease physical = CloseReport.PhysicalRelease.RELEASED;
        boolean resident = false;
        List<String> messages = new ArrayList<String>();
        for (Map.Entry<BoundPlan, BackendHandle> entry : handles.entrySet()) {
            BackendCloseReport report;
            try {
                report = entry.getValue().uninstall();
            } catch (RuntimeException failure) {
                restoration = CloseReport.EntryRestoration.NOT_RESTORED;
                physical = CloseReport.PhysicalRelease.UNKNOWN;
                resident = true;
                messages.add(entry.getKey().target().toGenericString() + ": uninstall failed: " + failure);
                continue;
            }
            restoration = worstRestoration(restoration, report.entryRestoration());
            physical = worstPhysical(physical, report.physicalRelease());
            resident |= report.resident();
            messages.add(entry.getKey().target().toGenericString() + ": " + report.message());
        }
        state = resident ? RegistrationState.RETIRED_RESIDENT : RegistrationState.CLOSED;
        lastCloseReport = new CloseReport(state, true, restoration, inFlight.get(), physical,
                messages.toString());
        return lastCloseReport;
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

    private final class PlanChannel implements DispatchChannel {
        private final BoundPlan plan;

        private PlanChannel(BoundPlan plan) {
            this.plan = plan;
        }

        @Override
        public DispatchResult dispatch(BackendInvocation invocation) {
            if (state != RegistrationState.INSTALLED) {
                return DispatchResult.passthrough();
            }
            inFlight.incrementAndGet();
            try {
                if (state != RegistrationState.INSTALLED) {
                    return DispatchResult.passthrough();
                }
                return Dispatcher.dispatch(plan, invocation, diagnostics);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }
}
