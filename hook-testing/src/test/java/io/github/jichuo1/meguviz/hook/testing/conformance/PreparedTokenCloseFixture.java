package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.*;
import io.github.jichuo1.meguviz.hook.core.*;
import io.github.jichuo1.meguviz.hook.core.spi.*;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Disposable JVM proof that a caller-owned token monitor cannot block environment callbacks. */
public final class PreparedTokenCloseFixture {
    public static int first() { return 1; }
    public static int second() { return 2; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void await(CountDownLatch latch, String message) {
        try { check(latch.await(5, TimeUnit.SECONDS), message); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        volatile Runnable beforeInstall;
        @Override public String id() { return fake.id(); }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return fake.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            if (beforeInstall != null) beforeInstall.run();
            return fake.install(plan, channel);
        }
    }
    public static void main(String[] args) throws Throwable {
        Method first = PreparedTokenCloseFixture.class.getDeclaredMethod("first");
        Method second = PreparedTokenCloseFixture.class.getDeclaredMethod("second");
        Backend backend = new Backend();
        backend.fake.setClassLoader(PreparedTokenCloseFixture.class.getClassLoader());
        backend.fake.onTarget(second, (receiver, input) -> 2);
        HookEnvironment env = HookEnvironment.create(backend,
                (target, loader) -> Collections.<Executable>singletonList(first), DiagnosticsSink.NONE);
        TargetSpec descriptor = TargetSpec.descriptor(TargetKind.METHOD, PreparedTokenCloseFixture.class.getName(), "first")
                .parameters().returning("int").match(MatchPolicy.UNIQUE).build();
        HookSpec prepared = HookSpec.builder("private.token", "test", descriptor).replaceConstant(9).build();
        HookEnvironment.Preparation preparation = env.prepareConcurrently(prepared, 4);
        check(preparation.status() == HookEnvironment.PreparationStatus.READY, "prepare failed");
        HookEnvironment.PreparedInstall token = preparation.prepared();
        CountDownLatch tokenHeld = new CountDownLatch(1), backendEntered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<RegistrationReceipt> installed = new AtomicReference<RegistrationReceipt>();
        backend.beforeInstall = () -> {
            backendEntered.countDown();
            token.close();
        };
        Thread owner = new Thread(() -> {
            try {
                synchronized (token) {
                    tokenHeld.countDown();
                    await(backendEntered, "backend not entered");
                    env.activeRegistrationCount();
                }
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "public-token-monitor-owner");
        Thread installer = new Thread(() -> {
            try {
                await(tokenHeld, "token not held");
                installed.set(env.install(HookSpec.forMember("private.other", "test", second).replaceConstant(7).build()));
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "environment-monitor-owner");
        owner.setDaemon(true);
        installer.setDaemon(true);
        owner.start(); installer.start();
        owner.join(7000); installer.join(7000);
        check(!owner.isAlive() && !installer.isAlive(), "monitor wait did not terminate");
        if (failure.get() != null) throw new AssertionError("worker failure", failure.get());
        check(installed.get() != null && installed.get().isInstalled(), "install did not finish");
        try { token.commit(); throw new AssertionError("closed token was reusable"); }
        catch (IllegalStateException expected) { }
        backend.beforeInstall = null;
        env.close();
        check(!backend.fake.isHooked(second), "cleanup did not unhook");
        System.out.println("PRIVATE_TOKEN_MONITOR_SMOKE_OK");
    }
}
