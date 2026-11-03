package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.*;
import io.github.jichuo1.meguviz.hook.core.*;
import io.github.jichuo1.meguviz.hook.core.spi.*;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Targeted fake-backend tests of experimental preparation reentry, not device evidence. */
public final class PreparedPreparationReentryTest {
    public static int first() { return 1; }
    public static int second() { return 2; }
    private static final Method FIRST;
    private static final Method SECOND;
    static {
        try { FIRST = PreparedPreparationReentryTest.class.getDeclaredMethod("first"); SECOND = PreparedPreparationReentryTest.class.getDeclaredMethod("second"); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private interface Task { void run() throws Throwable; }
    private static int failures;
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void run(String name, Task task) {
        try { task.run(); System.out.println("CASE " + name + " PASS"); }
        catch (Throwable failure) {
            failures++; System.out.println("CASE " + name + " FAIL " + failure.getClass().getName() + ":" + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }
    private static void await(CountDownLatch latch, String name) {
        try { check(latch.await(5, TimeUnit.SECONDS), name); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static HookSpec.Builder builder(String id, String name) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, PreparedPreparationReentryTest.class.getName(), name)
                .parameters().returning("int").match(MatchPolicy.UNIQUE).build();
        return HookSpec.builder(id, "prepare-reentry", target).replaceConstant(9);
    }
    private static HookSpec spec(String id) { return builder(id, "first").build(); }
    private static void invoke(AtomicReference<Runnable> callback) {
        Runnable current = callback.getAndSet(null); if (current != null) current.run();
    }
    private static final class Context implements BackendContext {
        volatile String process = "allowed";
        volatile ClassLoader loader = PreparedPreparationReentryTest.class.getClassLoader();
        volatile long epoch = 1;
        final AtomicReference<Runnable> onProcess = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> onLoader = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> onEpoch = new AtomicReference<Runnable>();
        @Override public String processName() { invoke(onProcess); return process; }
        @Override public ClassLoader classLoader() { invoke(onLoader); return loader; }
        @Override public long codeEpoch() { invoke(onEpoch); return epoch; }
    }
    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        final Context context = new Context();
        final AtomicReference<Runnable> onContext = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> onCapabilities = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> onInstall = new AtomicReference<Runnable>();
        @Override public String id() { return fake.id(); }
        @Override public BackendCapabilities capabilities() { invoke(onCapabilities); return fake.capabilities(); }
        @Override public BackendContext context() { invoke(onContext); return context; }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            invoke(onInstall); return fake.install(plan, channel);
        }
    }
    private static HookEnvironment environment(Backend backend, MemberResolver resolver) {
        backend.fake.setClassLoader(backend.context.loader);
        backend.fake.onTarget(FIRST, (receiver, args) -> 1);
        backend.fake.onTarget(SECOND, (receiver, args) -> 2);
        return HookEnvironment.create(backend, resolver, DiagnosticsSink.NONE);
    }
    private static void expectRejected(HookEnvironment env, HookSpec spec) {
        try { env.prepareConcurrently(spec, 4); throw new AssertionError("expected reentry rejection"); }
        catch (IllegalStateException expected) { }
    }
    private static void externalMonitorRejected() {
        Backend backend = new Backend(); AtomicInteger queries = new AtomicInteger();
        HookEnvironment env = environment(backend, (t,l) -> { queries.incrementAndGet(); return Collections.<Executable>singletonList(FIRST); });
        try { synchronized (env) { expectRejected(env, spec("external-monitor")); } check(queries.get() == 0, "resolver ran under caller lock"); }
        finally { env.close(); }
    }
    private static void backendCallbackRejected() {
        Backend backend = new Backend(); AtomicInteger queries = new AtomicInteger(), rejects = new AtomicInteger();
        HookEnvironment env = environment(backend, (t,l) -> { queries.incrementAndGet(); return Collections.<Executable>singletonList(FIRST); });
        try {
            backend.onInstall.set(() -> { expectRejected(env, spec("nested-prepare")); rejects.incrementAndGet(); });
            check(env.install(HookSpec.forMember("outer", "prepare-reentry", FIRST).replaceConstant(9).build()).isInstalled(), "outer install");
            check(rejects.get() == 1 && queries.get() == 0 && backend.fake.installCount() == 1, "backend reentry");
        } finally { env.close(); }
    }
    private static void preflightCallbackRejected() {
        Backend backend = new Backend(); AtomicInteger queries = new AtomicInteger(), rejects = new AtomicInteger();
        AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        HookEnvironment env = environment(backend, (t,l) -> {
            check(!Thread.holdsLock(ref.get()), "resolver retained env monitor");
            queries.incrementAndGet(); return Collections.<Executable>singletonList(FIRST);
        });
        ref.set(env);
        try {
            backend.context.onProcess.set(() -> { expectRejected(env, spec("nested")); rejects.incrementAndGet(); });
            HookEnvironment.Preparation p = env.prepareConcurrently(spec("outer"), 4);
            check(p.status() == HookEnvironment.PreparationStatus.READY && queries.get() == 1 && rejects.get() == 1, "preflight recursion");
            p.prepared().close();
        } finally { env.close(); }
    }
    private static void normalBackgroundOutsideMonitor() throws Throwable {
        Backend backend = new Backend(); AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        CountDownLatch started = new CountDownLatch(1); AtomicReference<HookEnvironment.Preparation> result = new AtomicReference<HookEnvironment.Preparation>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        HookEnvironment env = environment(backend, (t,l) -> {
            check(!Thread.holdsLock(ref.get()), "resolver under monitor"); return Collections.<Executable>singletonList(FIRST);
        });
        ref.set(env);
        Thread worker = new Thread(() -> { started.countDown(); try { result.set(env.prepareConcurrently(spec("background"), 4)); }
                                           catch (Throwable t) { failure.set(t); } });
        try {
            synchronized (env) { worker.start(); await(started, "background start"); }
            worker.join(7000);
            check(!worker.isAlive() && failure.get() == null && result.get().status() == HookEnvironment.PreparationStatus.READY, "background prepare");
            result.get().prepared().close();
        } finally { worker.join(7000); env.close(); }
    }
    private static void sameIdRecursionCaughtAndCleared() {
        Backend backend = new Backend(); HookSpec s = spec("same"); AtomicInteger queries = new AtomicInteger(), rejects = new AtomicInteger();
        AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        HookEnvironment env = environment(backend, (t,l) -> {
            if (queries.getAndIncrement() == 0) { expectRejected(ref.get(), s); rejects.incrementAndGet(); }
            return Collections.<Executable>singletonList(FIRST);
        }); ref.set(env);
        try {
            HookEnvironment.Preparation first = env.prepareConcurrently(s, 4);
            check(first.status() == HookEnvironment.PreparationStatus.READY && rejects.get() == 1 && queries.get() == 1, "same id recursion");
            first.prepared().close();
            HookEnvironment.Preparation again = env.prepareConcurrently(s, 4);
            check(again.status() == HookEnvironment.PreparationStatus.READY && queries.get() == 2, "guard not cleared");
            again.prepared().close();
        } finally { env.close(); }
    }
    private static void sameIdRecursionUncaughtAndCleared() {
        Backend backend = new Backend(); HookSpec s = spec("same-failure"); AtomicInteger queries = new AtomicInteger();
        AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        HookEnvironment env = environment(backend, (t,l) -> {
            if (queries.getAndIncrement() == 0) ref.get().prepareConcurrently(s, 4);
            return Collections.<Executable>singletonList(FIRST);
        }); ref.set(env);
        try {
            expectRejected(env, s);
            HookEnvironment.Preparation retry = env.prepareConcurrently(s, 4);
            check(retry.status() == HookEnvironment.PreparationStatus.READY && queries.get() == 2, "uncaught guard not cleared");
            retry.prepared().close();
        } finally { env.close(); }
    }
    private static void differentIdNestedAndOuterGuardSurvives() {
        Backend backend = new Backend(); HookSpec outer = spec("outer"), inner = builder("inner", "second").build();
        AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>(); AtomicInteger queries = new AtomicInteger();
        HookEnvironment env = environment(backend, (t,l) -> {
            queries.incrementAndGet();
            if (t.memberName().equals("first")) {
                HookEnvironment.Preparation nested = ref.get().prepareConcurrently(inner, 4);
                check(nested.status() == HookEnvironment.PreparationStatus.READY, "different id nested");
                nested.prepared().close();
                expectRejected(ref.get(), outer);
                return Collections.<Executable>singletonList(FIRST);
            }
            return Collections.<Executable>singletonList(SECOND);
        }); ref.set(env);
        try {
            HookEnvironment.Preparation result = env.prepareConcurrently(outer, 4);
            check(result.status() == HookEnvironment.PreparationStatus.READY && queries.get() == 2, "nested guards");
            result.prepared().close();
        } finally { env.close(); }
    }
    private static void sameIdDifferentThreadsAllowed() throws Throwable {
        Backend backend = new Backend(); CountDownLatch entered = new CountDownLatch(2);
        HookEnvironment env = environment(backend, (t,l) -> { entered.countDown(); await(entered, "both resolvers"); return Collections.<Executable>singletonList(FIRST); });
        HookSpec s = spec("parallel-same"); AtomicReference<HookEnvironment.Preparation> a = new AtomicReference<HookEnvironment.Preparation>();
        AtomicReference<HookEnvironment.Preparation> b = new AtomicReference<HookEnvironment.Preparation>(); AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread first = new Thread(() -> { try { a.set(env.prepareConcurrently(s, 4)); } catch (Throwable t) { error.compareAndSet(null,t); } });
        Thread second = new Thread(() -> { try { b.set(env.prepareConcurrently(s, 4)); } catch (Throwable t) { error.compareAndSet(null,t); } });
        try {
            first.start(); second.start(); first.join(7000); second.join(7000);
            check(!first.isAlive() && !second.isAlive() && error.get() == null && entered.getCount() == 0, "parallel same id");
            check(a.get().status() == HookEnvironment.PreparationStatus.READY && b.get().status() == HookEnvironment.PreparationStatus.READY, "parallel results");
            a.get().prepared().close(); b.get().prepared().close();
        } finally { first.join(7000); second.join(7000); env.close(); }
    }
    private static void fatalPreservedAndGuardCleared() throws Exception {
        Backend backend = new Backend(); OutOfMemoryError fatal = new OutOfMemoryError("synthetic"); AtomicInteger calls = new AtomicInteger();
        HookEnvironment env = environment(backend, (t,l) -> { if (calls.getAndIncrement() == 0) throw fatal; return Collections.<Executable>singletonList(FIRST); });
        try {
            try { env.prepareConcurrently(spec("fatal"), 4); throw new AssertionError("fatal swallowed"); }
            catch (OutOfMemoryError observed) { check(observed == fatal, "fatal identity"); }
            Field field = HookEnvironment.class.getDeclaredField("activePreparations"); field.setAccessible(true);
            check(((ThreadLocal<?>) field.get(env)).get() == null, "thread-local retained after fatal");
            HookEnvironment.Preparation next = env.prepareConcurrently(spec("fatal"), 4);
            check(next.status() == HookEnvironment.PreparationStatus.READY, "guard survived fatal"); next.prepared().close();
        } finally { env.close(); }
    }
    private static void checkedFailureAfterStateChange(boolean close) throws Throwable {
        Backend backend = new Backend(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BackendInstallException expected = new BackendInstallException("checked resolver failure");
        HookEnvironment env = environment(backend, (t,l) -> { entered.countDown(); await(release, "release resolver"); throw expected; });
        AtomicReference<HookEnvironment.Preparation> result = new AtomicReference<HookEnvironment.Preparation>(); AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> { try { result.set(env.prepareConcurrently(spec("checked"), 4)); } catch (Throwable t) { error.set(t); } });
        try {
            worker.start(); await(entered, "resolver entered");
            if (close) env.close(); else backend.context.epoch++;
            release.countDown(); worker.join(7000);
            check(!worker.isAlive() && error.get() == null, "worker");
            check(result.get().status() == (close ? HookEnvironment.PreparationStatus.CLOSED : HookEnvironment.PreparationStatus.STALE_IDENTITY), "outcome");
            check(result.get().failure() == expected && result.get().prepared() == null, "checked error lost");
        } finally { release.countDown(); worker.join(7000); env.close(); }
    }
    private static void checkedRecheckFailure(boolean close) {
        Backend backend = new Backend(); BackendInstallException original = new BackendInstallException("resolver checked");
        IllegalStateException checkError = new IllegalStateException("identity getter failed");
        AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        HookEnvironment env = environment(backend, (t,l) -> {
            backend.context.onLoader.set(() -> { if (close) ref.get().close(); throw checkError; });
            throw original;
        }); ref.set(env);
        try {
            HookEnvironment.Preparation p = env.prepareConcurrently(spec("checked-recheck"), 4);
            check(p.status() == (close ? HookEnvironment.PreparationStatus.CLOSED : HookEnvironment.PreparationStatus.IDENTITY_CHECK_FAILED),
                    "recheck status");
            check(p.failure() == original && p.checkFailure() == checkError && p.prepared() == null, "lost exception identities");
        } finally { env.close(); }
    }
    private static void checkedRecheckFatalPreservesBoth() {
        Backend backend = new Backend(); BackendInstallException original = new BackendInstallException("resolver checked");
        OutOfMemoryError fatal = new OutOfMemoryError("identity fatal");
        HookEnvironment env = environment(backend, (t,l) -> {
            backend.context.onLoader.set(() -> { throw fatal; });
            throw original;
        });
        try {
            try { env.prepareConcurrently(spec("checked-fatal"), 4); throw new AssertionError("fatal swallowed"); }
            catch (OutOfMemoryError observed) {
                check(observed == fatal, "fatal identity");
                check(observed.getSuppressed().length == 1 && observed.getSuppressed()[0] == original, "checked not suppressed");
            }
        } finally { env.close(); }
    }
    private static void successfulResolutionRecheckFailureVisible() {
        Backend backend = new Backend(); IllegalStateException checkError = new IllegalStateException("identity getter failed");
        HookEnvironment env = environment(backend, (t,l) -> {
            backend.context.onLoader.set(() -> { throw checkError; });
            return Collections.<Executable>singletonList(FIRST);
        });
        try {
            HookEnvironment.Preparation p = env.prepareConcurrently(spec("successful-recheck"), 4);
            check(p.status() == HookEnvironment.PreparationStatus.IDENTITY_CHECK_FAILED && p.failure() == null
                    && p.checkFailure() == checkError && p.prepared() == null, "recheck error not exposed");
        } finally { env.close(); }
    }
    private static void recheckDifferentIdRecursionRejectedAndGuardCleared() {
        Backend backend = new Backend();
        HookSpec outer = spec("recheck-outer"), inner = builder("recheck-inner", "second").build();
        AtomicInteger queries = new AtomicInteger(), rejects = new AtomicInteger();
        AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        HookEnvironment env = environment(backend, (target, loader) -> {
            int call = queries.incrementAndGet();
            if (call == 1) {
                backend.context.onLoader.set(() -> {
                    expectRejected(ref.get(), inner);
                    rejects.incrementAndGet();
                });
            }
            return Collections.<Executable>singletonList(target.memberName().equals("second") ? SECOND : FIRST);
        });
        ref.set(env);
        try {
            HookEnvironment.Preparation first = env.prepareConcurrently(outer, 4);
            check(first.status() == HookEnvironment.PreparationStatus.READY && first.prepared() != null,
                    "outer preparation failed after rejected identity reentry");
            check(rejects.get() == 1 && queries.get() == 1, "identity reentry reached resolver or was not rejected");
            first.prepared().close();
            HookEnvironment.Preparation next = env.prepareConcurrently(inner, 4);
            check(next.status() == HookEnvironment.PreparationStatus.READY && next.prepared() != null,
                    "identity check guard survived after preparation");
            check(queries.get() == 2 && rejects.get() == 1, "independent preparation did not resolve once");
            next.prepared().close();
        } finally { env.close(); }
    }
    private static void processCloseWinsOverFiltered() {
        Backend backend = new Backend(); backend.context.process = "other"; AtomicInteger calls = new AtomicInteger();
        HookEnvironment env = environment(backend, (t,l) -> { calls.incrementAndGet(); return Collections.<Executable>singletonList(FIRST); });
        backend.context.onProcess.set(env::close);
        HookEnvironment.Preparation p = env.prepareConcurrently(builder("filter-close", "first").processes(ProcessFilter.only("allowed")).build(), 4);
        check(p.status() == HookEnvironment.PreparationStatus.CLOSED && p.prepared() == null && calls.get() == 0, "closed vs filtered");
    }
    private static void capabilityCloseWinsOverUnsupported() {
        Backend backend = new Backend(); AtomicInteger calls = new AtomicInteger();
        HookEnvironment env = environment(backend, (t,l) -> { calls.incrementAndGet(); return Collections.<Executable>singletonList(FIRST); });
        backend.onCapabilities.set(env::close);
        HookEnvironment.Preparation p = env.prepareConcurrently(builder("cap-close", "first").require(Capability.HOT_RELOAD).build(), 4);
        check(p.status() == HookEnvironment.PreparationStatus.CLOSED && p.prepared() == null && calls.get() == 0, "closed vs unsupported");
    }
    private static void identityCloseWinsOverStale() {
        Backend backend = new Backend(); AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        HookEnvironment env = environment(backend, (t,l) -> {
            backend.context.onLoader.set(() -> { backend.context.loader = new ClassLoader(PreparedPreparationReentryTest.class.getClassLoader()) { }; ref.get().close(); });
            return Collections.<Executable>singletonList(FIRST);
        }); ref.set(env);
        HookEnvironment.Preparation p = env.prepareConcurrently(spec("identity-close"), 4);
        check(p.status() == HookEnvironment.PreparationStatus.CLOSED && p.prepared() == null, "closed vs stale");
    }

    @org.junit.Test public void external_monitor() throws Throwable { externalMonitorRejected(); }
    @org.junit.Test public void backend_callback() throws Throwable { backendCallbackRejected(); }
    @org.junit.Test public void preflight_callback() throws Throwable { preflightCallbackRejected(); }
    @org.junit.Test public void background_outside_monitor() throws Throwable { normalBackgroundOutsideMonitor(); }
    @org.junit.Test public void same_id_caught() throws Throwable { sameIdRecursionCaughtAndCleared(); }
    @org.junit.Test public void same_id_uncaught() throws Throwable { sameIdRecursionUncaughtAndCleared(); }
    @org.junit.Test public void different_id_nested() throws Throwable { differentIdNestedAndOuterGuardSurvives(); }
    @org.junit.Test public void same_id_parallel() throws Throwable { sameIdDifferentThreadsAllowed(); }
    @org.junit.Test public void fatal_identity_cleanup() throws Throwable { fatalPreservedAndGuardCleared(); }
    @org.junit.Test public void checked_closed() throws Throwable { checkedFailureAfterStateChange(true); }
    @org.junit.Test public void checked_stale() throws Throwable { checkedFailureAfterStateChange(false); }
    @org.junit.Test public void checked_recheck_error() throws Throwable { checkedRecheckFailure(false); }
    @org.junit.Test public void checked_close_recheck_error() throws Throwable { checkedRecheckFailure(true); }
    @org.junit.Test public void checked_recheck_fatal() throws Throwable { checkedRecheckFatalPreservesBoth(); }
    @org.junit.Test public void success_recheck_error() throws Throwable { successfulResolutionRecheckFailureVisible(); }
    @org.junit.Test public void recheck_different_id_guard_cleared() throws Throwable { recheckDifferentIdRecursionRejectedAndGuardCleared(); }
    @org.junit.Test public void closed_before_filtered() throws Throwable { processCloseWinsOverFiltered(); }
    @org.junit.Test public void closed_before_unsupported() throws Throwable { capabilityCloseWinsOverUnsupported(); }
    @org.junit.Test public void closed_before_stale() throws Throwable { identityCloseWinsOverStale(); }
}
