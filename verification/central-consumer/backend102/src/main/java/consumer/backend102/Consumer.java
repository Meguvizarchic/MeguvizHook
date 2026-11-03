package consumer.backend102;

import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.libxposed.api.XposedInterface;

/** Compile-time consumer only; never launched on a device. */
public final class Consumer {
    public static HookBackend create(XposedInterface framework, BackendContext context) {
        return new LibXposed102Backend(framework, context);
    }
}
