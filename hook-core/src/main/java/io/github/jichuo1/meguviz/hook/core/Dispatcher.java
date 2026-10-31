package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

import java.util.List;

/**
 * Hot-path state machine for one bound plan. It only reads the frozen plan and executes business
 * callbacks plus the required type/lifetime checks: no declaration re-parsing, no manager
 * discovery, no disk or network access, no service connection, no JNI and no IPC added by this SDK.
 *
 * <p>Default policy is PROPAGATE (CONTRACT-001 §3):
 * <ul>
 *   <li>before failure: downstream runs 0 times, after callbacks do not run, the failure propagates;</li>
 *   <li>explicit early outcome: downstream runs 0 times, after callbacks still observe the outcome;</li>
 *   <li>downstream failure: after callbacks observe the original throwable, which is kept unless
 *       explicitly replaced;</li>
 *   <li>after failure after a successful downstream: the after failure propagates, the original is
 *       not executed again;</li>
 *   <li>after failure while the downstream already failed: the original throwable is restored and
 *       the after failure goes to bounded diagnostics;</li>
 *   <li>around failure after proceed: the around failure propagates and proceed count never grows.</li>
 * </ul>
 * Fatal VM errors are rethrown immediately in every stage and are never routed to diagnostics.
 */
final class Dispatcher {
    private Dispatcher() {
    }

    static DispatchResult dispatch(BoundPlan plan, BackendInvocation backend, DiagnosticsSink diagnostics) {
        InvocationFrame frame = new InvocationFrame(plan, backend);
        try {
            if (plan.access() == ParameterAccess.AROUND) {
                return dispatchAround(plan, frame);
            }
            return dispatchBeforeAfter(plan, frame, diagnostics);
        } finally {
            frame.invalidate();
        }
    }

    private static DispatchResult dispatchBeforeAfter(BoundPlan plan, InvocationFrame frame, DiagnosticsSink diagnostics) {
        for (BeforeCallback callback : plan.beforeCallbacks()) {
            try {
                callback.before(frame);
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) {
                    throw TypeChecks.fatal(failure);
                }
                // Original not started: it must stay at zero executions.
                return DispatchResult.threw(failure);
            }
            if (frame.hasEarlyOutcome()) {
                break;
            }
        }

        CallOutcome outcome;
        Throwable originalFailure = null;
        if (frame.hasEarlyOutcome()) {
            outcome = frame.earlyOutcome();
        } else {
            try {
                outcome = CallOutcome.returned(frame.backend().proceed(frame.argsForProceed()));
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) {
                    throw TypeChecks.fatal(failure);
                }
                originalFailure = failure;
                outcome = CallOutcome.threw(failure);
            }
        }

        InvocationFrame.ResultSlot slot = frame.resultSlot();
        slot.init(outcome);
        List<AfterCallback> afters = plan.afterCallbacks();
        for (int i = afters.size() - 1; i >= 0; i--) {
            try {
                afters.get(i).after(frame, slot);
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) {
                    throw TypeChecks.fatal(failure);
                }
                if (originalFailure != null) {
                    // Both failed: the original throwable wins, the callback failure is diagnostic only.
                    slot.restoreOriginal(originalFailure);
                    diagnostics.record(new DiagnosticsSink.Event(
                            DiagnosticsSink.Event.Kind.AFTER_CALLBACK_FAILED_WHILE_ORIGINAL_FAILED,
                            plan.id(), plan.generation(), failure, "after callback failed; original throwable kept"));
                } else {
                    slot.init(CallOutcome.threw(failure));
                }
            }
        }
        return toDispatchResult(slot.outcome());
    }

    private static DispatchResult dispatchAround(BoundPlan plan, InvocationFrame frame) {
        try {
            plan.spec().aroundCallback().around(frame);
        } catch (Throwable failure) {
            if (TypeChecks.isFatal(failure)) {
                throw TypeChecks.fatal(failure);
            }
            // Even after a successful proceed the original is never replayed.
            return DispatchResult.threw(failure);
        }
        if (frame.hasEarlyOutcome()) {
            return toDispatchResult(frame.earlyOutcome());
        }
        CallOutcome.State state = frame.proceedState();
        if (state == CallOutcome.State.RETURNED || state == CallOutcome.State.THREW) {
            return toDispatchResult(frame.proceedOutcome());
        }
        return DispatchResult.threw(new HookContractException(
                "around callback of " + plan.id() + " returned without proceed() or an explicit outcome;"
                        + " the original was not executed implicitly"));
    }

    private static DispatchResult toDispatchResult(CallOutcome outcome) {
        return outcome.state() == CallOutcome.State.THREW
                ? DispatchResult.threw(outcome.throwable())
                : DispatchResult.returned(outcome.state() == CallOutcome.State.RETURNED ? outcome.value() : null);
    }
}
