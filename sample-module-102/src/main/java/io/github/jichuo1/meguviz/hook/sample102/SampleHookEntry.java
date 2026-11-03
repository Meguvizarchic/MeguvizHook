package io.github.jichuo1.meguviz.hook.sample102;

import android.os.Bundle;
import android.util.Log;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102ReloadSession;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual Java entry for the libxposed 102 sample. The module wires its backend and core directly;
 * its API102 reload lifecycle remains opt-in through the framework and module metadata.
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
    private static final int CLOCK = 1;
    private static final int TEXT = 2;
    private static final int STATE_VERSION = 1;
    private static final String STATE_SCHEMA = "meguviz.sample.schema";
    private static final String STATE_PROCESS = "meguviz.sample.process";
    private static final String STATE_EPOCH = "meguviz.sample.epoch";
    private static final String STATE_MASK = "meguviz.sample.mask";

    private final AtomicLong clockCalls = new AtomicLong();
    private HookEnvironment environment;
    private String processName;
    private long epoch = 1L;
    private int installedMask;
    private boolean reloadReady;

    @Override
    public synchronized void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public synchronized void onPackageReady(PackageReadyParam param) {
        if (!EXPECTED_PACKAGE.equals(param.getPackageName()) || environment != null || processName == null) {
            return;
        }
        LibXposed102Backend backend = new LibXposed102Backend(this, new LibXposed102Context(
                processName, frameworkTargetLoader(), epoch));
        environment = HookEnvironment.create(backend);
        boolean clockInstalled = installClockCounter();
        boolean textInstalled = installReadOnlyTextUtilsObserver();
        reloadReady = clockInstalled && textInstalled;
    }

    private boolean installClockCounter() {
        try {
            Method target = SystemClockHolder.elapsedRealtime();
            RegistrationReceipt receipt = environment.install(clockSpec(target));
            if (receipt.isInstalled()) installedMask |= CLOCK;
            logReceipt(receipt);
            return receipt.isInstalled();
        } catch (NoSuchMethodException error) {
            Log.w(TAG, "clock target unavailable", error);
            return false;
        }
    }

    private boolean installReadOnlyTextUtilsObserver() {
        try {
            Method target = TextUtilsHolder.isEmpty();
            RegistrationReceipt receipt = environment.install(textSpec(target));
            if (receipt.isInstalled()) installedMask |= TEXT;
            logReceipt(receipt);
            return receipt.isInstalled();
        } catch (NoSuchMethodException error) {
            Log.w(TAG, "text utils target unavailable", error);
            return false;
        }
    }

    /** This sample owns no threads, native hooks, JNI references or external callbacks. */
    @Override
    public synchronized boolean onHotReloading(HotReloadingParam param) {
        if (!reloadReady || environment == null || installedMask == 0 || epoch == Long.MAX_VALUE) {
            return false;
        }
        Bundle state = new Bundle();
        state.putInt(STATE_SCHEMA, STATE_VERSION);
        state.putString(STATE_PROCESS, processName);
        state.putLong(STATE_EPOCH, epoch);
        state.putInt(STATE_MASK, installedMask);
        // Leave the old environment and its handles intact for framework handover.
        param.setSavedInstanceState(state);
        return true;
    }

    @Override
    public synchronized void onHotReloaded(HotReloadedParam param) {
        if (environment != null) {
            Log.w(TAG, "reload rejected: module generation already initialized");
            return;
        }
        Object saved = param.getSavedInstanceState();
        if (!(saved instanceof Bundle)) {
            Log.w(TAG, "reload rejected: missing neutral state; old hooks left untouched");
            return;
        }
        Bundle state = (Bundle) saved;
        int mask = state.getInt(STATE_MASK, 0);
        long oldEpoch = state.getLong(STATE_EPOCH, 0L);
        if (state.getInt(STATE_SCHEMA, 0) != STATE_VERSION || oldEpoch <= 0L
                || oldEpoch == Long.MAX_VALUE || mask == 0 || (mask & ~(CLOCK | TEXT)) != 0
                || !param.getProcessName().equals(state.getString(STATE_PROCESS))) {
            Log.w(TAG, "reload rejected: invalid state; old hooks left untouched");
            return;
        }
        try {
            Method clock = SystemClockHolder.elapsedRealtime();
            Method text = TextUtilsHolder.isEmpty();
            processName = param.getProcessName();
            epoch = oldEpoch + 1L;
            LibXposed102Backend backend = new LibXposed102Backend(this, new LibXposed102Context(
                    processName, frameworkTargetLoader(), epoch));
            environment = HookEnvironment.create(backend);
            // Keep environment reachable even on a post-commit failure so quarantine is retained.
            try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, param)) {
                List<XposedInterface.HookHandle> old = session.oldHandles();
                XposedInterface.HookHandle clockHandle = null;
                XposedInterface.HookHandle textHandle = null;
                // The sample is the only installer: each known target has at most one entry.
                // Reject the whole mapping before mutation on duplicates or unexpected handles.
                for (XposedInterface.HookHandle handle : old) {
                    if ((mask & CLOCK) != 0 && clock.equals(handle.getExecutable()) && clockHandle == null) {
                        clockHandle = handle;
                    } else if ((mask & TEXT) != 0 && text.equals(handle.getExecutable()) && textHandle == null) {
                        textHandle = handle;
                    } else {
                        Log.w(TAG, "reload rejected: ambiguous or unexpected old handle; hooks left untouched");
                        return;
                    }
                }
                if (((mask & CLOCK) != 0) != (clockHandle != null)
                        || ((mask & TEXT) != 0) != (textHandle != null)) {
                    Log.w(TAG, "reload rejected: missing old handle; hooks left untouched");
                    return;
                }
                if (clockHandle != null) {
                    RegistrationReceipt receipt = environment.adopt(clockSpec(clock), session.adoption(clockHandle));
                    logReceipt(receipt);
                    if (!receipt.isInstalled()) return;
                    installedMask |= CLOCK;
                }
                if (textHandle != null) {
                    RegistrationReceipt receipt = environment.adopt(textSpec(text), session.adoption(textHandle));
                    logReceipt(receipt);
                    if (!receipt.isInstalled()) return;
                    installedMask |= TEXT;
                }
                reloadReady = installedMask == mask;
            }
        } catch (NoSuchMethodException | RuntimeException error) {
            // A replacement may already have committed: never unhook or retry an uncertain handle.
            reloadReady = false;
            Log.w(TAG, "reload incomplete; retained hooks require explicit recovery", error);
        }
    }

    private static ClassLoader frameworkTargetLoader() {
        // These two member targets are Android framework classes, resolvable by the system loader.
        // An app-specific target must instead use the actual host loader.
        return ClassLoader.getSystemClassLoader();
    }

    private HookSpec clockSpec(Method target) {
        return new HookSpec.Builder(HookId.of("sample.systemclock.elapsed-realtime"),
                HookOwner.of("meguvizhook-sample-102"), TargetSpec.ofMember(target))
                .access(ParameterAccess.NO_ARGS)
                .before(invocation -> clockCalls.incrementAndGet())
                .build();
    }

    private HookSpec textSpec(Method target) {
        return new HookSpec.Builder(HookId.of("sample.textutils.is-empty"),
                HookOwner.of("meguvizhook-sample-102"), TargetSpec.ofMember(target))
                .access(ParameterAccess.READ_ARGS)
                .beforeArgs(args -> {
                    if (args.argumentCount() != 1) {
                        throw new IllegalStateException("unexpected TextUtils.isEmpty signature");
                    }
                })
                .build();
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
