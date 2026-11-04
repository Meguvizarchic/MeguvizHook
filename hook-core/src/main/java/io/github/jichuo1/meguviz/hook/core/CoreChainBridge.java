package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor;
import java.util.Objects;
import java.util.function.Function;

/**
 * Optional backend binding for the flyweight transport of {@link ChainDispatchChannel}. A backend
 * binds once per hooker, with its own marker and its stateless accessor, and then calls the returned
 * function with the framework's per-call chain object. The function follows {@link CoreAfterBridge}:
 * it returns {@code notEntered} when the backend must run the original once without SDK callbacks,
 * wraps an application value that is {@code notEntered} or a {@link DispatchResult} in
 * {@link DispatchResult#returned}, and lets throwables, including fatal VM events, propagate.
 *
 * <p>Returns null for a channel without the flyweight transport; the backend then keeps
 * {@link CoreAfterBridge} or {@link DispatchChannel#dispatch}. The function's types exist in older
 * cores too, so a backend can look this class up reflectively and keep running on a core without it.
 */
public final class CoreChainBridge {
    private CoreChainBridge() { }

    public static Function<Object, Object> bind(DispatchChannel channel, Object notEntered, ChainAccessor<?> accessor) {
        Objects.requireNonNull(accessor, "accessor");
        if (!(channel instanceof ChainDispatchChannel)) return null;
        ChainDispatchChannel direct = (ChainDispatchChannel) channel;
        @SuppressWarnings("unchecked")
        ChainAccessor<Object> bound = (ChainAccessor<Object>) accessor;
        return chain -> {
            try {
                return CoreAfterBridge.decode(direct.dispatchChain(bound, chain), notEntered);
            } catch (Throwable failure) {
                return CoreAfterBridge.<RuntimeException, Object>throwUnchecked(failure);
            }
        };
    }
}
