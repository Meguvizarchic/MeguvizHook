package io.github.jichuo1.meguviz.hook.benchmark102;

import android.os.Process;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.ProcessFilter;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.AroundCallback;
import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.EntrySnapshotInvocation;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in B01 through B10 pilots. It never measures without an explicit host control request. */
public final class BenchmarkEntry extends XposedModule {
    private static final String HOST = "io.github.jichuo1.meguviz.hook.benchmarkhost";
    private static final String TAG = "MeguvizHookBench";
    private static final String CONTROL_TAG = "MeguvizHookBenchControl";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private final Object lock = new Object();
    private ClassLoader loader;
    private String processName;
    private boolean controlsReady;
    private boolean consumed;
    private Request activeRequest;
    private ActiveHook measurement;
    private B07Session b07Session;
    private B08Session b08Session;
    private B09Session b09Session;
    private B10Session b10Session;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!HOST.equals(param.getPackageName()) || !STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            loader = param.getClassLoader();
            processName = param.getApplicationInfo().processName;
            Class<?> controls = Class.forName(HOST + ".BenchmarkControl", false, loader);
            Method configure = controls.getMethod("configure", String.class, String.class, String.class,
                    String.class, String.class, String.class, int.class, int.class, int.class);
            Method finish = controls.getMethod("finish", String.class);
            ControlHookSetup.install(new ControlHookSetup.Steps<XposedInterface.HookHandle>() {
                @Override public XposedInterface.HookHandle installConfigure() {
                    return hook(configure).setId("benchmark.control.configure")
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> configure(chain.getArgs()));
                }

                @Override public XposedInterface.HookHandle installFinish() {
                    return hook(finish).setId("benchmark.control.finish")
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> finish(chain.getArgs()));
                }

                @Override public void ready() {
                    synchronized (lock) { controlsReady = true; }
                }

                @Override public void disarm() {
                    synchronized (lock) { controlsReady = false; }
                }

                @Override public void unhook(XposedInterface.HookHandle handle) {
                    handle.unhook();
                }
            });
        } catch (Throwable failure) {
            Log.e(CONTROL_TAG, "control_init_failed:" + failure.getClass().getName()
                    + "; unreturned hook effects may be unknown");
            if (failure instanceof Error) throw (Error) failure;
        }
    }

    private boolean configure(List<Object> arguments) {
        Request request = Request.from(arguments);
        if (request == null) return false;
        synchronized (lock) {
            if (!controlsReady || consumed) {
                event(request, "configured", "fail", ",\"detail\":\"already_used_or_unavailable\"");
                return false;
            }
            consumed = true;
            activeRequest = request;
            if ("B10".equals(request.workload)) {
                try {
                    b10Session = new B10Session(request);
                    return b10Session.configure();
                } catch (Throwable failure) {
                    if (b10Session == null) event(request, "resource_before", "fail", ",\"detail\":" + quote(failure.getClass().getName()));
                    if (failure instanceof Error) throw (Error) failure;
                    return false;
                }
            }
            if ("B09".equals(request.workload)) {
                try {
                    b09Session = new B09Session(request);
                    return b09Session.configure();
                } catch (Throwable failure) {
                    if (b09Session == null) event(request, "installed", "fail", ",\"detail\":" + quote(failure.getClass().getName()));
                    if (failure instanceof Error) throw (Error) failure;
                    return false;
                }
            }
            if ("B08".equals(request.workload)) {
                try {
                    b08Session = new B08Session(request);
                    return b08Session.configure();
                } catch (Throwable failure) {
                    if (b08Session == null) event(request, "installed", "fail", ",\"detail\":" + quote(failure.getClass().getName()));
                    if (failure instanceof Error) throw (Error) failure;
                    return false;
                }
            }
            if ("B07".equals(request.workload)) {
                try {
                    b07Session = new B07Session(request);
                    return b07Session.configure();
                } catch (Throwable failure) {
                    if (b07Session == null) {
                        event(request, "install", "fail", ",\"detail\":" + quote(failure.getClass().getName())
                                + ",\"outcome_unknown\":false");
                    }
                    if (failure instanceof Error) throw (Error) failure;
                    return false;
                }
            }
            ActiveHook diagnostic = new ActiveHook();
            String step = "preflight";
            try {
                Class<?> targets = Class.forName(HOST + (request.observed
                        ? ".ObservedBenchmarkTarget" : ".BenchmarkTarget"), false, loader);
                Method target;
                if ("B01".equals(request.workload)) {
                    target = targets.getMethod(request.observed ? "observedNoArgs" : "noArgs");
                } else if ("B02".equals(request.workload)) {
                    target = targets.getMethod(request.observed ? "observedAdd" : "add", int.class, int.class);
                } else if ("B03".equals(request.workload)) {
                    target = targets.getMethod(request.observed ? "observedSnapshot" : "snapshot",
                            int[].class, int.class);
                } else if ("B04".equals(request.workload)) {
                    target = targets.getMethod(request.observed ? "observedMutableAdd" : "mutableAdd",
                            int.class, int.class);
                } else if ("B05".equals(request.workload)) {
                    target = targets.getMethod(request.observed ? "observedAroundValue" : "aroundValue");
                } else if ("B06".equals(request.workload)) {
                    target = targets.getMethod(request.observed ? "observedReentrant" : "reentrant", int.class);
                } else {
                    throw new IllegalArgumentException("unsupported workload");
                }
                AtomicInteger calls = new AtomicInteger();
                diagnostic.install(request, target, calls);
                Object observed = invokeTarget(target, request.workload);
                long expected = request.workload.equals("B06") ? 4L : request.workload.equals("B04")
                        ? 9L : request.workload.equals("B02") ? 7L : 42L;
                int expectedHookCount = "B06".equals(request.workload) ? 4 : 1;
                if (!(observed instanceof Number) || ((Number) observed).longValue() != expected
                        || calls.get() != expectedHookCount) {
                    throw new IllegalStateException("preflight result or callback count mismatch");
                }
                CloseOutcome preflightClose = diagnostic.close();
                if (!preflightClose.restored) throw new IllegalStateException("preflight cleanup not confirmed");
                event(request, "preflight", "pass", ",\"hook_count\":" + expectedHookCount + ",\"result\":" + expected
                        + ",\"original_count\":\"unverified\",\"hook_scope\":\"separate_preflight\""
                        + ",\"measurement_hook_verified\":false,\"preflight_restoration\":\"RESTORED\"");

                step = "preflight_post_close";
                Object afterClose = invokeTarget(target, request.workload);
                long expectedAfterClose = request.workload.equals("B04") ? 7L : expected;
                if (!(afterClose instanceof Number) || ((Number) afterClose).longValue() != expectedAfterClose
                        || calls.get() != expectedHookCount) {
                    throw new IllegalStateException("preflight callback still active or result changed after close");
                }
                event(request, "preflight_post_close", "pass", ",\"result\":" + expectedAfterClose
                        + ",\"hook_count\":" + expectedHookCount + ",\"entry_restoration\":\"RESTORED\"");

                step = "configured";
                measurement = new ActiveHook();
                measurement.install(request, target, null);
                String name = getFrameworkName();
                String version = String.valueOf(getFrameworkVersion());
                if (name == null || name.trim().isEmpty() || "null".equalsIgnoreCase(name.trim())
                        || version.trim().isEmpty()
                        || "null".equalsIgnoreCase(version.trim())) {
                    throw new IllegalStateException("framework metadata unavailable");
                }
                event(request, "configured", "pass", ",\"backend\":\"102\",\"framework_name\":"
                        + quote(name) + ",\"framework_version\":" + quote(version)
                        + ",\"reported_api\":" + getApiVersion());
                return true;
            } catch (Throwable failure) {
                Throwable finalFailure = cleanupAfterFailure(failure, diagnostic, measurement);
                event(request, step, "fail", ",\"detail\":" + quote(finalFailure.getClass().getName())
                        + ",\"cleanup\":\"not_confirmed_by_event\"");
                if (finalFailure instanceof Error) throw (Error) finalFailure;
                return false;
            }
        }
    }

    private static Object invokeTarget(Method target, String workload) throws Throwable {
        try {
            if ("B01".equals(workload) || "B05".equals(workload)) return target.invoke(null);
            if ("B06".equals(workload)) return target.invoke(null, 3);
            if ("B02".equals(workload) || "B04".equals(workload)) return target.invoke(null, 3, 4);
            if (!"B03".equals(workload)) throw new IllegalArgumentException("unsupported workload");
            // This checks the target's side effect, not snapshot slot identity or isolation.
            int[] box = {0};
            Object result = target.invoke(null, box, 1);
            if (box[0] != 1) throw new IllegalStateException("snapshot target did not update box once");
            return result;
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
        }
    }

    private boolean finish(List<Object> arguments) {
        synchronized (lock) {
            if (activeRequest != null && "B10".equals(activeRequest.workload)) {
                if (arguments == null || arguments.size() != 1 || !(arguments.get(0) instanceof String)
                        || !activeRequest.runId.equals(arguments.get(0)) || b10Session == null) {
                    event(activeRequest, "cleanup", "fail", ",\"detail\":\"invalid_finish\"");
                    return false;
                }
                return b10Session.finish();
            }
            if (activeRequest != null && "B09".equals(activeRequest.workload)) {
                if (arguments == null || arguments.size() != 1 || !(arguments.get(0) instanceof String)
                        || !activeRequest.runId.equals(arguments.get(0)) || b09Session == null) {
                    event(activeRequest, "cleanup", "fail", ",\"detail\":\"invalid_finish\"");
                    return false;
                }
                return b09Session.finish();
            }
            if (activeRequest != null && "B08".equals(activeRequest.workload)) {
                if (arguments == null || arguments.size() != 1 || !(arguments.get(0) instanceof String)
                        || !activeRequest.runId.equals(arguments.get(0)) || b08Session == null) {
                    event(activeRequest, "cleanup", "fail", ",\"detail\":\"invalid_finish\"");
                    return false;
                }
                return b08Session.finish();
            }
            if (activeRequest != null && "B07".equals(activeRequest.workload)) {
                if (arguments == null || arguments.size() != 1 || !(arguments.get(0) instanceof String)
                        || !activeRequest.runId.equals(arguments.get(0)) || b07Session == null) {
                    event(activeRequest, "cleanup", "fail", ",\"detail\":\"invalid_finish\"");
                    return false;
                }
                return b07Session.finish();
            }
            if (arguments == null || arguments.size() != 1 || !(arguments.get(0) instanceof String)
                    || activeRequest == null || !activeRequest.runId.equals(arguments.get(0))
                    || measurement == null) {
                if (activeRequest != null) {
                    event(activeRequest, "cleanup", "fail", ",\"detail\":\"invalid_finish\"");
                }
                return false;
            }
            try {
                CloseOutcome result = measurement.close();
                if (!result.restored) {
                    event(activeRequest, "cleanup", "fail", ",\"entry_restoration\":\"UNKNOWN\"");
                    return false;
                }
                event(activeRequest, "cleanup", "pass", ",\"entry_restoration\":\"RESTORED\""
                        + ",\"evidence_source\":" + quote(result.source)
                        + ",\"physical_release\":\"NOT_CLAIMED\"");
                measurement = null;
                return true;
            } catch (Throwable failure) {
                event(activeRequest, "cleanup", "fail", ",\"detail\":" + quote(failure.getClass().getName()));
                if (failure instanceof Error) throw (Error) failure;
                return false;
            }
        }
    }



    /** B10 callbacks deliberately only count, without inspecting arguments or results. */
    static final class B10Callbacks implements BeforeCallback, AfterCallback {
        private final AtomicLong before = new AtomicLong(), after = new AtomicLong();
        void before() { before.incrementAndGet(); }
        void after() { after.incrementAndGet(); }
        @Override public void before(HookInvocation invocation) { before(); }
        @Override public void after(HookInvocation invocation, InvocationResult result) { after(); }
        Object intercept(XposedInterface.Chain chain) throws Throwable {
            before();
            Object result;
            try { result = chain.proceed(); }
            catch (Throwable failure) {
                if (!isFatal(failure)) after();
                throw failure;
            }
            after();
            return result;
        }
        B10Runner.State state(long original) { return new B10Runner.State(before.get(), after.get(), original); }
    }

    /** The session intentionally retains its handles and callbacks through resource_closed. */
    private final class B10Session {
        private final Request request;
        private final B10Callbacks callbacks = new B10Callbacks();
        private Method target, countMethod;
        private B10Resources resources;
        private XposedInterface.HookHandle raw;
        private HookEnvironment environment;
        private HookRegistration registration;
        private RegistrationReceipt receipt;
        private String frameworkName, frameworkVersion;
        private long reportedApi;
        private final B10Runner runner;

        B10Session(Request request) {
            this.request = request;
            runner = new B10Runner(request.control, new B10Runner.Steps() {
                @Override public void prepare() throws Throwable {
                    Class<?> type = Class.forName(HOST + ".B10Target", false, loader);
                    if (type.getClassLoader() != loader) throw new IllegalStateException("B10 target loader mismatch");
                    Method prepare = type.getMethod("prepare");
                    target = type.getMethod("call"); countMethod = type.getMethod("originalCount");
                    b10ValidateControls(type, prepare, target, countMethod);
                    b09InvokeStatic(prepare);
                    resources = new B10Resources(Process.myPid(), new B10Resources.ProcSource(), SystemClock::elapsedRealtimeNanos);
                }
                @Override public B10Resources.Snapshot resources() { return resources.read(); }
                @Override public void install() {
                    if ("A0".equals(request.control)) {
                        raw = hook(target).setPriority(HookSpec.DEFAULT_PRIORITY)
                                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                                .intercept(chain -> callbacks.intercept(chain));
                        if (raw == null) throw new IllegalStateException("B10 framework returned no handle");
                    } else {
                        environment = HookEnvironment.create(new LibXposed102Backend(BenchmarkEntry.this,
                                new LibXposed102Context(processName, loader, 1L)));
                        HookSpec spec = new HookSpec.Builder(HookId.of("benchmark.resources.b10"),
                                HookOwner.of("benchmark-resources-102"), TargetSpec.ofMember(target))
                                .access(ParameterAccess.NO_ARGS).errorPolicy(ErrorPolicy.PROPAGATE)
                                .processes(ProcessFilter.only(processName)).require(Capability.FRAMEWORK_CHAIN)
                                .before(callbacks).after(callbacks).build();
                        receipt = environment.install(spec);
                        if (receipt == null) throw new IllegalStateException("B10 SDK returned no receipt");
                        registration = receipt.registration();
                    }
                }
                @Override public void validateInstall() {
                    if ("A0".equals(request.control)) {
                        if (raw == null) throw new IllegalStateException("B10 raw handle unavailable");
                    } else if (receipt.status() != RegistrationReceipt.Status.INSTALLED || registration == null
                            || !LibXposed102Backend.BACKEND_ID.equals(receipt.backendId()) || receipt.generation() < 1
                            || receipt.targets().size() != 1
                            || receipt.targets().get(0).status() != TargetInstallReceipt.Status.INSTALLED
                            || !target.equals(receipt.targets().get(0).target())) {
                        throw new IllegalStateException("invalid B10 installation receipt");
                    }
                }
                @Override public Object invoke() throws Throwable { return b09InvokeStatic(target); }
                @Override public B10Runner.State state() throws Throwable {
                    if (countMethod == null) throw new IllegalStateException("B10 count unavailable");
                    Object count = b09InvokeStatic(countMethod);
                    if (!(count instanceof Long)) throw new IllegalStateException("B10 original count malformed");
                    return callbacks.state((Long) count);
                }
                @Override public void checkFramework() {
                    frameworkName = getFrameworkName(); frameworkVersion = String.valueOf(getFrameworkVersion());
                    reportedApi = getApiVersion();
                    if (frameworkName == null || frameworkName.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkName.trim()) || frameworkVersion.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkVersion.trim()) || reportedApi <= 0) {
                        throw new IllegalStateException("framework metadata unavailable");
                    }
                }
                @Override public B09Runner.Close closeRegistration() {
                    if ("A0".equals(request.control)) {
                        if (raw == null) return new B09Runner.Close("framework_unhook_contract", false, null);
                        raw.unhook();
                        return new B09Runner.Close("framework_unhook_contract", true, null);
                    }
                    return new B09Runner.Close("sdk_close_report", false, registration == null ? null : registration.close());
                }
                @Override public void closeEnvironment() { if (environment != null) environment.close(); }
            }, new B10Runner.Events() {
                @Override public void observed(String stage, B10Runner.Observation value) {
                    String extra = b10Observation(stage, value, false);
                    if ("installed".equals(stage)) {
                        if ("A0".equals(request.control)) extra += ",\"install_source\":\"framework_handle\",\"handle_returned\":true";
                        else extra += ",\"install_source\":\"sdk_receipt\",\"receipt_status\":\"INSTALLED\",\"generation\":"
                                + receipt.generation() + ",\"target_count\":1";
                    }
                    if ("configured".equals(stage)) extra += ",\"backend\":\"102\",\"framework_name\":" + quote(frameworkName)
                            + ",\"framework_version\":" + quote(frameworkVersion) + ",\"reported_api\":" + reportedApi;
                    event(request, stage, "pass", extra);
                }
                @Override public void failed(String stage, Throwable failure, B10Runner.Observation value) {
                    event(request, stage, "fail", ",\"detail\":" + quote(failure.getClass().getName()) + b10Observation(stage, value, true));
                }
            });
        }
        boolean configure() { return runner.configure(); }
        boolean finish() { return runner.finish(); }
    }

    static void b10ValidateControls(Class<?> type, Method prepare, Method target, Method count) {
        for (Method method : new Method[]{prepare, target, count}) {
            if (method.getDeclaringClass() != type || !Modifier.isPublic(method.getModifiers())
                    || !Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                throw new IllegalStateException("invalid B10 target controls");
            }
        }
        if (prepare.getReturnType() != void.class || target.getReturnType() != long.class || count.getReturnType() != long.class) {
            throw new IllegalStateException("invalid B10 target control return types");
        }
    }

    static String b10Observation(String stage, B10Runner.Observation value, boolean failed) {
        String extra = ",\"outcome_unknown\":" + value.outcomeUnknown;
        if (value.state != null) extra += ",\"before_count\":" + value.state.before + ",\"after_count\":"
                + value.state.after + ",\"original_count\":" + value.state.original;
        if (value.result instanceof Long) extra += ",\"result\":" + value.result;
        if ((stage.startsWith("resource_") || failed) && value.resources != null) {
            B10Resources.Snapshot snapshot = value.resources;
            extra += ",\"resource_start_ns\":" + snapshot.startNs + ",\"resource_end_ns\":" + snapshot.endNs
                    + ",\"rss_bytes\":" + snapshot.rssBytes + ",\"rss_status\":" + quote(snapshot.rssStatus)
                    + ",\"thread_count\":" + snapshot.threadCount + ",\"thread_status\":" + quote(snapshot.threadStatus)
                    + ",\"fd_count\":" + snapshot.fdCount + ",\"fd_status\":" + quote(snapshot.fdStatus);
        }
        if (("cleanup".equals(stage) || failed) && value.close != null) {
            extra += ",\"close_source\":" + quote(value.close.source);
            if ("framework_unhook_contract".equals(value.close.source)) {
                extra += ",\"entry_restoration\":" + quote(value.close.rawRestored ? "RESTORED" : "UNKNOWN")
                        + ",\"physical_release\":\"NOT_CLAIMED\"";
            } else if (value.close.report != null) {
                CloseReport report = value.close.report;
                extra += ",\"new_callbacks_rejected\":" + report.newCallbacksRejected()
                        + ",\"in_flight_at_close\":" + report.inFlightAtClose();
                if (report.state() != null) extra += ",\"registration_state\":" + quote(report.state().name());
                if (report.entryRestoration() != null) extra += ",\"entry_restoration\":" + quote(report.entryRestoration().name());
                if (report.physicalRelease() != null) extra += ",\"physical_release\":" + quote(report.physicalRelease().name());
            }
        }
        if ("resource_closed".equals(stage)) {
            extra += ",\"retired_resident_bytes\":null,\"retired_bytes_status\":\"not_attributable\"";
            if (value.close != null && "framework_unhook_contract".equals(value.close.source))
                extra += ",\"selected_registration_state\":\"UNOBSERVED\"";
            else if (value.close != null && value.close.report != null && value.close.report.state() != null)
                extra += ",\"selected_registration_state\":" + quote(value.close.report.state().name());
        }
        return extra;
    }

    /** Actual B09 callbacks shared by the direct path and SDK observers. */
    static final class B09Callbacks {
        final String failureCase;
        final RuntimeException expected;
        private final AtomicLong before = new AtomicLong(), after = new AtomicLong();
        private final AtomicReference<String> afterOutcome = new AtomicReference<>("not_called");
        private final AtomicReference<Object> afterResult = new AtomicReference<>();
        private final AtomicReference<Throwable> afterFailure = new AtomicReference<>();
        private final AtomicReference<Throwable> observationFailure = new AtomicReference<>();
        B09Callbacks(String failureCase, RuntimeException expected) {
            if (!("before".equals(failureCase) || "after".equals(failureCase) || "original".equals(failureCase))
                    || expected == null) throw new IllegalArgumentException("invalid B09 callback setup");
            this.failureCase = failureCase; this.expected = expected;
        }
        void before() {
            before.incrementAndGet();
            if ("before".equals(failureCase)) throw expected;
        }
        void after(InvocationResult result) throws Throwable {
            after.incrementAndGet();
            try {
                if (result.hasThrown()) {
                    afterOutcome.set("threw"); afterFailure.set(result.getThrowable());
                } else if (result.hasReturned()) {
                    afterOutcome.set("returned"); afterResult.set(result.getResult());
                } else afterOutcome.set("not_called");
            } catch (Throwable failure) {
                if (isFatal(failure)) throw failure;
                observationFailure.compareAndSet(null, failure);
            }
            if ("after".equals(failureCase)) throw expected;
        }
        Object intercept(XposedInterface.Chain chain) throws Throwable {
            before();
            Object result;
            try { result = chain.proceed(); }
            catch (Throwable failure) {
                if (isFatal(failure)) throw failure;
                after.incrementAndGet(); afterOutcome.set("threw"); afterFailure.set(failure);
                throw failure;
            }
            after.incrementAndGet(); afterOutcome.set("returned"); afterResult.set(result);
            if ("after".equals(failureCase)) throw expected;
            return result;
        }
        B09Runner.State state(long original) {
            return new B09Runner.State(before.get(), after.get(), original, afterOutcome.get(),
                    afterResult.get(), afterFailure.get(), observationFailure.get());
        }
    }

    /** B09 observes one controlled RuntimeException and verifies unhooked behavior. */
    private final class B09Session {
        private final Request request;
        private B09Callbacks callbacks;
        private Method target, countMethod;
        private RuntimeException expected;
        private XposedInterface.HookHandle raw;
        private HookEnvironment environment;
        private HookRegistration registration;
        private RegistrationReceipt receipt;
        private String frameworkName, frameworkVersion;
        private long reportedApi;
        private final B09Runner runner;

        B09Session(Request request) {
            this.request = request;
            runner = new B09Runner(request.failureCase, request.control, new B09Runner.Clock() {
                @Override public long wall() { return SystemClock.elapsedRealtimeNanos(); }
                @Override public long cpu() { return Debug.threadCpuTimeNanos(); }
            }, new B09Runner.Steps() {
                @Override public void prepare() throws Throwable {
                    Class<?> type = Class.forName(HOST + ".B09Target", false, loader);
                    if (type.getClassLoader() != loader) throw new IllegalStateException("B09 target loader mismatch");
                    b09InvokeStatic(type.getMethod("prepare", String.class), request.failureCase);
                    Object value = b09InvokeStatic(type.getMethod("expectedFailure"));
                    if (!(value instanceof RuntimeException)) throw new IllegalStateException("B09 expected failure missing");
                    expected = (RuntimeException) value;
                    callbacks = new B09Callbacks(request.failureCase, expected);
                    target = type.getMethod("value"); countMethod = type.getMethod("originalCount");
                    if (!Modifier.isStatic(target.getModifiers()) || target.getParameterCount() != 0
                            || target.getReturnType() != long.class || !Modifier.isStatic(countMethod.getModifiers())
                            || countMethod.getReturnType() != long.class || countMethod.getParameterCount() != 0) {
                        throw new IllegalStateException("invalid B09 target controls");
                    }
                    if ("A2".equals(request.control)) environment = HookEnvironment.create(new LibXposed102Backend(BenchmarkEntry.this,
                            new LibXposed102Context(processName, loader, 1L)));
                }
                @Override public RuntimeException expectedFailure() { return expected; }
                @Override public void install() {
                    if ("A0".equals(request.control)) {
                        raw = hook(target).setPriority(HookSpec.DEFAULT_PRIORITY)
                                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                                .intercept(chain -> callbacks.intercept(chain));
                        if (raw == null) throw new IllegalStateException("B09 framework returned no handle");
                    } else {
                        HookSpec spec = new HookSpec.Builder(HookId.of("benchmark.failure.b09"),
                                HookOwner.of("benchmark-failure-102"), TargetSpec.ofMember(target))
                                .access(ParameterAccess.NO_ARGS).errorPolicy(ErrorPolicy.PROPAGATE)
                                .processes(ProcessFilter.only(processName)).require(Capability.FRAMEWORK_CHAIN)
                                .before(invocation -> callbacks.before())
                                .after((invocation, result) -> callbacks.after(result)).build();
                        receipt = environment.install(spec);
                        if (receipt == null) throw new IllegalStateException("B09 SDK returned no receipt");
                        registration = receipt.registration();
                    }
                }
                @Override public void validateInstall() {
                    if ("A0".equals(request.control)) {
                        if (raw == null) throw new IllegalStateException("B09 raw handle unavailable");
                    } else if (receipt.status() != RegistrationReceipt.Status.INSTALLED || registration == null
                            || !LibXposed102Backend.BACKEND_ID.equals(receipt.backendId()) || receipt.generation() < 1
                            || receipt.targets().size() != 1
                            || receipt.targets().get(0).status() != TargetInstallReceipt.Status.INSTALLED
                            || !target.equals(receipt.targets().get(0).target())) {
                        throw new IllegalStateException("invalid B09 installation receipt");
                    }
                }
                @Override public Object invoke() throws Throwable { return b09InvokeStatic(target); }
                @Override public B09Runner.State state() throws Throwable {
                    if (countMethod == null) throw new IllegalStateException("B09 count unavailable");
                    Object count = b09InvokeStatic(countMethod);
                    if (!(count instanceof Long)) throw new IllegalStateException("B09 original count malformed");
                    return callbacks.state((Long) count);
                }
                @Override public void checkFramework() {
                    frameworkName = getFrameworkName(); frameworkVersion = String.valueOf(getFrameworkVersion());
                    reportedApi = getApiVersion();
                    if (frameworkName == null || frameworkName.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkName.trim()) || frameworkVersion.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkVersion.trim()) || reportedApi <= 0) {
                        throw new IllegalStateException("framework metadata unavailable");
                    }
                }
                @Override public B09Runner.Close closeRegistration() {
                    if ("A0".equals(request.control)) {
                        if (raw == null) return new B09Runner.Close("framework_unhook_contract", false, null);
                        raw.unhook();
                        return new B09Runner.Close("framework_unhook_contract", true, null);
                    }
                    return new B09Runner.Close("sdk_close_report", false,
                            registration == null ? null : registration.close());
                }
                @Override public void closeEnvironment() { if (environment != null) environment.close(); }
            }, new B09Runner.Events() {
                @Override public void observed(String stage, B09Runner.Observation value) {
                    String extra = b09Observation(stage, value);
                    if ("installed".equals(stage)) {
                        if ("A0".equals(request.control)) extra += ",\"install_source\":\"framework_handle\",\"handle_returned\":true";
                        else extra += ",\"install_source\":\"sdk_receipt\",\"receipt_status\":\"INSTALLED\",\"generation\":"
                                + receipt.generation() + ",\"target_count\":1";
                    }
                    if ("configured".equals(stage)) extra += ",\"backend\":\"102\",\"framework_name\":" + quote(frameworkName)
                            + ",\"framework_version\":" + quote(frameworkVersion) + ",\"reported_api\":" + reportedApi;
                    event(request, stage, "pass", extra);
                }
                @Override public void failed(String stage, Throwable failure, B09Runner.Observation value) {
                    event(request, stage, "fail", ",\"detail\":" + quote(failure.getClass().getName()) + b09Observation(stage, value));
                }
            });
        }

        boolean configure() { return runner.configure(); }
        boolean finish() { return runner.finish(); }
    }

    static Object b09InvokeStatic(Method method, Object... arguments) throws Throwable {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause() == null ? wrapped : wrapped.getCause(); }
    }

    private static String b09Observation(String stage, B09Runner.Observation value) {
        String extra = ",\"outcome_unknown\":" + value.outcomeUnknown;
        if (value.state != null) {
            extra += ",\"before_count\":" + value.state.before + ",\"after_count\":" + value.state.after
                    + ",\"original_count\":" + value.state.original;
        }
        if ("failure_call".equals(stage)) {
            if (value.wallStart != null) extra += ",\"wall_start_ns\":" + value.wallStart;
            if (value.cpuStart != null) extra += ",\"cpu_start_ns\":" + value.cpuStart;
            if (value.cpuEnd != null) extra += ",\"cpu_end_ns\":" + value.cpuEnd;
            if (value.wallEnd != null) extra += ",\"wall_end_ns\":" + value.wallEnd;
            if (value.state != null && value.state.afterOutcome != null) {
                extra += ",\"after_outcome\":" + quote(value.state.afterOutcome);
                if ("returned".equals(value.state.afterOutcome) && value.state.afterResult instanceof Long)
                    extra += ",\"after_result\":" + value.state.afterResult;
                if ("threw".equals(value.state.afterOutcome)) extra += ",\"after_identity_preserved\":" + value.afterIdentityPreserved;
            }
        }
        if ("failure_call".equals(stage) || "post_close".equals(stage)) {
            if (value.outcome != null) extra += ",\"outcome\":" + quote(value.outcome);
            if ("threw".equals(value.outcome)) extra += ",\"identity_preserved\":" + value.identityPreserved;
            if ("returned".equals(value.outcome) && value.result instanceof Long) extra += ",\"result\":" + value.result;
        }
        if ("cleanup".equals(stage) && value.close != null) {
            extra += ",\"close_source\":" + quote(value.close.source);
            if ("framework_unhook_contract".equals(value.close.source)) {
                extra += ",\"entry_restoration\":" + quote(value.close.rawRestored ? "RESTORED" : "UNKNOWN")
                        + ",\"physical_release\":\"NOT_CLAIMED\"";
            } else if (value.close.report != null) {
                CloseReport report = value.close.report;
                extra += ",\"new_callbacks_rejected\":" + report.newCallbacksRejected()
                        + ",\"in_flight_at_close\":" + report.inFlightAtClose();
                if (report.state() != null) extra += ",\"registration_state\":" + quote(report.state().name());
                if (report.entryRestoration() != null) extra += ",\"entry_restoration\":" + quote(report.entryRestoration().name());
                if (report.physicalRelease() != null) extra += ",\"physical_release\":" + quote(report.physicalRelease().name());
            }
        }
        return extra;
    }

    /** B08 closes one registration while its first original call is deliberately held. */
    private final class B08Session {
        private final Request request;
        private final AtomicLong before = new AtomicLong(), after = new AtomicLong();
        private HookEnvironment environment;
        private HookRegistration registration;
        private RegistrationReceipt receipt;
        private Method target, prepareTarget, originalCounts;
        private String frameworkName, frameworkVersion;
        private long reportedApi;
        private final B08Runner runner;

        B08Session(Request request) {
            this.request = request;
            runner = new B08Runner(new B08Runner.Clock() {
                @Override public long wall() { return SystemClock.elapsedRealtimeNanos(); }
                @Override public long tid() { return Process.myTid(); }
            }, new B08Runner.Steps() {
                @Override public void install() {
                    environment = HookEnvironment.create(new LibXposed102Backend(BenchmarkEntry.this,
                            new LibXposed102Context(processName, loader, 1L)));
                    HookSpec spec = new HookSpec.Builder(HookId.of("benchmark.close.b08"),
                            HookOwner.of("benchmark-close-102"), TargetSpec.descriptor(TargetKind.METHOD,
                            HOST + ".B08Target", "call").parameters("boolean").returning("long").build())
                            .processes(ProcessFilter.only(processName)).require(Capability.FRAMEWORK_CHAIN)
                            .before(invocation -> before.incrementAndGet())
                            .after((invocation, result) -> after.incrementAndGet()).build();
                    receipt = environment.install(spec);
                    registration = receipt.registration();
                    if (receipt.status() != RegistrationReceipt.Status.INSTALLED || registration == null
                            || !LibXposed102Backend.BACKEND_ID.equals(receipt.backendId())
                            || receipt.generation() < 1 || receipt.targets().size() != 1
                            || receipt.targets().get(0).status() != TargetInstallReceipt.Status.INSTALLED
                            || !(receipt.targets().get(0).target() instanceof Method)) {
                        throw new IllegalStateException("invalid B08 installation receipt");
                    }
                    target = (Method) receipt.targets().get(0).target();
                    if (!(HOST + ".B08Target").equals(target.getDeclaringClass().getName())
                            || target.getDeclaringClass().getClassLoader() != loader
                            || !"call".equals(target.getName()) || target.getParameterCount() != 1
                            || target.getParameterTypes()[0] != boolean.class || target.getReturnType() != long.class
                            || !Modifier.isStatic(target.getModifiers())) {
                        throw new IllegalStateException("unexpected B08 target");
                    }
                }
                @Override public java.util.concurrent.CountDownLatch[] prepareTarget() throws Throwable {
                    Class<?> type = target.getDeclaringClass();
                    prepareTarget = type.getMethod("prepare"); originalCounts = type.getMethod("originalCounts");
                    if (!Modifier.isStatic(prepareTarget.getModifiers()) || !Modifier.isStatic(originalCounts.getModifiers())
                            || originalCounts.getReturnType() != long[].class) {
                        throw new IllegalStateException("invalid B08 controls");
                    }
                    Object value = b08InvokeStatic(prepareTarget);
                    if (!(value instanceof java.util.concurrent.CountDownLatch[])) {
                        throw new IllegalStateException("invalid B08 target gates");
                    }
                    return (java.util.concurrent.CountDownLatch[]) value;
                }
                @Override public Object invoke(boolean hold) throws Throwable { return b08InvokeStatic(target, hold); }
                @Override public long[] originalCounts() throws Throwable {
                    if (originalCounts == null) throw new IllegalStateException("B08 counts unavailable");
                    return (long[]) b08InvokeStatic(originalCounts);
                }
                @Override public long[] hookCounts() { return new long[]{before.get(), after.get()}; }
                @Override public CloseReport closeRegistration() { return registration == null ? null : registration.close(); }
                @Override public void closeEnvironment() { if (environment != null) environment.close(); }
                @Override public void checkFramework() {
                    frameworkName = getFrameworkName(); frameworkVersion = String.valueOf(getFrameworkVersion());
                    reportedApi = getApiVersion();
                    if (frameworkName == null || frameworkName.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkName.trim()) || frameworkVersion.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkVersion.trim()) || reportedApi <= 0) {
                        throw new IllegalStateException("framework metadata unavailable");
                    }
                }
            }, new B08Runner.Events() {
                @Override public void observed(String kind, B08Runner.Observation value) {
                    String extra = b08Observation(value);
                    if ("installed".equals(kind)) extra += ",\"receipt_status\":\"INSTALLED\",\"generation\":"
                            + receipt.generation() + ",\"target_count\":1";
                    if ("post_close".equals(kind)) extra += ",\"result\":" + value.postResult;
                    if ("old_completed".equals(kind)) extra += ",\"result\":" + value.oldResult;
                    if ("configured".equals(kind)) extra += ",\"backend\":\"102\",\"framework_name\":"
                            + quote(frameworkName) + ",\"framework_version\":" + quote(frameworkVersion)
                            + ",\"reported_api\":" + reportedApi;
                    event(request, kind, "pass", extra);
                }
                @Override public void failed(String stage, Throwable failure, B08Runner.Observation value) {
                    event(request, stage, "fail", ",\"detail\":" + quote(failure.getClass().getName())
                            + b08Observation(value));
                }
            }, 3000L);
        }
        boolean configure() { return runner.configure(); }
        boolean finish() { return runner.finish(); }
    }

    static Object b08InvokeStatic(Method method, Object... arguments) throws Throwable {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause() == null ? wrapped : wrapped.getCause(); }
    }

    private static String b08Observation(B08Runner.Observation value) {
        String extra = ",\"worker_completed\":" + value.workerCompleted + ",\"gate_released\":" + value.gateReleased
                + ",\"worker_alive\":" + value.workerAlive + ",\"closer_alive\":" + value.closerAlive
                + ",\"post_alive\":" + value.postAlive + ",\"cleanup_alive\":" + value.cleanupAlive
                + ",\"cleanup_confirmed\":" + value.cleanupConfirmed;
        if (value.workerTid != null) extra += ",\"worker_tid\":" + value.workerTid;
        if (value.closerTid != null) extra += ",\"closer_tid\":" + value.closerTid;
        if (value.postTid != null) extra += ",\"post_tid\":" + value.postTid;
        if (value.closeStart != null) extra += ",\"close_wall_start_ns\":" + value.closeStart;
        if (value.closeEnd != null) extra += ",\"close_wall_end_ns\":" + value.closeEnd;
        if (value.hookCounts != null && value.hookCounts.length == 2) {
            extra += ",\"before_count\":" + value.hookCounts[0] + ",\"after_count\":" + value.hookCounts[1];
        }
        if (value.originalCounts != null && value.originalCounts.length == 2) {
            extra += ",\"old_original\":" + value.originalCounts[0] + ",\"new_original\":" + value.originalCounts[1];
        }
        if (value.report != null) {
            extra += ",\"new_callbacks_rejected\":" + value.report.newCallbacksRejected()
                    + ",\"in_flight_at_close\":" + value.report.inFlightAtClose();
            if (value.report.state() != null) extra += ",\"registration_state\":" + quote(value.report.state().name());
            if (value.report.entryRestoration() != null) extra += ",\"entry_restoration\":" + quote(value.report.entryRestoration().name());
            if (value.report.physicalRelease() != null) extra += ",\"physical_release\":" + quote(value.report.physicalRelease().name());
        }
        return extra;
    }

    /** B07 is one descriptor install per process; no preflight touches the target. */
    private final class B07Session {
        private final Request request;
        private final AtomicInteger beforeCount = new AtomicInteger();
        private HookEnvironment environment;
        private HookSpec spec;
        private final B07Runner runner;
        private HookRegistration registration;
        private Method target;
        private Method originalCount;
        private String frameworkName;
        private String frameworkVersion;
        private int reportedApi;

        B07Session(Request request) {
            this.request = request;
            runner = new B07Runner(new B07Runner.Clock() {
                @Override public long wall() { return SystemClock.elapsedRealtimeNanos(); }
                @Override public long cpu() { return Debug.threadCpuTimeNanos(); }
            }, new B07Runner.Steps() {
                @Override public void prepare() {
                    environment = HookEnvironment.create(new LibXposed102Backend(BenchmarkEntry.this,
                            new LibXposed102Context(processName, loader, 1L)));
                    TargetSpec descriptor = TargetSpec.descriptor(TargetKind.METHOD, HOST + ".B07Target", "value")
                            .parameters().returning("long").build();
                    spec = new HookSpec.Builder(HookId.of("benchmark.install.b07"),
                            HookOwner.of("benchmark-install-102"), descriptor)
                            .processes(ProcessFilter.only(processName)).require(Capability.FRAMEWORK_CHAIN)
                            .before(invocation -> beforeCount.incrementAndGet()).build();
                }
                @Override public RegistrationReceipt install() { return environment.install(spec); }
                @Override public void retain(RegistrationReceipt receipt) { registration = receipt.registration(); }
                @Override public void validate(RegistrationReceipt receipt) {
                    if (receipt.status() != RegistrationReceipt.Status.INSTALLED || registration == null
                            || !LibXposed102Backend.BACKEND_ID.equals(receipt.backendId())
                            || receipt.generation() < 1 || receipt.targets().size() != 1
                            || receipt.targets().get(0).status() != TargetInstallReceipt.Status.INSTALLED
                            || !(receipt.targets().get(0).target() instanceof Method)) {
                        throw new IllegalStateException("invalid installation receipt");
                    }
                    target = (Method) receipt.targets().get(0).target();
                    if (!b07TargetMatches(target, loader)) throw new IllegalStateException("unexpected resolved target");
                }
                @Override public void hookCheck() throws Throwable {
                    originalCount = target.getDeclaringClass().getMethod("originalCount");
                    if (originalCount.getReturnType() != long.class || originalCount.getParameterCount() != 0
                            || !Modifier.isStatic(originalCount.getModifiers())) {
                        throw new IllegalStateException("invalid original-count method");
                    }
                    Object result = b07InvokeStatic(target);
                    long originals = ((Number) b07InvokeStatic(originalCount)).longValue();
                    if (!(result instanceof Long) || ((Long) result).longValue() != 42L
                            || beforeCount.get() != 1 || originals != 1L) {
                        throw new IllegalStateException("B07 hook check failed");
                    }
                }
                @Override public void checkFramework() {
                    frameworkName = getFrameworkName();
                    frameworkVersion = String.valueOf(getFrameworkVersion());
                    reportedApi = getApiVersion();
                    if (frameworkName == null || frameworkName.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkName.trim()) || frameworkVersion.trim().isEmpty()
                            || "null".equalsIgnoreCase(frameworkVersion.trim()) || reportedApi <= 0) {
                        throw new IllegalStateException("framework metadata unavailable");
                    }
                }
                @Override public CloseReport closeRegistration() {
                    return registration == null ? null : registration.close();
                }
                @Override public void closeEnvironment() { if (environment != null) environment.close(); }
                @Override public void postClose() throws Throwable {
                    Object result = b07InvokeStatic(target);
                    long originals = ((Number) b07InvokeStatic(originalCount)).longValue();
                    if (!(result instanceof Long) || ((Long) result).longValue() != 42L
                            || beforeCount.get() != 1 || originals != 2L) {
                        throw new IllegalStateException("B07 post-close check failed");
                    }
                }
            }, new B07Runner.Events() {
                @Override public void installed(RegistrationReceipt receipt, long ws, long cs, long ce, long we) {
                    event(request, "install", "pass", ",\"wall_start_ns\":" + ws
                            + ",\"cpu_start_ns\":" + cs + ",\"cpu_end_ns\":" + ce
                            + ",\"wall_end_ns\":" + we + ",\"receipt_status\":\"INSTALLED\""
                            + ",\"generation\":" + receipt.generation() + ",\"target_count\":1");
                }
                @Override public void hookChecked() {
                    event(request, "hook_check", "pass", ",\"result\":42,\"before_count\":1,\"original_count\":1");
                }
                @Override public void configured() {
                    event(request, "configured", "pass", ",\"backend\":\"102\",\"framework_name\":"
                            + quote(frameworkName) + ",\"framework_version\":" + quote(frameworkVersion)
                            + ",\"reported_api\":" + reportedApi);
                }
                @Override public void cleaned(CloseReport report) {
                    event(request, "cleanup", "pass", ",\"entry_restoration\":\"RESTORED\""
                            + ",\"new_callbacks_rejected\":true,\"in_flight\":0,\"physical_release\":"
                            + quote(report.physicalRelease().name()));
                }
                @Override public void postClosed() {
                    event(request, "post_close", "pass", ",\"result\":42,\"before_count\":1,\"original_count\":2");
                }
                @Override public void failed(String stage, Throwable failure, long ws, long cs, long ce, long we,
                                             boolean wsRead, boolean csRead, boolean ceRead, boolean weRead,
                                             boolean unknown) {
                    String timing = "";
                    if ("install".equals(stage)) {
                        if (wsRead) timing += ",\"wall_start_ns\":" + ws;
                        if (csRead) timing += ",\"cpu_start_ns\":" + cs;
                        if (ceRead) timing += ",\"cpu_end_ns\":" + ce;
                        if (weRead) timing += ",\"wall_end_ns\":" + we;
                        timing += ",\"outcome_unknown\":" + unknown;
                    }
                    event(request, stage, "fail", ",\"detail\":" + quote(failure.getClass().getName()) + timing);
                }
            });
        }

        boolean configure() { return runner.configure(); }
        boolean finish() { return runner.finish(); }
    }

    static Object b07InvokeStatic(Method method) throws Throwable {
        try {
            return method.invoke(null);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
        }
    }

    static boolean b07ValidClocks(long wallStart, long cpuStart, long cpuEnd, long wallEnd) {
        return wallStart >= 0 && cpuStart >= 0 && cpuEnd >= cpuStart && wallEnd >= wallStart;
    }

    static boolean b07TargetMatches(Method target, ClassLoader expectedLoader) {
        return target != null && (HOST + ".B07Target").equals(target.getDeclaringClass().getName())
                && target.getDeclaringClass().getClassLoader() == expectedLoader
                && "value".equals(target.getName()) && target.getParameterCount() == 0
                && target.getReturnType() == long.class && Modifier.isStatic(target.getModifiers());
    }

    static boolean b07CloseRestored(CloseReport report) {
        return report != null && report.newCallbacksRejected()
                && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED
                && report.inFlightAtClose() == 0 && report.physicalRelease() != null;
    }

    private Throwable cleanupAfterFailure(Throwable failure, ActiveHook first, ActiveHook second) {
        for (ActiveHook hook : new ActiveHook[]{first, second}) {
            if (hook == null) continue;
            try {
                hook.close();
            } catch (Throwable cleanup) {
                if (cleanup instanceof Error && (!(failure instanceof Error)
                        || (isFatal(cleanup) && !isFatal(failure)))) {
                    if (cleanup != failure) cleanup.addSuppressed(failure);
                    failure = cleanup;
                } else if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        return failure;
    }

    private final class ActiveHook {
        private XposedInterface.HookHandle raw;
        private HookEnvironment environment;
        private HookRegistration registration;
        private boolean attempted;
        private boolean closed;
        private CloseOutcome result = new CloseOutcome(false, "unknown");

        void install(Request request, Method target, AtomicInteger counter) throws Throwable {
            attempted = true;
            String id = counter == null ? "benchmark.measurement" : "benchmark.preflight";
            Observations observations = counter == null && request.observed
                    ? Observations.from(target.getDeclaringClass()) : null;
            if ("A0".equals(request.control)) {
                final XposedInterface.Hooker callback;
                if ("B06".equals(request.workload)) {
                    if (observations != null) callback = new ObservedReentrantHooker(observations);
                    else if (counter == null) callback = new MeasurementReentrantHooker();
                    else callback = chain -> {
                        counter.incrementAndGet();
                        return proceedReentrant(chain);
                    };
                } else if ("B05".equals(request.workload)) {
                    if (observations != null) callback = new ObservedAroundHooker(observations);
                    else if (counter == null) callback = new MeasurementAroundHooker();
                    else callback = chain -> {
                        counter.incrementAndGet();
                        return chain.proceed();
                    };
                } else if ("B04".equals(request.workload)) {
                    if (observations != null) callback = new ObservedMutableHooker(observations);
                    else if (counter == null) callback = new MeasurementMutableHooker();
                    else callback = chain -> {
                        counter.incrementAndGet();
                        return proceedWithMutableArgument(chain);
                    };
                } else if ("B03".equals(request.workload)) {
                    if (observations != null) callback = new ObservedSnapshotHooker(observations);
                    else if (counter == null) callback = new MeasurementSnapshotHooker();
                    else callback = chain -> {
                        counter.incrementAndGet();
                        Object[] args = chain.getArgs().toArray();
                        Object[] snapshot = args.clone();
                        readSnapshot(snapshot[0], snapshot[1]);
                        Object result = chain.proceed(args);
                        readSnapshot(snapshot[0], snapshot[1]);
                        return result;
                    };
                } else if (observations != null && "B01".equals(request.workload)) {
                    callback = new ObservedNoArgsHooker(observations);
                } else if (observations != null) {
                    callback = new ObservedReadArgsHooker(observations);
                } else if (counter == null && "B01".equals(request.workload)) {
                    callback = new MeasurementNoArgsHooker();
                } else if (counter == null) {
                    callback = new MeasurementReadArgsHooker();
                } else if ("B01".equals(request.workload)) {
                    callback = chain -> {
                        counter.incrementAndGet();
                        return chain.proceed();
                    };
                } else {
                    callback = chain -> {
                        counter.incrementAndGet();
                        chain.getArgs().get(0);
                        chain.getArgs().get(1);
                        return chain.proceed();
                    };
                }
                // Match the SDK adapter: the target hook has no framework-level ID.
                raw = hook(target).setPriority(HookSpec.DEFAULT_PRIORITY)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(callback);
                if (raw == null) throw new IllegalStateException("framework returned no hook handle");
                return;
            }
            environment = HookEnvironment.create(new LibXposed102Backend(BenchmarkEntry.this,
                    new LibXposed102Context(processName, loader, 1L)));
            HookSpec.Builder builder = new HookSpec.Builder(HookId.of(id), HookOwner.of("benchmark-pilot-102"),
                    TargetSpec.ofMember(target));
            if ("B06".equals(request.workload)) {
                if (observations != null) builder.around(new ObservedReentrantCallback(observations));
                else if (counter == null) builder.around(new MeasurementReentrantCallback());
                else builder.around(invocation -> {
                    counter.incrementAndGet();
                    proceedReentrant(invocation);
                });
            } else if ("B05".equals(request.workload)) {
                if (observations != null) builder.around(new ObservedAroundCallback(observations));
                else if (counter == null) builder.around(new MeasurementAroundCallback());
                else builder.around(invocation -> {
                    counter.incrementAndGet();
                    invocation.proceed();
                });
            } else if ("B01".equals(request.workload)) {
                builder.access(ParameterAccess.NO_ARGS);
                if (observations != null) builder.before(new ObservedNoArgsBefore(observations));
                else if (counter == null) builder.before(new MeasurementNoArgsBefore());
                else builder.before(invocation -> counter.incrementAndGet());
                if (observations != null) builder.after(new ObservedNoArgsAfter(observations));
                else builder.after(new MeasurementNoArgsAfter());
            } else if ("B04".equals(request.workload)) {
                builder.access(ParameterAccess.MUTABLE_ARGS);
                if (observations != null) builder.before(new ObservedMutableBefore(observations));
                else if (counter == null) builder.before(new MeasurementMutableBefore());
                else builder.before(invocation -> {
                    counter.incrementAndGet();
                    writeMutableArgument(invocation);
                });
            } else if ("B03".equals(request.workload)) {
                builder.access(ParameterAccess.ENTRY_SNAPSHOT);
                if (observations != null) {
                    builder.before(new ObservedSnapshotBefore(observations));
                    builder.after(new ObservedSnapshotAfter(observations));
                } else if (counter == null) {
                    builder.before(new MeasurementSnapshotBefore());
                    builder.after(new MeasurementSnapshotAfter());
                } else {
                    builder.before(invocation -> {
                        counter.incrementAndGet();
                        readEntrySnapshot(invocation);
                    });
                    builder.after(new MeasurementSnapshotAfter());
                }
            } else {
                builder.access(ParameterAccess.READ_ARGS);
                if (observations != null) {
                    builder.before(new ObservedReadArgsBefore(observations));
                } else if (counter == null) {
                    builder.before(new MeasurementReadArgsBefore());
                } else {
                    builder.before(invocation -> {
                        counter.incrementAndGet();
                        ReadArgsInvocation args = (ReadArgsInvocation) invocation;
                        args.getArgument(0);
                        args.getArgument(1);
                    });
                }
            }
            RegistrationReceipt receipt = environment.install(builder.build());
            if (!receipt.isInstalled() || receipt.registration() == null) {
                throw new IllegalStateException("SDK install failed: " + receipt.status());
            }
            registration = receipt.registration();
        }

        CloseOutcome close() {
            if (closed) return result;
            closed = true;
            if (!attempted) return result;
            if (raw != null) {
                // libxposed unhook is void; as in the 102 adapter this is an API-contract-level
                // restoration report, not independent proof of physical removal or device behaviour.
                raw.unhook();
                result = new CloseOutcome(true, "framework_unhook_contract");
                return result;
            }
            if (registration != null) {
                CloseReport report = registration.close();
                if (report != null && report.newCallbacksRejected()
                        && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED) {
                    environment.close();
                    result = new CloseOutcome(true, "sdk_close_report");
                }
            } else if (environment != null) {
                // No handle was returned: close what the environment owns but make no restoration claim.
                environment.close();
            }
            return result;
        }
    }

    /** Shared standard-library counters are obtained once during configuration, not per call. */
    static final class Observations {
        final AtomicLong[] counters;
        final Thread owner;

        private Observations(AtomicLong[] counters) {
            this.counters = counters;
            this.owner = Thread.currentThread();
        }

        static Observations from(Class<?> targets) throws Throwable {
            Object value = targets.getMethod("observationCounters").invoke(null);
            if (!(value instanceof AtomicLong[]) || ((AtomicLong[]) value).length != 4) {
                throw new IllegalStateException("invalid observation counters");
            }
            AtomicLong[] counters = (AtomicLong[]) value;
            for (AtomicLong counter : counters) {
                if (counter == null) throw new IllegalStateException("null observation counter");
            }
            return new Observations(counters);
        }

        void count(int index) {
            if (Thread.currentThread() != owner) counters[3].incrementAndGet();
            counters[index].incrementAndGet();
        }
    }

    /** B06 retains the entry depth across its single continuation on each recursive layer. */
    private static int checkedReentrantDepth(Object value) {
        if (!(value instanceof Integer) || (Integer) value < 0 || (Integer) value > 3) {
            throw new IllegalStateException("invalid reentrant depth");
        }
        return (Integer) value;
    }

    private static int chainReentrantDepth(XposedInterface.Chain chain) {
        List<Object> args = chain.getArgs();
        if (args == null || args.size() != 1) throw new IllegalStateException("invalid reentrant arguments");
        return checkedReentrantDepth(args.get(0));
    }

    private static void checkReentrantResult(int depth, Object result) {
        if (!(result instanceof Integer) || (Integer) result != depth + 1) {
            throw new IllegalStateException("invalid reentrant result");
        }
    }

    static Object proceedReentrant(XposedInterface.Chain chain) throws Throwable {
        int depth = chainReentrantDepth(chain);
        Object result = chain.proceed();
        if (chainReentrantDepth(chain) != depth) throw new IllegalStateException("reentrant depth changed");
        checkReentrantResult(depth, result);
        return result;
    }

    static boolean proceedReentrant(AroundInvocation invocation) {
        int depth = checkedReentrantDepth(invocation.getArgument(0));
        CallOutcome outcome = invocation.proceed();
        if (outcome.state() == CallOutcome.State.THREW) return false;
        if (outcome.state() != CallOutcome.State.RETURNED) throw new IllegalStateException("invalid continuation state");
        if (checkedReentrantDepth(invocation.getArgument(0)) != depth) {
            throw new IllegalStateException("reentrant depth changed");
        }
        checkReentrantResult(depth, outcome.value());
        return true;
    }

    static final class ObservedReentrantHooker implements XposedInterface.Hooker {
        private final Observations observations;
        ObservedReentrantHooker(Observations observations) { this.observations = observations; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            observations.count(0);
            Object result = proceedReentrant(chain);
            observations.count(1);
            return result;
        }
    }

    static final class ObservedReentrantCallback implements AroundCallback {
        private final Observations observations;
        ObservedReentrantCallback(Observations observations) { this.observations = observations; }
        @Override public void around(AroundInvocation invocation) {
            observations.count(0);
            if (proceedReentrant(invocation)) observations.count(1);
        }
    }

    static final class MeasurementReentrantHooker implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            return proceedReentrant(chain);
        }
    }

    static final class MeasurementReentrantCallback implements AroundCallback {
        @Override public void around(AroundInvocation invocation) { proceedReentrant(invocation); }
    }

    /** B05 counts only normal continuation return; exceptions remain outside this workload. */
    static final class ObservedAroundHooker implements XposedInterface.Hooker {
        private final Observations observations;
        ObservedAroundHooker(Observations observations) { this.observations = observations; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            observations.count(0);
            Object result = chain.proceed();
            observations.count(1);
            return result;
        }
    }

    static final class ObservedAroundCallback implements AroundCallback {
        private final Observations observations;
        ObservedAroundCallback(Observations observations) { this.observations = observations; }
        @Override public void around(AroundInvocation invocation) {
            observations.count(0);
            CallOutcome outcome = invocation.proceed();
            if (outcome.state() == CallOutcome.State.RETURNED) observations.count(1);
        }
    }

    static final class MeasurementAroundHooker implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            return chain.proceed();
        }
    }

    static final class MeasurementAroundCallback implements AroundCallback {
        @Override public void around(AroundInvocation invocation) { invocation.proceed(); }
    }

    // The direct path validates the write shape before passing a new argument array downstream.
    static Object proceedWithMutableArgument(XposedInterface.Chain chain) throws Throwable {
        Object[] args = chain.getArgs().toArray();
        if (args.length != 2 || !(args[0] instanceof Integer)) {
            throw new IllegalStateException("invalid mutable argument shape");
        }
        args[0] = 5;
        return chain.proceed(args);
    }

    static void writeMutableArgument(HookInvocation invocation) {
        ((MutableArgsInvocation) invocation).setArgument(0, 5);
    }

    static final class ObservedMutableHooker implements XposedInterface.Hooker {
        private final Observations observations;
        ObservedMutableHooker(Observations observations) { this.observations = observations; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            observations.count(0);
            return proceedWithMutableArgument(chain);
        }
    }

    static final class ObservedMutableBefore implements BeforeCallback {
        private final Observations observations;
        ObservedMutableBefore(Observations observations) { this.observations = observations; }
        @Override public void before(HookInvocation invocation) {
            observations.count(0);
            writeMutableArgument(invocation);
        }
    }

    static final class MeasurementMutableHooker implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            return proceedWithMutableArgument(chain);
        }
    }

    static final class MeasurementMutableBefore implements BeforeCallback {
        @Override public void before(HookInvocation invocation) { writeMutableArgument(invocation); }
    }

    // Type checks make both slot reads observable in both controls; this is diagnostic work.
    static void readSnapshot(Object box, Object delta) {
        if (!(box instanceof int[]) || !(delta instanceof Integer)) {
            throw new IllegalStateException("invalid snapshot argument types");
        }
    }

    static void readEntrySnapshot(HookInvocation invocation) {
        EntrySnapshotInvocation snapshot = (EntrySnapshotInvocation) invocation;
        readSnapshot(snapshot.getEntryArgument(0), snapshot.getEntryArgument(1));
    }

    static final class ObservedSnapshotHooker implements XposedInterface.Hooker {
        private final Observations observations;
        ObservedSnapshotHooker(Observations observations) { this.observations = observations; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object[] args = chain.getArgs().toArray();
            Object[] snapshot = args.clone();
            observations.count(0);
            readSnapshot(snapshot[0], snapshot[1]);
            Object result = chain.proceed(args);
            observations.count(1);
            readSnapshot(snapshot[0], snapshot[1]);
            return result;
        }
    }

    static final class ObservedSnapshotBefore implements BeforeCallback {
        private final Observations observations;
        ObservedSnapshotBefore(Observations observations) { this.observations = observations; }
        @Override public void before(HookInvocation invocation) {
            observations.count(0);
            readEntrySnapshot(invocation);
        }
    }

    static final class ObservedSnapshotAfter implements AfterCallback {
        private final Observations observations;
        ObservedSnapshotAfter(Observations observations) { this.observations = observations; }
        @Override public void after(HookInvocation invocation, InvocationResult result) {
            observations.count(1);
            readEntrySnapshot(invocation);
        }
    }

    static final class ObservedNoArgsHooker implements XposedInterface.Hooker {
        private final Observations observations;
        ObservedNoArgsHooker(Observations observations) { this.observations = observations; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            observations.count(0);
            Object result = chain.proceed();
            observations.count(1);
            return result;
        }
    }

    static final class ObservedReadArgsHooker implements XposedInterface.Hooker {
        private final Observations observations;
        ObservedReadArgsHooker(Observations observations) { this.observations = observations; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            observations.count(0);
            chain.getArgs().get(0);
            chain.getArgs().get(1);
            return chain.proceed();
        }
    }

    static final class ObservedNoArgsBefore implements BeforeCallback {
        private final Observations observations;
        ObservedNoArgsBefore(Observations observations) { this.observations = observations; }
        @Override public void before(HookInvocation invocation) { observations.count(0); }
    }

    static final class ObservedNoArgsAfter implements AfterCallback {
        private final Observations observations;
        ObservedNoArgsAfter(Observations observations) { this.observations = observations; }
        @Override public void after(HookInvocation invocation, InvocationResult result) { observations.count(1); }
    }

    static final class ObservedReadArgsBefore implements BeforeCallback {
        private final Observations observations;
        ObservedReadArgsBefore(Observations observations) { this.observations = observations; }
        @Override public void before(HookInvocation invocation) {
            observations.count(0);
            ReadArgsInvocation args = (ReadArgsInvocation) invocation;
            args.getArgument(0);
            args.getArgument(1);
        }
    }

    /** Separate kept classes prevent R8 from merging measured callbacks into a switch dispatch. */
    static final class MeasurementSnapshotHooker implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object[] args = chain.getArgs().toArray();
            Object[] snapshot = args.clone();
            readSnapshot(snapshot[0], snapshot[1]);
            Object result = chain.proceed(args);
            readSnapshot(snapshot[0], snapshot[1]);
            return result;
        }
    }

    static final class MeasurementSnapshotBefore implements BeforeCallback {
        @Override public void before(HookInvocation invocation) { readEntrySnapshot(invocation); }
    }

    static final class MeasurementSnapshotAfter implements AfterCallback {
        @Override public void after(HookInvocation invocation, InvocationResult result) {
            readEntrySnapshot(invocation);
        }
    }

    private static final class MeasurementNoArgsHooker implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            return chain.proceed();
        }
    }

    private static final class MeasurementReadArgsHooker implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            chain.getArgs().get(0);
            chain.getArgs().get(1);
            return chain.proceed();
        }
    }

    private static final class MeasurementNoArgsBefore implements BeforeCallback {
        @Override public void before(HookInvocation invocation) { }
    }

    private static final class MeasurementNoArgsAfter implements AfterCallback {
        @Override public void after(HookInvocation invocation, InvocationResult result) { }
    }

    private static final class MeasurementReadArgsBefore implements BeforeCallback {
        @Override public void before(HookInvocation invocation) {
            ReadArgsInvocation args = (ReadArgsInvocation) invocation;
            args.getArgument(0);
            args.getArgument(1);
        }
    }

    private static final class CloseOutcome {
        final boolean restored;
        final String source;

        CloseOutcome(boolean restored, String source) {
            this.restored = restored;
            this.source = source;
        }
    }

    static final class Request {
        final String runId, pairId, sessionId, side, workload, control, failureCase;
        final boolean observed;

        Request(String runId, String pairId, String sessionId, String side, String workload, String control,
                boolean observed) {
            this(runId, pairId, sessionId, side, workload, control, observed, null);
        }

        Request(String runId, String pairId, String sessionId, String side, String workload, String control,
                boolean observed, String failureCase) {
            this.runId = runId;
            this.pairId = pairId;
            this.sessionId = sessionId;
            this.side = side;
            this.workload = workload;
            this.control = control;
            this.observed = observed;
            this.failureCase = failureCase;
        }

        static Request from(List<Object> arguments) {
            if (arguments == null || arguments.size() != 9) return null;
            for (int i = 0; i < 6; i++) if (!(arguments.get(i) instanceof String)) return null;
            for (int i = 6; i < 9; i++) if (!(arguments.get(i) instanceof Integer)) return null;
            String run = (String) arguments.get(0), pair = (String) arguments.get(1);
            String session = (String) arguments.get(2), side = (String) arguments.get(3);
            String workload = (String) arguments.get(4), control = (String) arguments.get(5);
            String failureCase = null;
            if ("B09_BEFORE".equals(workload)) { workload = "B09"; failureCase = "before"; }
            else if ("B09_AFTER".equals(workload)) { workload = "B09"; failureCase = "after"; }
            else if ("B09_ORIGINAL".equals(workload)) { workload = "B09"; failureCase = "original"; }
            boolean observed = "B01_OBSERVED".equals(workload) || "B02_OBSERVED".equals(workload)
                    || "B03_OBSERVED".equals(workload) || "B04_OBSERVED".equals(workload)
                    || "B05_OBSERVED".equals(workload) || "B06_OBSERVED".equals(workload);
            if (observed) workload = workload.substring(0, 3);
            int warmup = (Integer) arguments.get(6), samples = (Integer) arguments.get(7);
            int iterations = (Integer) arguments.get(8);
            if (!uuid(run) || !uuid(pair) || !uuid(session) || !("A".equals(side) || "B".equals(side))
                    || !("B01".equals(workload) || "B02".equals(workload) || "B03".equals(workload)
                        || "B04".equals(workload) || "B05".equals(workload) || "B06".equals(workload)
                        || "B07".equals(workload) || "B08".equals(workload) || "B09".equals(workload) || "B10".equals(workload))
                    || !("A0".equals(control) || "A2".equals(control))
                    || (("B07".equals(workload) || "B08".equals(workload)) && (!"A2".equals(control) || observed
                        || warmup != 0 || samples != 1 || iterations != 1
                        || run.equals(pair) || run.equals(session) || pair.equals(session)))
                    || ("B09".equals(workload) && (failureCase == null || observed || warmup != 0 || samples != 1
                        || iterations != 1 || run.equals(pair) || run.equals(session) || pair.equals(session)))
                    || ("B10".equals(workload) && (observed || warmup != 0 || samples != 1 || iterations != 1
                        || run.equals(pair) || run.equals(session) || pair.equals(session)))
                    || warmup < 0 || warmup > 1_000_000 || samples < 1 || samples > 200
                    || iterations < 1 || iterations > 1_000_000
                    || (warmup + (long) samples * iterations) * ("B06".equals(workload) ? 4L : 1L)
                        > 10_000_000L) return null;
            return new Request(run, pair, session, side, workload, control, observed, failureCase);
        }

        private static boolean uuid(String value) {
            try {
                return UUID.fromString(value).toString().equals(value);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    private static void event(Request r, String kind, String status, String extra) {
        Log.i(TAG, "{\"schema\":1,\"run_id\":" + quote(r.runId) + ",\"pair_id\":" + quote(r.pairId)
                + ",\"session_id\":" + quote(r.sessionId) + ",\"pid\":" + Process.myPid()
                + ",\"side\":" + quote(r.side) + ",\"workload\":" + quote(r.workload)
                + ",\"control\":" + quote(r.control)
                + ("B09".equals(r.workload) ? ",\"failure_case\":" + quote(r.failureCase) : "")
                + ",\"event\":" + quote(kind)
                + ",\"status\":" + quote(status) + extra + "}");
    }

    private static String quote(String value) {
        return JSONObject.quote(value);
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }
}
