package io.github.jichuo1.meguviz.hook.core.spi;

import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;

/**
 * A backend-owned, single-use bridge to one hook delivered to a new module generation by the
 * framework. The bridge must validate its framework callback lifetime, handle provenance and
 * target before attempting replacement. It must not retain an old SDK registration or plan.
 *
 * <p>A non-null result commits a single atomic replacement and transfers uninstall ownership to
 * the returned handle. A checked failure guarantees no commit or mutation of the old handle. An
 * unchecked failure, error or null result leaves the external outcome unknown. Implementations
 * must not emulate this operation with uninstall followed by install.
 */
public interface BackendAdoption {
    /** The exact backend instance that created and owns this bridge. */
    HookBackend backend();

    BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException;
}
