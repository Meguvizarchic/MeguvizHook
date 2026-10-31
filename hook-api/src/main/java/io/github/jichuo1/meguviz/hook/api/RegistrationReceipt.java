package io.github.jichuo1.meguviz.hook.api;

/**
 * Outcome of an install request. More precise than a boolean: capability refusal, ambiguity,
 * missing target, stale identity, process pruning and conflict are distinguishable, and no
 * registration handle is published unless installation really happened.
 */
public final class RegistrationReceipt {
    public enum Status {
        INSTALLED,
        ALREADY_INSTALLED,
        CONFLICT,
        UNSUPPORTED_CAPABILITY,
        AMBIGUOUS_TARGET,
        TARGET_MISSING,
        STALE_IDENTITY,
        FILTERED,
        TEMPORARILY_UNAVAILABLE,
        FAILED
    }

    private final HookId id;
    private final Status status;
    private final String backendId;
    private final long generation;
    private final String message;
    private final HookRegistration registration;

    private RegistrationReceipt(HookId id, Status status, String backendId, long generation,
                                String message, HookRegistration registration) {
        this.id = id;
        this.status = status;
        this.backendId = backendId;
        this.generation = generation;
        this.message = message;
        this.registration = registration;
    }

    public static RegistrationReceipt of(HookId id, Status status, String backendId, long generation, String message) {
        return new RegistrationReceipt(id, status, backendId, generation, message, null);
    }

    public static RegistrationReceipt installed(HookRegistration registration, String backendId, String message) {
        return new RegistrationReceipt(registration.id(), Status.INSTALLED, backendId,
                registration.generation(), message, registration);
    }

    public HookId id() {
        return id;
    }

    public Status status() {
        return status;
    }

    public String backendId() {
        return backendId;
    }

    public long generation() {
        return generation;
    }

    public String message() {
        return message;
    }

    /** Non-null only for {@link Status#INSTALLED}. */
    public HookRegistration registration() {
        return registration;
    }

    public boolean isInstalled() {
        return status == Status.INSTALLED;
    }

    @Override
    public String toString() {
        return "RegistrationReceipt[" + id + " " + status + " backend=" + backendId
                + " generation=" + generation + (message == null ? "" : " " + message) + "]";
    }
}
