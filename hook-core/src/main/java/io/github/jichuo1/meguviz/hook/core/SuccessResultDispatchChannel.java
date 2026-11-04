package io.github.jichuo1.meguviz.hook.core;

/**
 * Optional backend fast path for replacing a result only after downstream succeeds.
 * A successful {@link #tryEnter()} remains leased until downstream returns or throws, and must
 * be paired with {@link #exit()} exactly once. {@link #constantValue()} returns the same exact
 * prevalidated reference throughout the binding, including when that reference is null.
 *
 * <p>{@link #requiresInvocation()} must remain true: older backends use ordinary
 * {@link #dispatch(io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation)} with a non-null
 * invocation, which proceeds exactly once with the original arguments, keeps the returned
 * constant only on success, and propagates the original failure according to the core contract.
 * It must not also implement the invocation-free constant channel: that channel's contract
 * skips downstream, whereas this one requires it. Call {@link #exit()} on the thread that called
 * tryEnter, as a framework continuation does: the concurrent bounds of the reported in-flight
 * count rely on it; a quiescent count stays exact either way.
 */
public interface SuccessResultDispatchChannel extends DispatchChannel {
    @Override
    default boolean requiresInvocation() { return true; }

    boolean tryEnter();
    Object constantValue();
    void exit();
}
