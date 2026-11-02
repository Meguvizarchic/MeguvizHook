package io.github.jichuo1.meguviz.hook.cpubody102;

import android.os.Debug;
import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend;
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only interceptor; intervals exclude chain.proceed and the target body. */
public final class ProbeEntry extends XposedModule {
    private static final String HOST = "io.github.jichuo1.meguviz.hook.cpubodyhost";
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private ClassLoader loader;
    private String processName, activeRun;
    private volatile boolean controlsReady;
    private boolean consumed;
    private XposedInterface.HookHandle raw;
    private HookEnvironment environment;
    private HookRegistration registration;

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!HOST.equals(param.getPackageName()) || !STARTED.compareAndSet(false, true)) return;
        try {
            loader = param.getClassLoader();
            processName = param.getApplicationInfo().processName;
            Class<?> controls = Class.forName(HOST + ".ProbeControl", false, loader);
            Method configure = controls.getMethod("configure", String.class, String.class, String.class,
                    String.class, int.class, int.class);
            Method finish = controls.getMethod("finish", String.class);
            ControlHookSetup.install(new ControlHookSetup.Steps<XposedInterface.HookHandle>() {
                @Override public XposedInterface.HookHandle installConfigure() {
                    return hook(configure).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> configure(chain.getArgs()));
                }
                @Override public XposedInterface.HookHandle installFinish() {
                    return hook(finish).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> finish(chain.getArgs()));
                }
                @Override public void ready() { controlsReady = true; }
                @Override public void disarm() { controlsReady = false; }
                @Override public void unhook(XposedInterface.HookHandle handle) { handle.unhook(); }
            });
        } catch (Throwable failure) {
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            throw new IllegalStateException(failure);
        }
    }

    private synchronized boolean configure(List<Object> a) {
        if (!controlsReady || consumed || a == null || a.size() != 6
                || !(a.get(0) instanceof String) || !(a.get(1) instanceof String)
                || !(a.get(2) instanceof String) || !(a.get(3) instanceof String)
                || !(a.get(4) instanceof Integer) || !(a.get(5) instanceof Integer)) return false;
        String id = (String) a.get(0), backend = (String) a.get(1), workload = (String) a.get(2), control = (String) a.get(3);
        int warmup = (Integer) a.get(4), samples = (Integer) a.get(5);
        try { if (!UUID.fromString(id).toString().equals(id)) return false; }
        catch (IllegalArgumentException invalid) { return false; }
        if (!"102".equals(backend) || !("B01".equals(workload) || "B02".equals(workload))
                || !("A0".equals(control) || "A2".equals(control))
                || warmup < 0 || warmup > 100 || samples < 1 || samples > 200) return false;
        consumed = true; activeRun = id;
        try {
            Class<?> ledger = Class.forName(HOST + ".ProbeLedger", false, loader);
            Trace trace = Trace.from(ledger.getMethod("export").invoke(null));
            Class<?> targets = Class.forName(HOST + ".ProbeTarget", false, loader);
            Method target = "B01".equals(workload) ? targets.getMethod("noArgs")
                    : targets.getMethod("add", int.class, int.class);
            if ("A0".equals(control)) {
                XposedInterface.Hooker callback = "B01".equals(workload)
                        ? new TimedNoArgsHooker(trace) : new TimedReadArgsHooker(trace);
                raw = hook(target).setPriority(HookSpec.DEFAULT_PRIORITY)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(callback);
                if (raw == null) throw new IllegalStateException("missing hook handle");
            } else {
                environment = HookEnvironment.create(new LibXposed102Backend(this,
                        new LibXposed102Context(processName, loader, 1L)));
                HookSpec.Builder builder = new HookSpec.Builder(HookId.of("cpubody.measurement"),
                        HookOwner.of("cpubody-diagnostic-102"), TargetSpec.ofMember(target));
                if ("B01".equals(workload)) {
                    builder.access(ParameterAccess.NO_ARGS).before(new TimedBefore(trace)).after(new TimedAfter(trace));
                } else {
                    builder.access(ParameterAccess.READ_ARGS).before(new TimedReadArgsBefore(trace));
                }
                RegistrationReceipt receipt = environment.install(builder.build());
                if (!receipt.isInstalled() || receipt.registration() == null) throw new IllegalStateException("install failed");
                registration = receipt.registration();
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // The host will call finish once and must not infer restoration from this failure.
            return false;
        }
    }

    private synchronized boolean finish(List<Object> a) {
        if (a == null || a.size() != 1 || !(a.get(0) instanceof String)
                || activeRun == null || !activeRun.equals(a.get(0))) return false;
        if (raw != null) { raw.unhook(); raw = null; return true; }
        if (registration != null) {
            CloseReport report = registration.close();
            if (report == null || !report.newCallbacksRejected()
                    || report.entryRestoration() != CloseReport.EntryRestoration.RESTORED) return false;
            environment.close(); registration = null; return true;
        }
        if (environment != null) environment.close();
        return false;
    }

    interface CpuClock { long read(); }
    static final class Trace {
        final int[] state, before, original, after, present;
        final long[] bs, be, as, ae;
        final AtomicInteger fault;
        final Thread owner;
        final CpuClock clock;
        long previous = -1L;

        Trace(int[] state, int[] before, int[] original, int[] after, int[] present,
              long[] bs, long[] be, long[] as, long[] ae, AtomicInteger fault, Thread owner, CpuClock clock) {
            this.state = state; this.before = before; this.original = original; this.after = after; this.present = present;
            this.bs = bs; this.be = be; this.as = as; this.ae = ae; this.fault = fault; this.owner = owner; this.clock = clock;
        }
        static Trace from(Object object) {
            if (!(object instanceof Object[]) || ((Object[]) object).length != 11) throw new IllegalStateException("invalid ledger");
            Object[] a = (Object[]) object;
            Trace t = new Trace((int[]) a[0], (int[]) a[1], (int[]) a[2], (int[]) a[3], (int[]) a[4],
                    (long[]) a[5], (long[]) a[6], (long[]) a[7], (long[]) a[8], (AtomicInteger) a[9],
                    (Thread) a[10], Debug::threadCpuTimeNanos);
            int n = t.before.length;
            if (n < 1 || n > 300 || t.state.length != 6 || t.original.length != n || t.after.length != n
                    || t.present.length != n || t.bs.length != n || t.be.length != n || t.as.length != n
                    || t.ae.length != n || t.owner != Thread.currentThread()) throw new IllegalStateException("invalid ledger sizes");
            return t;
        }
        void start(boolean afterPhase) {
            if (Thread.currentThread() != owner) { fault.compareAndSet(0, 2); return; }
            int i = state[0];
            if (i < 0 || i >= before.length) { fault.compareAndSet(0, 1); return; }
            int[] counts = afterPhase ? after : before;
            counts[i]++; state[afterPhase ? 4 : 2]++;
            if (counts[i] != 1 || state[1] != (afterPhase ? 2 : 0)) { fault.compareAndSet(0, 4); return; }
            state[1] = afterPhase ? 0 : 1;
            read(i, afterPhase ? as : bs, afterPhase ? 4 : 1);
        }
        void end(boolean afterPhase) {
            if (Thread.currentThread() != owner) { fault.compareAndSet(0, 2); return; }
            int i = state[0];
            if (i < 0 || i >= before.length) { fault.compareAndSet(0, 1); return; }
            read(i, afterPhase ? ae : be, afterPhase ? 8 : 2);
        }
        private void read(int i, long[] output, int bit) {
            try {
                long value = clock.read();
                output[i] = value; present[i] |= bit;
                if (value < 0) fault.compareAndSet(0, 5);
                else if (previous >= 0 && value < previous) fault.compareAndSet(0, 6);
                if (value >= 0) previous = value;
            } catch (RuntimeException failure) { fault.compareAndSet(0, 7); }
            catch (Error failure) {
                if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) throw failure;
                fault.compareAndSet(0, 7);
            }
        }
    }

    static final class TimedNoArgsHooker implements XposedInterface.Hooker {
        final Trace trace; TimedNoArgsHooker(Trace t) { trace = t; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            trace.start(false); trace.end(false);
            Object result;
            try { result = chain.proceed(); }
            catch (Throwable original) {
                if (fatal(original)) throw original;
                try { trace.start(true); trace.end(true); }
                catch (Throwable afterFailure) {
                    if (fatal(afterFailure)) throw afterFailure;
                    if (original != afterFailure) original.addSuppressed(afterFailure);
                }
                throw original;
            }
            trace.start(true); trace.end(true);
            return result;
        }
    }
    private static boolean fatal(Throwable value) {
        return value instanceof VirtualMachineError || value instanceof ThreadDeath;
    }
    private static Throwable endBeforeAfterFailure(Trace trace, Throwable original) {
        if (fatal(original)) return original;
        try { trace.end(false); }
        catch (Throwable afterFailure) {
            if (fatal(afterFailure)) return afterFailure;
            if (original != afterFailure) original.addSuppressed(afterFailure);
        }
        return original;
    }
    static final class TimedReadArgsHooker implements XposedInterface.Hooker {
        final Trace trace; TimedReadArgsHooker(Trace t) { trace = t; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            trace.start(false);
            try { chain.getArgs().get(0); chain.getArgs().get(1); }
            catch (Throwable original) { throw endBeforeAfterFailure(trace, original); }
            trace.end(false);
            return chain.proceed();
        }
    }
    static final class TimedBefore implements BeforeCallback {
        final Trace trace; TimedBefore(Trace t) { trace = t; }
        @Override public void before(HookInvocation invocation) { trace.start(false); trace.end(false); }
    }
    static final class TimedAfter implements AfterCallback {
        final Trace trace; TimedAfter(Trace t) { trace = t; }
        @Override public void after(HookInvocation invocation, InvocationResult result) { trace.start(true); trace.end(true); }
    }
    static final class TimedReadArgsBefore implements BeforeCallback {
        final Trace trace; TimedReadArgsBefore(Trace t) { trace = t; }
        @Override public void before(HookInvocation invocation) throws Throwable {
            trace.start(false);
            try { ReadArgsInvocation args = (ReadArgsInvocation) invocation; args.getArgument(0); args.getArgument(1); }
            catch (Throwable original) { throw endBeforeAfterFailure(trace, original); }
            trace.end(false);
        }
    }
}
