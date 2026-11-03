package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.*;
import io.github.jichuo1.meguviz.hook.core.*;
import io.github.jichuo1.meguviz.hook.core.spi.*;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded disposable JVM proof for token.commit while a callback holds the environment monitor. */
public final class PreparedTokenCommitFixture {
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
            Runnable callback = beforeInstall;
            if (callback != null) callback.run();
            return fake.install(plan, channel);
        }
    }
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Throwable {
        Method first = PreparedTokenCommitFixture.class.getDeclaredMethod("first");
        Method second = PreparedTokenCommitFixture.class.getDeclaredMethod("second");
        Backend backend = new Backend();
        backend.fake.setClassLoader(PreparedTokenCommitFixture.class.getClassLoader());
        backend.fake.onTarget(first, (receiver, input) -> 1);
        backend.fake.onTarget(second, (receiver, input) -> 2);
        HookEnvironment env = HookEnvironment.create(backend,
                (target, loader) -> Collections.<Executable>singletonList(first), DiagnosticsSink.NONE);
        TargetSpec descriptor = TargetSpec.descriptor(TargetKind.METHOD, PreparedTokenCommitFixture.class.getName(), "first")
                .parameters().returning("int").match(MatchPolicy.UNIQUE).build();
        HookEnvironment.Preparation preparation = env.prepareConcurrently(
                HookSpec.builder("token.commit", "test", descriptor).replaceConstant(9).build(), 4);
        check(preparation.status() == HookEnvironment.PreparationStatus.READY, "prepare failed");
        HookEnvironment.PreparedInstall token = preparation.prepared();
        CountDownLatch tokenHeld = new CountDownLatch(1), callbackEntered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<RegistrationReceipt> committed = new AtomicReference<RegistrationReceipt>();
        AtomicReference<RegistrationReceipt> installed = new AtomicReference<RegistrationReceipt>();
        Thread owner = new Thread(() -> {
            try {
                synchronized (token) {
                    tokenHeld.countDown();
                    await(callbackEntered, "callback not entered");
                    committed.set(token.commit());
                }
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "public-token-commit-owner");
        backend.beforeInstall = () -> {
            callbackEntered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blockedOnEnvironment = false;
            while (System.nanoTime() - deadline < 0) {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(owner.getId());
                if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                        && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(env)) {
                    blockedOnEnvironment = true;
                    break;
                }
                Thread.yield();
            }
            check(blockedOnEnvironment, "commit did not block on held environment monitor");
            token.close();
            backend.beforeInstall = null;
        };
        Thread installer = new Thread(() -> {
            try {
                await(tokenHeld, "public token monitor not held");
                installed.set(env.install(HookSpec.forMember("other.install", "test", second).replaceConstant(7).build()));
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "environment-callback-owner");
        owner.setDaemon(true); installer.setDaemon(true);
        owner.start(); installer.start();
        owner.join(7000); installer.join(7000);
        check(!owner.isAlive() && !installer.isAlive(), "monitor wait did not terminate");
        if (failure.get() != null) throw new AssertionError("worker failure", failure.get());
        check(installed.get() != null && installed.get().isInstalled(), "outer install failed");
        check(committed.get() != null && committed.get().isInstalled(), "claimed commit did not complete");
        try { token.commit(); throw new AssertionError("claimed token was reusable"); }
        catch (IllegalStateException expected) { }
        check(Integer.valueOf(9).equals(backend.fake.call(first, null)), "committed hook not active");
        env.close();
        check(!backend.fake.isHooked(first) && !backend.fake.isHooked(second), "cleanup did not unhook");
        System.out.println("PRIVATE_TOKEN_COMMIT_SMOKE_OK");
    }
}
