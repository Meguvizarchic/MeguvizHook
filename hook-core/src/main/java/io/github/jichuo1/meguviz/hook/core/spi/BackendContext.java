package io.github.jichuo1.meguviz.hook.core.spi;

/**
 * Runtime facts supplied by the backend at install time. {@link #codeEpoch()} must change whenever
 * already resolved members or negative lookups may no longer be valid; without a reliable epoch the
 * core does not reuse negative caches (this slice keeps no resolution cache at all).
 */
public interface BackendContext {
    String processName();

    /** Actual loader used for descriptor resolution. Never keyed by {@code ClassLoader.toString()}. */
    ClassLoader classLoader();

    long codeEpoch();
}
