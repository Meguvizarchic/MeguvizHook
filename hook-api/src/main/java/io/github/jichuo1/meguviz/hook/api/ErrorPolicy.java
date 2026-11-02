package io.github.jichuo1.meguviz.hook.api;

/**
 * Failure policy for ordinary callback failures (CONTRACT-001 §3).
 *
 * <p>{@link #PROPAGATE} is the only default of the new API:
 * <ul>
 *   <li>a failing {@code before} prevents the original call (downstream executions = 0);</li>
 *   <li>a failing original keeps its own throwable instance unless explicitly replaced;</li>
 *   <li>a failing {@code after} after a successful original propagates the after failure and never
 *       re-executes the original;</li>
 *   <li>when both fail, the original throwable wins and the callback failure goes to bounded
 *       diagnostics;</li>
 *   <li>an {@code around} failure after {@code proceed()} never replays the original.</li>
 * </ul>
 *
 * <p>The SDK itself rethrows fatal VM events ({@link VirtualMachineError}, {@link ThreadDeath})
 * rather than turning them into ordinary callback results or diagnostics. Under
 * {@link #FRAMEWORK_PROTECTIVE}, the framework receives the throwable and its own protective
 * policy determines what reaches the host; the SDK cannot promise fatal propagation there.
 *
 * <p>Protection concerns result/throwable slots and callback control state only. It never rolls
 * back host objects, argument object internals or external I/O side effects.
 */
public enum ErrorPolicy {
    PROPAGATE,

    /**
     * Ask a capable framework to apply its own protective exception mode to the installed hook.
     * The SDK still dispatches callbacks according to its contract and does not replay downstream.
     * The framework, not the SDK, decides whether an escaped hooker failure is protected, including
     * whether a fatal throwable reaches the host. This does not promise BIL's multi-callback DSL
     * semantics, framework behavior on every implementation, or rollback of side effects.
     */
    FRAMEWORK_PROTECTIVE
}
