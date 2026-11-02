package io.github.jichuo1.meguviz.hook.core;

/**
 * Optional single-call constant dispatch for a backend and channel that explicitly support it.
 * The returned reference is either the exact bound constant or {@link #NOT_ENTERED}; it must not
 * expose another value. The implementation retains the same admission and in-flight lifetime as
 * {@link ConstantDispatchChannel#tryEnter()} and {@link ConstantDispatchChannel#exit()}.
 *
 * <p>The sentinel is compared by identity, never by equals. If the bound constant is itself the
 * sentinel, a backend must use the ordinary tryEnter/exit path, so that it remains a valid result.
 * A backend must also use ordinary dispatch when requiresInvocation is true. Old backends and
 * channels that only implement ConstantDispatchChannel do not use this optional fast path.
 */
public interface FusedConstantDispatchChannel extends ConstantDispatchChannel {
    Object NOT_ENTERED = new Object();

    Object dispatchValueOrNotEntered();
}
