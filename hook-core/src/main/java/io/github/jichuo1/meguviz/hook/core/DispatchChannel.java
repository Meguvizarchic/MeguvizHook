package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

/** Hot-path entry from a backend into the core. Bound to one registration and one generation. */
public interface DispatchChannel {
    /**
     * Whether this channel needs a backend invocation to dispatch. When false, a backend may pass
     * null to dispatch and must still run the original chain once if the result is PASSTHROUGH.
     * This answer must remain constant for the lifetime of this channel; adapters may cache it at
     * binding. The default preserves the contract for existing third-party channels.
     */
    default boolean requiresInvocation() {
        return true;
    }

    DispatchResult dispatch(BackendInvocation invocation);
}
