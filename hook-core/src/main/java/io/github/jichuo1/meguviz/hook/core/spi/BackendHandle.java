package io.github.jichuo1.meguviz.hook.core.spi;

/** Handle of one externally installed hook. */
public interface BackendHandle {
    /**
     * Removes the external registration. Must be idempotent and must not wait indefinitely for
     * in-flight host calls. Reports only what the backend can actually claim.
     */
    BackendCloseReport uninstall();
}
