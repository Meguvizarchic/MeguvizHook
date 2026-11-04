package io.github.jichuo1.meguviz.hook.core.spi;

/**
 * Runtime facts supplied by the backend at install time. {@link #codeEpoch()} must change whenever
 * already resolved members or negative lookups may no longer be valid, and must never return to an
 * earlier value (no ABA). The core keeps no negative cache and no resolution cache across calls;
 * only {@code HookEnvironment.installAll} lets a resolver reuse positive results within that one
 * call while the loader and this epoch stay unchanged.
 */
public interface BackendContext {
    String processName();

    /** Actual loader used for descriptor resolution. Never keyed by {@code ClassLoader.toString()}. */
    ClassLoader classLoader();

    long codeEpoch();
}
