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
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal hand-wired Java API 101 module. No 102-only API, Kotlin DSL, KSP, service, native artifact
 * or hot reload is referenced. Replace the placeholder scope before any real-device experiment.
 */
public final class SampleHookEntry extends XposedModule {
    private static final String TAG = "MeguvizHookSample101";
    private static final String EXPECTED_PACKAGE = "com.example.host";

    private final AtomicLong clockCalls = new AtomicLong();
    private volatile String processName;
    private HookEnvironment environment;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!EXPECTED_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        String currentProcess = processName;
        if (currentProcess == null) {
            Log.w(TAG, "module-loaded process name unavailable");
            return;
        }
        LibXposed101Backend backend = new LibXposed101Backend(this, new LibXposed101Context(
                currentProcess, param.getClassLoader(), 1L));
        environment = HookEnvironment.create(backend);
        installClockCounter();
    }

    private void installClockCounter() {
        try {
            Method target = SystemClockHolder.elapsedRealtime();
            RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                            HookId.of("sample101.systemclock.elapsed-realtime"),
                            HookOwner.of("meguvizhook-sample-101"), TargetSpec.ofMember(target))
                    .access(ParameterAccess.NO_ARGS)
                    .before(invocation -> clockCalls.incrementAndGet())
                    .build());
            Log.i(TAG, "install: " + receipt);
        } catch (NoSuchMethodException error) {
            Log.w(TAG, "clock target unavailable", error);
        }
    }

    long observedClockCalls() {
        return clockCalls.get();
    }

    private static final class SystemClockHolder {
        private SystemClockHolder() {
        }

        static Method elapsedRealtime() throws NoSuchMethodException {
            return android.os.SystemClock.class.getMethod("elapsedRealtime");
        }
    }
}
