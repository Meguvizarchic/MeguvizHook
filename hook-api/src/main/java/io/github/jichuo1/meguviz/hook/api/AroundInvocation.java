package io.github.jichuo1.meguviz.hook.api;

/**
 * Around shape with an at-most-once continuation.
 *
 * <p>The callback must either call {@link #proceed()} exactly once or set an explicit early
 * outcome. Returning without either is a contract violation and never triggers an implicit
 * original call. A second {@code proceed()}, a {@code proceed()} after the callback returned, or a
 * {@code proceed()} from another thread raises {@link HookContractException}. A {@code proceed()}
 * after an explicit early outcome is rejected before the downstream call. After a successful or
 * failed {@code proceed()}, a callback may explicitly replace its outcome without replaying it.
 */
public interface AroundInvocation extends MutableArgsInvocation {
    /** Runs the downstream continuation once and reports its outcome instead of throwing it. */
    CallOutcome proceed();

    CallOutcome.State proceedState();
}
