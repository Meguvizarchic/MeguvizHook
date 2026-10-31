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
     * Atomically replaces this handle's external hook with a new frozen plan/channel. Returning a
     * non-null handle commits the operation and transfers uninstall ownership. Any exception means
     * no replacement was committed and this handle remains valid. Implementations must not simulate
     * replacement with uninstall+install or wait for in-flight callbacks.
     */
    default BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
        throw new BackendInstallException("atomic replacement is not supported by this backend handle");
    }
}
