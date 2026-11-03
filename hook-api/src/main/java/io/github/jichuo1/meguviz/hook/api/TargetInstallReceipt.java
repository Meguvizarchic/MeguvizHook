package io.github.jichuo1.meguviz.hook.api;

import java.lang.reflect.Executable;
import java.util.Objects;

/**
 * Install outcome for one resolved target. {@code MatchPolicy.ALL} uses a list of these receipts;
 * there is no cross-target atomicity claim. {@link Status#ROLLED_BACK} means an installed entry was
 * subsequently closed because the loader or code epoch changed before the logical registration was
 * published. This records the close attempt; see the receipt message for the aggregate entry
 * restoration result, which may be unconfirmed.
 */
public final class TargetInstallReceipt {
    public enum Status {
        INSTALLED,
        FAILED,
        ROLLED_BACK
    }

    private final Executable target;
    private final Status status;
    private final String message;

    private TargetInstallReceipt(Executable target, Status status, String message) {
        this.target = Objects.requireNonNull(target, "target");
        this.status = Objects.requireNonNull(status, "status");
        this.message = message;
    }

    public static TargetInstallReceipt installed(Executable target) {
        return new TargetInstallReceipt(target, Status.INSTALLED, "installed");
    }

    public static TargetInstallReceipt failed(Executable target, String message) {
        return new TargetInstallReceipt(target, Status.FAILED, message);
    }

    public static TargetInstallReceipt rolledBack(Executable target, String message) {
        return new TargetInstallReceipt(target, Status.ROLLED_BACK, message);
    }

    /** The loader-bound reflective identity resolved for this attempt. */
    public Executable target() {
        return target;
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "TargetInstallReceipt[" + targetDescription() + " " + status
                + (message == null ? "" : " " + message) + "]";
    }

    @SuppressWarnings("removal")
    private String targetDescription() {
        try {
            return target.toGenericString();
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
                throw (Error) failure;
            }
            return target.getDeclaringClass().getName() + "#" + target.getName();
        }
    }
}
