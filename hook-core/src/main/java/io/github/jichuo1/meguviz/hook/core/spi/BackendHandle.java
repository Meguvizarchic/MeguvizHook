package io.github.jichuo1.meguviz.hook.core.spi;

import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;

/** Handle of one externally installed hook. */
@FunctionalInterface
public interface BackendHandle {
    /**
     * Removes the external registration. Must be idempotent and must not wait indefinitely for
     * in-flight host calls. Reports only what the backend can actually claim.
     */
    BackendCloseReport uninstall();

    /**
     * Atomically switches this handle's single-target hook to a new frozen plan/channel, either via
     * a framework replacement or inside one retained framework entry. Returning a
     * non-null handle commits the operation and transfers uninstall ownership. A
     * {@link BackendInstallException} guarantees no replacement was committed and this handle remains
     * valid. An unchecked exception, Error, or null return has an unknown outcome; the core rejects
     * both callback channels and quarantines the ID in its environment without attempting unhook.
     * Implementations must not simulate replacement with uninstall+install or wait for in-flight callbacks.
     */
    default BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
        throw new BackendInstallException("atomic replacement is not supported by this backend handle");
    }
}
