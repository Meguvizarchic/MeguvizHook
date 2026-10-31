package io.github.jichuo1.meguviz.hook.api.identity;

/**
 * Configuration revision. Only a backend with a real acknowledgement channel may present this as an
 * accepted remote revision; otherwise it is a local observation and must be labelled as such.
 * Unused by the default JVM dispatch path in this slice.
 */
public final class ConfigRevision {
    private final long value;
    private final boolean remotelyAcknowledged;

    public ConfigRevision(long value, boolean remotelyAcknowledged) {
        this.value = value;
        this.remotelyAcknowledged = remotelyAcknowledged;
    }

    public long value() {
        return value;
    }

    public boolean remotelyAcknowledged() {
        return remotelyAcknowledged;
    }

    @Override
    public String toString() {
        return "ConfigRevision[" + value + (remotelyAcknowledged ? " acked" : " local") + "]";
    }
}
