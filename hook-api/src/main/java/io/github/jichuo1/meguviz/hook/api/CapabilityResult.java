package io.github.jichuo1.meguviz.hook.api;

import java.util.Objects;

/**
 * Capability answer with its evidence scope. {@link Status#UNKNOWN} is never treated as supported,
 * and {@link Status#SUPPORTED_IN_DECLARED_CELL} only covers the declared cell, not a device run.
 */
public final class CapabilityResult {
    public enum Status {
        SUPPORTED_IN_DECLARED_CELL,
        UNSUPPORTED,
        UNKNOWN,
        TEMPORARILY_UNAVAILABLE
    }

    private final Status status;
    private final String namespace;
    private final String versionHint;
    private final String declaredCell;
    private final String detail;

    private CapabilityResult(Status status, String namespace, String versionHint, String declaredCell, String detail) {
        this.status = status;
        this.namespace = namespace;
        this.versionHint = versionHint;
        this.declaredCell = declaredCell;
        this.detail = detail;
    }

    public static CapabilityResult of(Status status, String namespace, String versionHint, String declaredCell, String detail) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(namespace, "namespace");
        return new CapabilityResult(status, namespace, versionHint, declaredCell, detail);
    }

    public static CapabilityResult unknown(String namespace, String detail) {
        return of(Status.UNKNOWN, namespace, null, null, detail);
    }

    public static CapabilityResult unsupported(String namespace, String detail) {
        return of(Status.UNSUPPORTED, namespace, null, null, detail);
    }

    public Status status() {
        return status;
    }

    public String namespace() {
        return namespace;
    }

    public String versionHint() {
        return versionHint;
    }

    public String declaredCell() {
        return declaredCell;
    }

    public String detail() {
        return detail;
    }

    public boolean isSupportedInDeclaredCell() {
        return status == Status.SUPPORTED_IN_DECLARED_CELL;
    }

    @Override
    public String toString() {
        return namespace + ":" + status + (declaredCell == null ? "" : "[" + declaredCell + "]")
                + (detail == null ? "" : " " + detail);
    }
}
