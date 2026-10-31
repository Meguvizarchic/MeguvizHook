package io.github.jichuo1.meguviz.hook.api.identity;

/**
 * Service session epoch. Reserved identity type for the optional service line: every reconnect
 * increments it, and a late answer from an older epoch must not overwrite a newer session.
 * No service is started by this slice; the type exists so the identity models cannot be conflated.
 */
public final class ServiceEpoch {
    private final long value;

    public ServiceEpoch(long value) {
        this.value = value;
    }

    public long value() {
        return value;
    }

    @Override
    public String toString() {
        return "ServiceEpoch[" + value + "]";
    }
}
