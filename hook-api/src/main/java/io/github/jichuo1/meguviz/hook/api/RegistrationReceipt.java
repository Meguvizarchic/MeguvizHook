package io.github.jichuo1.meguviz.hook.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of an install request. More precise than a boolean: capability refusal, ambiguity,
 * missing target, stale identity, process pruning and conflict are distinguishable.
 *
 * <p>For {@code MatchPolicy.ALL}, {@link #targets()} contains one result per resolved member.
 * {@link Status#PARTIALLY_INSTALLED} is deliberately distinct from full success and still carries
 * a registration so the caller can retire the entries that were installed. No cross-target
 * atomicity is promised.
 */
public final class RegistrationReceipt {
    public enum Status {
        INSTALLED,
        PARTIALLY_INSTALLED,
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
    private final List<TargetInstallReceipt> targets;

    private RegistrationReceipt(HookId id, Status status, String backendId, long generation,
                                String message, HookRegistration registration,
                                List<TargetInstallReceipt> targets) {
        this.id = id;
        this.status = status;
        this.backendId = backendId;
        this.generation = generation;
        this.message = message;
        this.registration = registration;
        this.targets = Collections.unmodifiableList(new ArrayList<TargetInstallReceipt>(targets));
    }

    public static RegistrationReceipt of(HookId id, Status status, String backendId, long generation, String message) {
        return new RegistrationReceipt(id, status, backendId, generation, message, null,
                Collections.<TargetInstallReceipt>emptyList());
    }

    public static RegistrationReceipt withTargets(HookId id, Status status, String backendId, long generation,
                                                   String message, HookRegistration registration,
                                                   List<TargetInstallReceipt> targets) {
        if (registration != null && status != Status.INSTALLED && status != Status.PARTIALLY_INSTALLED) {
            throw new IllegalArgumentException("a registration can only be published for an installed outcome");
        }
        return new RegistrationReceipt(id, status, backendId, generation, message, registration, targets);
    }

    public static RegistrationReceipt installed(HookRegistration registration, String backendId, String message) {
        return new RegistrationReceipt(registration.id(), Status.INSTALLED, backendId,
                registration.generation(), message, registration, Collections.<TargetInstallReceipt>emptyList());
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

    /** Non-null for {@link Status#INSTALLED} and {@link Status#PARTIALLY_INSTALLED}. */
    public HookRegistration registration() {
        return registration;
    }

    /** Immutable per-target outcomes; empty when no backend install was attempted. */
    public List<TargetInstallReceipt> targets() {
        return targets;
    }

    /** True only when every selected target was installed; partial success remains false. */
    public boolean isInstalled() {
        return status == Status.INSTALLED;
    }

    public boolean isPartiallyInstalled() {
        return status == Status.PARTIALLY_INSTALLED;
    }

    @Override
    public String toString() {
        return "RegistrationReceipt[" + id + " " + status + " backend=" + backendId
                + " generation=" + generation + (message == null ? "" : " " + message)
                + (targets.isEmpty() ? "" : " targets=" + targets) + "]";
    }
}
