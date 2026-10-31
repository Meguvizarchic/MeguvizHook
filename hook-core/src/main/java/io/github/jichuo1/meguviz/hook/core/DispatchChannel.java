package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

/** Hot-path entry from a backend into the core. Bound to one registration and one generation. */
public interface DispatchChannel {
    DispatchResult dispatch(BackendInvocation invocation);
}
