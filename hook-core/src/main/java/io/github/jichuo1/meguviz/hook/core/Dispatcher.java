package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

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
            BeforeCallback single = plan.singleBeforeOnly();
            if (single != null) {
                return dispatchSingleBefore(single, frame);
            }
            return dispatchBeforeAfter(plan, frame, diagnostics);
        } finally {
            frame.invalidate();
        }
    }

    /** Selected once by the registration when binding a sole after-only plan. */
    static DispatchResult dispatchSingleAfter(BoundPlan plan, AfterCallback callback,
                                               BackendInvocation backend, DiagnosticsSink diagnostics) {
        InvocationFrame frame = new InvocationFrame(plan, backend);
        try {
            return dispatchSingleAfter(plan, callback, frame, diagnostics).dispatchResult();
        } finally {
            frame.invalidate();
        }
    }

    static Object dispatchSingleAfterValue(BoundPlan plan, AfterCallback callback,
                                           BackendInvocation backend, DiagnosticsSink diagnostics) throws Throwable {
        InvocationFrame frame = new InvocationFrame(plan, backend);
        try {
            return dispatchSingleAfter(plan, callback, frame, diagnostics).dispatchValue();
        } finally {
            frame.invalidate();
        }
    }

    /**
     * A sole after callback is the frame's only borrow. The result slot remains a distinct object
     * but can use the frame lifetime directly, eliminating two per-callback wrappers. Expire both
     * before handling failure or calling diagnostics, just as the ordinary borrowed view does.
     */
    private static InvocationFrame.ResultSlot dispatchSingleAfter(BoundPlan plan, AfterCallback callback,
                                                       InvocationFrame frame, DiagnosticsSink diagnostics) {
        InvocationFrame.ResultSlot slot = frame.resultSlot();
        Throwable originalFailure = null;
        try {
            slot.initReturned(frame.backend().proceed(frame.argsForProceed()));
        } catch (Throwable failure) {
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            originalFailure = failure;
            slot.initThrown(failure);
        }
        try {
            try {
                callback.after(frame, slot);
            } finally {
                frame.invalidate();
            }
            frame.rethrowObservedFatal();
        } catch (Throwable failure) {
            frame.rethrowObservedFatal();
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            if (originalFailure == null) {
                slot.initThrown(failure);
            } else {
                try {
                    diagnostics.record(new DiagnosticsSink.Event(
                            DiagnosticsSink.Event.Kind.AFTER_CALLBACK_FAILED_WHILE_ORIGINAL_FAILED,
                            plan.id(), plan.generation(), failure, "after callback failed; original throwable kept"));
                } catch (Throwable diagnosticFailure) {
                    if (TypeChecks.isFatal(diagnosticFailure)) throw TypeChecks.fatal(diagnosticFailure);
                }
                slot.restoreOriginal(originalFailure);
            }
        }
        return slot;
    }

    /**
     * With exactly one before callback, the per-invocation frame is itself its only borrow. Expire
     * it before downstream code runs, so a retained callback reference cannot be used by the
     * original method or by synchronous reentry. Internal dispatch state remains accessible only
     * through package-private methods that do not expose the expired invocation to consumers.
     */
    private static DispatchResult dispatchSingleBefore(BeforeCallback callback, InvocationFrame frame) {
        try {
            callback.before(frame);
            frame.rethrowObservedFatal();
        } catch (Throwable failure) {
            frame.rethrowObservedFatal();
            if (TypeChecks.isFatal(failure)) {
                throw TypeChecks.fatal(failure);
            }
            return DispatchResult.threw(failure);
        } finally {
            frame.invalidate();
        }
        if (frame.hasEarlyOutcomeForDispatch()) return frame.earlyDispatchResult();
        try {
            return DispatchResult.returned(frame.backend().proceed(frame.argsForProceed()));
        } catch (Throwable failure) {
            if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
            return DispatchResult.threw(failure);
        }
    }

    private static DispatchResult dispatchBeforeAfter(BoundPlan plan, InvocationFrame frame, DiagnosticsSink diagnostics) {
        // The plan freezes callbacks into arrays at bind time, so indexed iteration preserves the
        // same order and early-stop semantics without a List or Iterator per call.
        BeforeCallback[] befores = plan.befores();
        for (int i = 0; i < befores.length; i++) {
            BeforeCallback callback = befores[i];
            try {
                InvocationFrame.BorrowedInvocation borrowed = frame.borrow();
                try {
                    callback.before(borrowed);
                } finally {
                    borrowed.invalidate();
                }
                frame.rethrowObservedFatal();
            } catch (Throwable failure) {
                frame.rethrowObservedFatal();
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

        AfterCallback[] afters = plan.afters();
        if (afters.length == 0) {
            if (frame.hasEarlyOutcome()) return frame.earlyDispatchResult();
            try {
                return DispatchResult.returned(frame.backend().proceed(frame.argsForProceed()));
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                return DispatchResult.threw(failure);
            }
        }

        InvocationFrame.ResultSlot slot = frame.resultSlot();
        Throwable originalFailure = null;
        if (frame.hasEarlyOutcome()) {
            frame.initializeEarlyResult(slot);
        } else {
            try {
                slot.initReturned(frame.backend().proceed(frame.argsForProceed()));
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) {
                    throw TypeChecks.fatal(failure);
                }
                originalFailure = failure;
                slot.initThrown(failure);
            }
        }

        Throwable firstAfterFailure = null;
        for (int i = afters.length - 1; i >= 0; i--) {
            try {
                InvocationFrame.BorrowedInvocation borrowed = frame.borrow();
                try {
                    afters[i].after(borrowed, borrowed.result());
                } finally {
                    borrowed.invalidate();
                }
                frame.rethrowObservedFatal();
            } catch (Throwable failure) {
                frame.rethrowObservedFatal();
                if (TypeChecks.isFatal(failure)) {
                    throw TypeChecks.fatal(failure);
                }
                if (firstAfterFailure == null) {
                    firstAfterFailure = failure;
                }
                if (originalFailure != null) {
                    // Both failed: the original throwable wins, the callback failure is diagnostic only.
                    try {
                        diagnostics.record(new DiagnosticsSink.Event(
                                DiagnosticsSink.Event.Kind.AFTER_CALLBACK_FAILED_WHILE_ORIGINAL_FAILED,
                                plan.id(), plan.generation(), failure, "after callback failed; original throwable kept"));
                    } catch (Throwable diagnosticFailure) {
                        if (TypeChecks.isFatal(diagnosticFailure)) {
                            throw TypeChecks.fatal(diagnosticFailure);
                        }
                        // A failing diagnostics sink must not replace the original failure.
                    }
                }
            }
            if (firstAfterFailure != null) {
                // Continue the remaining after callbacks, but do not let later slot writes erase a
                // failure already fixed by the contract. The original exception has precedence.
                if (originalFailure != null) {
                    slot.restoreOriginal(originalFailure);
                } else {
                    slot.initThrown(firstAfterFailure);
                }
            }
        }
        return slot.dispatchResult();
    }

    private static DispatchResult dispatchAround(BoundPlan plan, InvocationFrame frame) {
        try {
            try {
                // An around plan has exactly one callback, so its invocation frame itself is the
                // sole borrow. The frame stays valid during proceed(), including reentry, and is
                // expired at callback exit before any result processing or error handling.
                plan.aroundCallback().around(frame);
            } finally {
                frame.invalidate();
            }
            frame.rethrowObservedFatal();
        } catch (Throwable failure) {
            frame.rethrowObservedFatal();
            if (TypeChecks.isFatal(failure)) {
                throw TypeChecks.fatal(failure);
            }
            // Even after a successful proceed the original is never replayed.
            return DispatchResult.threw(failure);
        }
        if (frame.hasEarlyOutcomeForDispatch()) {
            return frame.earlyDispatchResult();
        }
        CallOutcome.State state = frame.proceedStateForDispatch();
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
