package consumer.backend101;

import io.github.jichuo1.meguviz.hook.backend.libxposed101.LibXposed101Backend;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.libxposed.api.XposedInterface;

/** Compile-time consumer only; never launched on a device. */
public final class Consumer {
    public static HookBackend create(XposedInterface framework, BackendContext context) {
        return new LibXposed101Backend(framework, context);
    }
}
