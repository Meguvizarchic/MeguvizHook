package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor;

/**
 * Optional flyweight transport: the core reads the framework chain through a stateless
 * {@link ChainAccessor} instead of a per-call {@code BackendInvocation}. The core implements it for
 * the channels that also implement {@link DirectAfterDispatchChannel}; backends reach it only through
 * {@link CoreChainBridge}, and every implementation keeps {@link #dispatch} and
 * {@link #dispatchDirect} with identical semantics.
 *
 * <p>{@link #dispatchChain} uses the encoding of {@link #dispatchDirect}: {@link #NOT_ENTERED} where
 * {@code dispatch} returns PASSTHROUGH, otherwise the RETURNED value with one level of
 * {@link DirectAfterDispatchChannel#escape escaping}; it throws the throwable {@code dispatch}
 * reports as THREW, and fatal VM events propagate unchanged.
 */
public interface ChainDispatchChannel extends DirectAfterDispatchChannel {
    <C> Object dispatchChain(ChainAccessor<C> accessor, C chain) throws Throwable;
}
