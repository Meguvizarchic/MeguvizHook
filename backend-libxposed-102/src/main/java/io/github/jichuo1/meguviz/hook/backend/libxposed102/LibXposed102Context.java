package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;

import java.util.Objects;

/**
 * Install-time context supplied by the module entry: the process name reported by the framework,
 * the actual package class loader, and the code epoch of this module generation.
 *
 * <p>This slice has no hot reload, so the epoch is normally constant for the lifetime of the
 * process. A future hot-reload profile must advance it instead of reusing stale plans.
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
