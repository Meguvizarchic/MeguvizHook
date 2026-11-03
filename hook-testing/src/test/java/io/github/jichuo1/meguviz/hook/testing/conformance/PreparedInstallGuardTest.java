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

/** Reentry/close checks for the isolated prototype. This is not a real framework backend. */
public final class PreparedInstallGuardTest {
    public static int first() { return 1; }
    public static int second() { return 2; }
    private static final Method FIRST;
    private static final Method SECOND;
    static {
        try { FIRST = PreparedInstallGuardTest.class.getDeclaredMethod("first"); SECOND = PreparedInstallGuardTest.class.getDeclaredMethod("second"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
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
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static HookSpec descriptor(String id) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, PreparedInstallGuardTest.class.getName(), "first")
                .parameters().returning("int").match(MatchPolicy.UNIQUE).build();
        return HookSpec.builder(id, "guard-contract", target).replaceConstant(9).build();
    }
    private static HookSpec direct(String id, Method method) {
        return HookSpec.forMember(id, "guard-contract", method).replaceConstant(9).build();
    }
    private static Object payload(HookEnvironment.PreparedInstall token) throws Exception {
        Field field = HookEnvironment.PreparedInstall.class.getDeclaredField("payload");
        field.setAccessible(true); return field.get(token);
    }
    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        volatile Runnable onInstall;
        volatile Runnable onId;
        volatile boolean installFirst;
        @Override public String id() { if (onId != null) onId.run(); return fake.id(); }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return fake.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            BackendHandle installed = installFirst ? fake.install(plan, channel) : null;
            if (onInstall != null) onInstall.run();
            return installed == null ? fake.install(plan, channel) : installed;
        }
    }
    private static HookEnvironment environment(Backend backend) {
        backend.fake.setClassLoader(PreparedInstallGuardTest.class.getClassLoader());
        backend.fake.onTarget(FIRST, (receiver, args) -> 1);
        backend.fake.onTarget(SECOND, (receiver, args) -> 2);
        return HookEnvironment.create(backend, (target, loader) -> Collections.<Executable>singletonList(FIRST), DiagnosticsSink.NONE);
    }
    private static HookEnvironment.PreparedInstall ready(HookEnvironment env, HookSpec spec) {
        HookEnvironment.Preparation p = env.prepareConcurrently(spec, 4);
        check(p.status() == HookEnvironment.PreparationStatus.READY, "prepare"); return p.prepared();
    }
    private static void closeCaughtInBackend() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        try {
            HookEnvironment.PreparedInstall token = ready(env, descriptor("caught-close"));
            AtomicInteger rejected = new AtomicInteger();
            backend.onInstall = () -> { try { env.close(); } catch (IllegalStateException expected) { rejected.incrementAndGet(); } };
            check(token.commit().isInstalled() && rejected.get() == 1, "reentrant close was not rejected");
            check(payload(token) == null && backend.fake.call(FIRST, null).equals(9), "token/result");
            backend.onInstall = null; env.close();
            check(!backend.fake.isHooked(FIRST), "close after commit did not uninstall");
        } finally { backend.onInstall = null; env.close(); }
    }
    private static void closeUncaughtAfterBackendCommit() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        try {
            HookSpec spec = descriptor("uncaught-close");
            HookEnvironment.PreparedInstall token = ready(env, spec);
            backend.installFirst = true;
            backend.onInstall = env::close;
            try { token.commit(); throw new AssertionError("close was accepted"); } catch (IllegalStateException expected) { }
            check(payload(token) == null, "claimed token retained payload");
            backend.onInstall = null;
            check(backend.fake.isHooked(FIRST) && backend.fake.call(FIRST, null).equals(1), "unknown entry not retired");
            check(env.install(spec).status() == RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, "unknown id not quarantined");
            check(backend.fake.installCount() == 1, "unexpected reinstall");
        } finally { backend.onInstall = null; env.close(); }
    }
    private static void nestedOrdinarySameIdCaught() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        try {
            HookSpec spec = descriptor("same-id"); HookEnvironment.PreparedInstall token = ready(env, spec);
            AtomicInteger rejected = new AtomicInteger();
            backend.onInstall = () -> { try { env.install(spec); } catch (IllegalStateException expected) { rejected.incrementAndGet(); } };
            check(token.commit().isInstalled() && rejected.get() == 1, "same id mutation not rejected");
            check(payload(token) == null && backend.fake.installCount() == 1, "token or duplicate install");
        } finally { backend.onInstall = null; env.close(); }
    }
    private static void nestedPreparedSameIdConsumed() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        try {
            HookSpec spec = descriptor("same-prepared");
            HookEnvironment.PreparedInstall outer = ready(env, spec), inner = ready(env, spec);
            AtomicInteger rejected = new AtomicInteger();
            backend.onInstall = () -> { try { inner.commit(); } catch (IllegalStateException expected) { rejected.incrementAndGet(); } };
            check(outer.commit().isInstalled() && rejected.get() == 1, "nested prepared commit was not rejected");
            check(payload(outer) == null && payload(inner) == null, "claimed token retained payload");
            check(backend.fake.installCount() == 1, "same-id duplicate installed");
            try { inner.commit(); throw new AssertionError("rejected token replayed"); } catch (IllegalStateException expected) { }
        } finally { backend.onInstall = null; env.close(); }
    }
    private static void nestedSameIdUncaughtAfterBackendCommit() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        try {
            HookSpec spec = descriptor("same-unknown"); HookEnvironment.PreparedInstall token = ready(env, spec);
            backend.installFirst = true; backend.onInstall = () -> env.install(spec);
            try { token.commit(); throw new AssertionError("nested mutation escaped"); } catch (IllegalStateException expected) { }
            backend.onInstall = null;
            check(payload(token) == null, "unknown token retained payload");
            check(backend.fake.isHooked(FIRST) && backend.fake.call(FIRST, null).equals(1), "unknown callback active");
            check(env.install(spec).status() == RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, "id not quarantined");
            check(backend.fake.installCount() == 1, "duplicate backend entry");
        } finally { backend.onInstall = null; env.close(); }
    }
    private static void differentIdNestedStillAllowed() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        try {
            HookEnvironment.PreparedInstall token = ready(env, descriptor("outer"));
            AtomicReference<RegistrationReceipt> inner = new AtomicReference<RegistrationReceipt>();
            backend.onInstall = () -> { backend.onInstall = null; inner.set(env.install(direct("inner", SECOND))); };
            check(token.commit().isInstalled() && inner.get() != null && inner.get().isInstalled(), "different id nested install");
            check(backend.fake.installCount() == 2, "wrong number of backend entries");
        } finally { backend.onInstall = null; env.close(); }
    }
    private static void backendIdCloseUncaughtCleansGuard() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        try {
            HookSpec spec = descriptor("id-close"); HookEnvironment.PreparedInstall token = ready(env, spec);
            backend.onId = env::close;
            try { token.commit(); throw new AssertionError("id callback close accepted"); } catch (IllegalStateException expected) { }
            check(payload(token) == null && backend.fake.installCount() == 0, "pre-backend token/side effect");
            backend.onId = null;
            check(ready(env, spec).commit().isInstalled(), "guard not cleared after failure");
        } finally { backend.onId = null; env.close(); }
    }
    private static void otherThreadCloseWaitsForCommit() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = environment(backend);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closeStarted = new CountDownLatch(1), closeDone = new CountDownLatch(1);
        AtomicReference<RegistrationReceipt> receipt = new AtomicReference<RegistrationReceipt>();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread commit = null, closer = null;
        try {
            HookEnvironment.PreparedInstall token = ready(env, descriptor("cross-thread"));
            backend.onInstall = () -> { entered.countDown(); await(release, "release backend"); };
            commit = new Thread(() -> { try { receipt.set(token.commit()); } catch (Throwable t) { error.set(t); } });
            closer = new Thread(() -> { closeStarted.countDown(); try { env.close(); } catch (Throwable t) { error.set(t); } finally { closeDone.countDown(); } });
            commit.start(); await(entered, "backend entered"); closer.start(); await(closeStarted, "closer started");
            check(!closeDone.await(100, TimeUnit.MILLISECONDS), "other thread close returned during commit");
            release.countDown(); commit.join(6000); closer.join(6000);
            check(!commit.isAlive() && !closer.isAlive() && error.get() == null, "cross-thread workers");
            check(receipt.get() != null && receipt.get().isInstalled() && !backend.fake.isHooked(FIRST), "close after commit");
            check(payload(token) == null, "finished token retained payload");
        } finally {
            release.countDown(); if (commit != null) commit.join(6000); if (closer != null) closer.join(6000);
            backend.onInstall = null; env.close();
        }
    }

    @org.junit.Test public void close_caught_backend() throws Throwable { closeCaughtInBackend(); }
    @org.junit.Test public void close_uncaught_after_commit() throws Throwable { closeUncaughtAfterBackendCommit(); }
    @org.junit.Test public void ordinary_same_id_caught() throws Throwable { nestedOrdinarySameIdCaught(); }
    @org.junit.Test public void prepared_same_id_consumed() throws Throwable { nestedPreparedSameIdConsumed(); }
    @org.junit.Test public void same_id_uncaught_after_commit() throws Throwable { nestedSameIdUncaughtAfterBackendCommit(); }
    @org.junit.Test public void different_id_nested() throws Throwable { differentIdNestedStillAllowed(); }
    @org.junit.Test public void backend_id_close_uncaught() throws Throwable { backendIdCloseUncaughtCleansGuard(); }
    @org.junit.Test public void other_thread_close_waits() throws Throwable { otherThreadCloseWaitsForCommit(); }
}
