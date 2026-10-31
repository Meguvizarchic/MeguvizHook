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
 * <p>Fatal VM events ({@link VirtualMachineError}, {@link ThreadDeath}) are not ordinary callback
 * failures: they are always rethrown, never swallowed into diagnostics.
 *
 * <p>Protection concerns result/throwable slots and callback control state only. It never rolls
 * back host objects, argument object internals or external I/O side effects. Legacy facades that
 * need another behaviour must use a separately named preset; none is provided in this slice.
 */
public enum ErrorPolicy {
    PROPAGATE
}
