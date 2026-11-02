package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;

import java.util.Objects;

/**
 * Install-time context supplied by the module entry: the process name reported by the framework,
 * the actual package class loader, and the code epoch of this module generation.
 *
 * <p>For module hot reload, create a new context for the new generation and advance the epoch;
 * do not reuse plans or an environment from the previous module generation.
 */
public final class LibXposed102Context implements BackendContext {
    private final String processName;
    private final ClassLoader classLoader;
    private final long codeEpoch;

    public LibXposed102Context(String processName, ClassLoader classLoader, long codeEpoch) {
        this.processName = Objects.requireNonNull(processName, "processName");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.codeEpoch = codeEpoch;
    }

    @Override
    public String processName() {
        return processName;
    }

    @Override
    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public long codeEpoch() {
        return codeEpoch;
    }
}
