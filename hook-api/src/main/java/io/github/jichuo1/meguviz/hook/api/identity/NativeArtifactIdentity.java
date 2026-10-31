package io.github.jichuo1.meguviz.hook.api.identity;

import java.util.Objects;

/**
 * Identity of an optional native artifact, by coordinate and digest. It is deliberately separate
 * from framework API numbers and from the C ABI version. The default build ships no native
 * artifact, so no instance is created unless a native profile is explicitly selected.
 */
public final class NativeArtifactIdentity {
    private final String coordinate;
    private final String sha256;

    public NativeArtifactIdentity(String coordinate, String sha256) {
        this.coordinate = Objects.requireNonNull(coordinate, "coordinate");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    public String coordinate() {
        return coordinate;
    }

    public String sha256() {
        return sha256;
    }

    @Override
    public String toString() {
        return "NativeArtifactIdentity[" + coordinate + " sha256=" + sha256 + "]";
    }
}
