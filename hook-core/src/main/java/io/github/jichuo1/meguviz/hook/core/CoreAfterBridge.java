package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import java.util.function.Function;

/**
 * Optional backend binding for the direct value transport of {@link DirectAfterDispatchChannel}
 * (single-after and generic plan channels). Returns null for any other channel, which keeps the
 * ordinary {@link DispatchChannel#dispatch} path. The bound function returns {@code notEntered}
 * when the backend must run the original once without SDK callbacks; an application value that is
 * itself {@code notEntered} or a {@link DispatchResult} is wrapped in {@link DispatchResult#returned}
 * so the backend can tell them apart. Throwables, including fatal VM events, propagate unchanged.
 */
public final class CoreAfterBridge {
    private CoreAfterBridge() { }

    public static Function<BackendInvocation, Object> bind(DispatchChannel channel, Object notEntered) {
        if (!(channel instanceof DirectAfterDispatchChannel)) return null;
        DirectAfterDispatchChannel direct = (DirectAfterDispatchChannel) channel;
        return invocation -> {
            try {
                Object encoded = direct.dispatchDirect(invocation);
                if (encoded == DirectAfterDispatchChannel.NOT_ENTERED) return notEntered;
                Object value = DirectAfterDispatchChannel.unescape(encoded);
                // Preserve application values even when they equal the marker or a result wrapper.
                return value == notEntered || value instanceof DispatchResult
                        ? DispatchResult.returned(value) : value;
            } catch (Throwable failure) {
                return CoreAfterBridge.<RuntimeException, Object>throwUnchecked(failure);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
