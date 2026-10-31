package io.github.jichuo1.meguviz.hook.sample102;

import android.util.Log;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual Java entry for the libxposed 102 sample. No Kotlin DSL, KSP, service, native artifact or
 * hot-reload session is involved: the consumer wires the backend and the core by hand.
 *
 * <p>The entry class is named in {@code META-INF/xposed/java_init.list} and instantiated by the
 * framework. The sample hooks two side-effect-free framework targets in the scoped host only:
 * <ul>
 *   <li>{@code SystemClock.elapsedRealtime} with NO_ARGS (the SDK never materializes arguments),</li>
 *   <li>{@code TextUtils.isEmpty} with READ_ARGS (read-only observation, result untouched).</li>
 * </ul>
 * Loading of this sample on a real 102 runtime has NOT been verified in this slice; see
 * work/hook/release_artifact_audit.json for the exact verification state.
 */
public final class SampleHookEntry extends XposedModule {
    private static final String TAG = "MeguvizHookSample";
    private static final String EXPECTED_PACKAGE = "com.example.host";

    private final AtomicLong clockCalls = new AtomicLong();
    private HookEnvironment environment;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!EXPECTED_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        LibXposed102Backend backend = new LibXposed102Backend(this, new LibXposed102Context(
                param.getApplicationInfo().processName, param.getClassLoader(), 1L));
        environment = HookEnvironment.create(backend);
        installClockCounter();
        installReadOnlyTextUtilsObserver();
    }

    private void installClockCounter() {
        try {
            Method target = SystemClockHolder.elapsedRealtime();
            RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                            HookId.of("sample.systemclock.elapsed-realtime"),
                            HookOwner.of("meguvizhook-sample-102"), TargetSpec.ofMember(target))
                    .access(ParameterAccess.NO_ARGS)
                    .before(invocation -> clockCalls.incrementAndGet())
                    .build());
            logReceipt(receipt);
        } catch (NoSuchMethodException error) {
            Log.w(TAG, "clock target unavailable", error);
        }
    }

    private void installReadOnlyTextUtilsObserver() {
        try {
            Method target = TextUtilsHolder.isEmpty();
            RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                            HookId.of("sample.textutils.is-empty"),
                            HookOwner.of("meguvizhook-sample-102"), TargetSpec.ofMember(target))
                    .access(ParameterAccess.READ_ARGS)
                    .before(invocation -> {
                        // Read-only: the result and the argument are deliberately left unchanged.
                        ReadArgsInvocation args = (ReadArgsInvocation) invocation;
                        if (args.argumentCount() != 1) {
                            throw new IllegalStateException("unexpected TextUtils.isEmpty signature");
                        }
                    })
                    .build());
            logReceipt(receipt);
        } catch (NoSuchMethodException error) {
            Log.w(TAG, "text utils target unavailable", error);
        }
    }

    private void logReceipt(RegistrationReceipt receipt) {
        Log.i(TAG, "install: " + receipt);
    }

    long observedClockCalls() {
        return clockCalls.get();
    }

    /** Reflection isolated so R8 keeps the referenced framework members obvious. */
    private static final class SystemClockHolder {
        private SystemClockHolder() {
        }

        static Method elapsedRealtime() throws NoSuchMethodException {
            return android.os.SystemClock.class.getMethod("elapsedRealtime");
        }
    }

    private static final class TextUtilsHolder {
        private TextUtilsHolder() {
        }

        static Method isEmpty() throws NoSuchMethodException {
            return android.text.TextUtils.class.getMethod("isEmpty", CharSequence.class);
        }
    }
}
