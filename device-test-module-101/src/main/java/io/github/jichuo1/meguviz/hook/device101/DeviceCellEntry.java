package io.github.jichuo1.meguviz.hook.device101;

import android.os.Process;
import android.util.Log;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.backend.libxposed101.LibXposed101Backend;
import io.github.jichuo1.meguviz.hook.backend.libxposed101.LibXposed101Context;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedModule;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated, opt-in device cell. Never hooks packages other than its controlled test host. */
public final class DeviceCellEntry extends XposedModule {
    private static final String TAG = "MeguvizHookCell";
    private static final String HOST = "io.github.jichuo1.meguviz.hook.devicehost";
    private static final String TARGET = HOST + ".ProbeTarget";
    private static final String BACKEND = "101";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private final String runId = UUID.randomUUID().toString();
    private final AtomicInteger oldCallbacks = new AtomicInteger();
    private final AtomicInteger newCallbacks = new AtomicInteger();
    private String step = "run";
    private HookEnvironment environment;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!HOST.equals(param.getPackageName()) || !STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            // getApiVersion may be the API library's default constant, not a runtime probe.
            String info = ",\"framework_name\":" + quote(String.valueOf(getFrameworkName()))
                    + ",\"framework_version\":" + quote(String.valueOf(getFrameworkVersion()))
                    + ",\"reported_api\":" + getApiVersion() + ",\"probe_version\":2";
            event("start", "start", "run", "", info);
            Class<?> targetClass = Class.forName(TARGET, false, param.getClassLoader());
            Method target = targetClass.getMethod("add", int.class, int.class);
            Method call = targetClass.getMethod("callAdd");
            Method count = targetClass.getMethod("calls");
            Method reset = targetClass.getMethod("reset");
            LibXposed101Backend backend = new LibXposed101Backend(this,
                    new LibXposed101Context(param.getApplicationInfo().processName,
                            param.getClassLoader(), 1L));
            environment = HookEnvironment.create(backend);

            step = "baseline";
            checkCall(reset, call, count, 5, 1, 0, 0);

            step = "install";
            RegistrationReceipt installed = environment.install(spec(target, 10));
            check(installed.isInstalled() && installed.registration() != null,
                    "status=" + installed.status());
            HookRegistration old = installed.registration();

            step = "installed_call";
            checkCall(reset, call, count, 10, 0, 1, 0);

            step = "replace";
            RegistrationReceipt replacement = environment.replace(spec(target, 20));
            check(replacement.isInstalled() && replacement.registration() != null
                    && replacement.generation() > old.generation(), "status=" + replacement.status());
            HookRegistration current = replacement.registration();

            step = "replaced_call";
            checkCall(reset, call, count, 20, 0, 1, 1);

            step = "old_close";
            CloseReport superseded = old.close();
            check(superseded.newCallbacksRejected()
                    && superseded.entryRestoration() == CloseReport.EntryRestoration.NOT_RESTORED,
                    "entry=" + superseded.entryRestoration());

            step = "after_old_close";
            checkCall(reset, call, count, 20, 0, 1, 2);

            step = "close";
            CloseReport report = current.close();
            check(report.newCallbacksRejected() && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED,
                    "entry=" + report.entryRestoration());
            step = "restored_call";
            checkCall(reset, call, count, 5, 1, 1, 2);
            environment.close();
            environment = null;
            event("end", "pass", "run", "", "");
        } catch (Throwable failure) {
            event("check", "fail", step, failure instanceof CheckFailure
                    ? failure.getMessage() : failure.getClass().getName(), "");
            if (environment != null) {
                try {
                    environment.close();
                } catch (Throwable cleanup) {
                    event("check", "fail", "cleanup", cleanup.getClass().getName(), "");
                    if (cleanup instanceof Error) {
                        event("end", "fail", "run", "", "");
                        throw (Error) cleanup;
                    }
                }
            }
            event("end", "fail", "run", "", "");
            if (failure instanceof Error) throw (Error) failure;
        }
    }

    private HookSpec spec(Method target, int early) {
        AtomicInteger callbacks = early == 10 ? oldCallbacks : newCallbacks;
        return new HookSpec.Builder(HookId.of("device-cell.add"), HookOwner.of("device-cell-101"),
                TargetSpec.ofMember(target)).before(invocation -> {
                    callbacks.incrementAndGet();
                    invocation.setEarlyResult(early);
                }).build();
    }

    private void checkCall(Method reset, Method call, Method count, int expected, int expectedCalls,
                           int expectedOld, int expectedNew) throws Exception {
        reset.invoke(null);
        int value = number(call);
        int calls = number(count);
        int oldCount = oldCallbacks.get();
        int newCount = newCallbacks.get();
        check(value == expected && calls == expectedCalls && oldCount == expectedOld && newCount == expectedNew,
                "value=" + value + ",calls=" + calls + ",expected=" + expected + ",expected_calls=" + expectedCalls
                        + ",old_callbacks=" + oldCount + ",new_callbacks=" + newCount
                        + ",expected_old_callbacks=" + expectedOld + ",expected_new_callbacks=" + expectedNew);
    }

    private static final class CheckFailure extends RuntimeException {
        CheckFailure(String detail) { super(detail); }
    }

    private static int number(Method method) throws Exception {
        return ((Number) method.invoke(null)).intValue();
    }

    private void check(boolean ok, String detail) {
        if (!ok) throw new CheckFailure(detail);
        event("check", "pass", step, detail, "");
    }

    private void event(String kind, String status, String name, String detail, String extra) {
        String line = "{\"schema\":1,\"run_id\":" + quote(runId)
                + ",\"pid\":" + Process.myPid() + ",\"backend\":" + quote(BACKEND)
                + ",\"event\":" + quote(kind) + ",\"status\":" + quote(status)
                + ",\"name\":" + quote(name) + ",\"detail\":" + quote(detail) + extra + "}";
        Log.i(TAG, line);
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() > 160) safe = safe.substring(0, 160);
        return JSONObject.quote(safe);
    }
}
