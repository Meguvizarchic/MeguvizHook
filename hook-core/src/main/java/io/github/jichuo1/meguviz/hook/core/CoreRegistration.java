package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registration state plus its dispatch channel.
 *
 * <p>Close rejects new SDK work without holding a lock and without waiting for arbitrary host
 * calls: a racing call either finishes on the old immutable plan or falls through to the original
 * exactly once via {@link DispatchResult#passthrough()}. It never sees half of two generations.
 */
final class CoreRegistration implements HookRegistration, DispatchChannel {
    private final BoundPlan plan;
    private final DiagnosticsSink diagnostics;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean closeOnce = new AtomicBoolean();
    private volatile RegistrationState state = RegistrationState.INSTALLED;
    private volatile BackendHandle handle;
    private volatile CloseReport lastCloseReport;

    CoreRegistration(BoundPlan plan, DiagnosticsSink diagnostics) {
        this.plan = plan;
        this.diagnostics = diagnostics;
    }

    void bind(BackendHandle backendHandle) {
        this.handle = backendHandle;
    }

    BoundPlan plan() {
        return plan;
    }

    @Override
    public DispatchResult dispatch(BackendInvocation invocation) {
        if (state != RegistrationState.INSTALLED) {
            return DispatchResult.passthrough();
        }
        inFlight.incrementAndGet();
        try {
            if (state != RegistrationState.INSTALLED) {
                // Close won the race before any SDK work happened; run the original once instead.
                return DispatchResult.passthrough();
            }
            return Dispatcher.dispatch(plan, invocation, diagnostics);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public HookId id() {
        return plan.id();
    }

    @Override
    public long generation() {
        return plan.generation();
    }

    @Override
    public RegistrationState state() {
        return state;
    }

    @Override
    public CloseReport close() {
        if (!closeOnce.compareAndSet(false, true)) {
            return lastCloseReport;
        }
        state = RegistrationState.RETIRING;
        BackendHandle current = handle;
        if (current == null) {
            state = RegistrationState.CLOSED;
            lastCloseReport = new CloseReport(state, true, CloseReport.EntryRestoration.UNKNOWN,
                    inFlight.get(), CloseReport.PhysicalRelease.NOT_CLAIMED, "never bound to a backend handle");
            return lastCloseReport;
        }
        BackendCloseReport report = current.uninstall();
        state = report.resident() ? RegistrationState.RETIRED_RESIDENT : RegistrationState.CLOSED;
        lastCloseReport = new CloseReport(state, true, report.entryRestoration(), inFlight.get(),
                report.physicalRelease(), report.message());
        return lastCloseReport;
    }
}
