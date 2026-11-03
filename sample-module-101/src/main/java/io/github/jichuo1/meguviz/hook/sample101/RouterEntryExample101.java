package io.github.jichuo1.meguviz.hook.sample101;

import android.util.Log;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.backend.libxposed101.LibXposed101Backend;
import io.github.jichuo1.meguviz.hook.backend.libxposed101.LibXposed101Context;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.ModuleEntryRouter;
import io.github.jichuo1.meguviz.hook.core.ModuleLifecycle;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/** Optional entry example; not registered in the sample's metadata or enabled automatically. */
public final class RouterEntryExample101 extends XposedModule {
    private static final String TAG = "RouterExample101";
    private static final String TARGET = "com.example.host";
    private final AtomicLong calls = new AtomicLong();
    private volatile ModuleEntryRouter router;

    @Override public synchronized void onModuleLoaded(ModuleLoadedParam param) {
        if (router != null) throw new IllegalStateException("module entry already initialized");
        ModuleLifecycle lifecycle = new ModuleLifecycle(32);
        router = new ModuleEntryRouter(param.getProcessName(), 1L, 16, lifecycle,
                info -> TARGET.equals(info.packageName()),
                ModuleEntryRouter.fromBackend(info -> new LibXposed101Backend(this,
                        new LibXposed101Context(info.processName(), info.classLoader(), info.epoch())),
                        new ReflectiveMemberResolver(), DiagnosticsSink.NONE),
                context -> {
                    Method target = android.os.SystemClock.class.getMethod("elapsedRealtime");
                    RegistrationReceipt receipt = context.environment().install(new HookSpec.Builder(
                            HookId.of("router101.elapsed"), HookOwner.of("router101"), TargetSpec.ofMember(target))
                            .access(ParameterAccess.NO_ARGS).before(invocation -> calls.incrementAndGet()).build());
                    if (!receipt.isInstalled()) throw new IllegalStateException("hook installation not complete: " + receipt.status());
                });
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        ModuleEntryRouter current = router;
        if (current == null) { Log.w(TAG, "module-loaded event missing"); return; }
        try {
            ModuleEntryRouter.Result result = current.onPackageReady(param.getPackageName(), param.getClassLoader());
            Log.i(TAG, "package entry: " + result.status());
        } catch (Exception failure) {
            // The route remains failed; it may have installed a hook before the exception.
            Log.w(TAG, "package entry failed; explicit recovery required", failure);
        }
    }
}
