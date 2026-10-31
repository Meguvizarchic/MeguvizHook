package io.github.jichuo1.meguviz.hook.core.spi;

import java.lang.reflect.Executable;

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

    Object proceed(Object[] arguments) throws Throwable;
}
