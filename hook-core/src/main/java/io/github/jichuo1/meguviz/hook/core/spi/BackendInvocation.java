package io.github.jichuo1.meguviz.hook.core.spi;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;

/**
 * One live invocation as seen by a backend. Argument materialization may be expensive, so the core
 * only calls {@link #arguments()} for shapes that actually need it.
 *
 * <p>{@link #proceed(Object[])} runs the downstream continuation with the supplied arguments and
 * propagates its throwable. A {@code null} argument array means "keep the incoming arguments", so a
 * {@code NO_ARGS} registration never forces argument materialization. Fatal VM errors thrown by the
 * downstream are never converted into an ordinary outcome by the core.
 */
public interface BackendInvocation {
    Executable target();

    Object thisObject();

    Object[] arguments();

    /**
     * A read-only view of incoming argument slots for the lifetime of this invocation. The core
     * retains the returned list until dispatch ends but never exposes it to callbacks or modifies it.
     * The adapter must not change its slots during the invocation; argument objects themselves may
     * be mutable. Existing adapters fall back to a defensive copy of their ordinary argument array,
     * because the older arguments() contract does not promise exclusive ownership. An override may
     * avoid that copy, but neither this contract nor the fallback promises zero allocations.
     */
    default List<?> readOnlyArguments() {
        return Arrays.asList(arguments().clone());
    }

    Object proceed(Object[] arguments) throws Throwable;
}
