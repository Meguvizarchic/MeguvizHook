package io.github.jichuo1.meguviz.hook.core.spi;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;

/**
 * Backend SPI implemented by framework adapters and by the test fake. Real interception is always
 * performed by the selected framework; this SDK never replaces the manager, injector or ART.
 *
 * <p>Implementations must be constructed with everything they need; {@link #install} is called at
 * install time only, and the returned channel is the only hot-path entry into the core.
 */
public interface HookBackend {
    String id();

    BackendCapabilities capabilities();

    BackendContext context();

    /**
     * Installs one bound plan. Implementations must map the core's propagate policy onto the real
     * framework result/throwable/continuation channel; if that is impossible they must throw
     * {@link BackendInstallException} instead of silently degrading. That checked exception must
     * only be thrown when no external entry was installed by this call. An unchecked exception or
     * error leaves the outcome unknown; the core quarantines the logical ID and rethrows it.
     * A backend may declare FRAMEWORK_PROTECTIVE only when it delegates the requested mode to the
     * framework; unsupported policies must fail closed rather than silently using PROPAGATE.
     */
    BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException;
}
