package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import java.util.function.Function;

/** Optional backend binding for the direct single-after transport. */
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
