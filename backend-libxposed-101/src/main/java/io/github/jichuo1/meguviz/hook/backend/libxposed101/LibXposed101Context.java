package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;

import java.util.Objects;

/** Actual process, package loader and module-generation epoch captured at install time. */
public final class LibXposed101Context implements BackendContext {
    private final String processName;
    private final ClassLoader classLoader;
    private final long codeEpoch;

    public LibXposed101Context(String processName, ClassLoader classLoader, long codeEpoch) {
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
