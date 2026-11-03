package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** A stuck monitor cannot be interrupted; isolate this regression so failure cannot leak threads. */
public final class LifecycleLockRegressionTest {
    public static int target() { return 0; }

    @Test public void resolverCloseCannotProduceFalseAlreadyInstalled() throws Exception {
        Backend backend = new Backend();
        backend.onFirstUninstall = () -> { };
        java.lang.reflect.Method target = LifecycleLockRegressionTest.class.getMethod("target");
        AtomicReference<Runnable> duringResolve = new AtomicReference<Runnable>();
        HookEnvironment environment = HookEnvironment.create(backend, (descriptor, loader) -> {
            Runnable action = duringResolve.getAndSet(null);
            if (action != null) action.run();
            return Collections.singletonList(target);
        }, DiagnosticsSink.NONE);
        TargetSpec descriptor = TargetSpec.descriptor(TargetKind.METHOD,
                LifecycleLockRegressionTest.class.getName(), "target").parameters().returning("int").build();
        HookSpec spec = HookSpec.builder("resolver.reentry", "lifecycle-test", descriptor)
                .replaceConstant(1).build();
        try {
            RegistrationReceipt first = environment.install(spec);
            assertTrue(first.toString(), first.isInstalled());
            HookRegistration original = first.registration();
            duringResolve.set(original::close);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.replace(spec).status());
            assertEquals(RegistrationState.CLOSED, original.state());
            assertTrue(environment.install(spec).isInstalled());
        } finally { environment.close(); }
        assertEquals(2, backend.uninstalls.get());
    }

    @Test public void closeCoordinationFinishesWithoutLeakingThreads() throws Exception {
        Set<String> paths = new LinkedHashSet<String>();
        paths.add(System.getProperty("java.class.path"));
        for (Class<?> type : new Class<?>[] {LifecycleLockRegressionTest.class, HookEnvironment.class,
                HookSpec.class, org.junit.Assert.class}) {
            paths.add(Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        String executable = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(executable, "-cp", String.join(File.pathSeparator, paths),
                LifecycleLockRegressionTest.class.getName(), "child").redirectErrorStream(true).start();
        boolean completed = child.waitFor(20, TimeUnit.SECONDS);
        if (!completed) {
            child.destroyForcibly();
            assertTrue("test subprocess did not terminate", child.waitFor(5, TimeUnit.SECONDS));
            fail("close coordination deadlocked; subprocess terminated");
        }
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, child.exitValue());
    }

    public static void main(String[] args) throws Exception {
        concurrentEnvironmentCloseAndBackendReentry();
        distinctRegistrationsCloseConcurrently();
        directCloseAndReplaceDoNotInvertLocks();
        repeatedRegistrationCloseWaits();
        environmentCloseAllowsAnotherRegistrationToClose();
        repeatedEnvironmentCloseWaits();
        preflightMonitorReentryClosesEnvironment();
    }

    private static void concurrentEnvironmentCloseAndBackendReentry() throws Exception {
        Backend backend = new Backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        HookRegistration registration = install(environment, "close.reentry");
        backend.onFirstUninstall = () -> {
            backend.firstEntered.countDown();
            await(backend.releaseFirst);
            // Environment close is already under way and waiting on this registration.
            environment.close();
        };
        AtomicReference<CloseReport> report = new AtomicReference<CloseReport>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread direct = worker("direct-close", failure, () -> report.set(registration.close()));
        Thread whole = worker("environment-close", failure, environment::close);
        direct.start();
        try {
            assertTrue(backend.firstEntered.await(5, TimeUnit.SECONDS));
            whole.start();
            awaitBlockedOrWaiting(whole);
        } finally { backend.releaseFirst.countDown(); }
        join(direct); join(whole);
        assertNull(String.valueOf(failure.get()), failure.get());
        assertEquals(CloseReport.EntryRestoration.RESTORED, report.get().entryRestoration());
        assertSame(report.get(), registration.close());
        assertEquals(1, backend.uninstalls.get());
    }

    private static void distinctRegistrationsCloseConcurrently() throws Exception {
        Backend backend = new Backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        HookRegistration first = install(environment, "close.first");
        HookRegistration second = install(environment, "close.second");
        CountDownLatch secondUninstalled = new CountDownLatch(1);
        backend.onFirstUninstall = () -> { backend.firstEntered.countDown(); await(secondUninstalled); };
        backend.onOtherUninstall = secondUninstalled::countDown;
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread firstClose = worker("first-close", failure, first::close);
        Thread secondClose = worker("second-close", failure, second::close);
        firstClose.start();
        assertTrue(backend.firstEntered.await(5, TimeUnit.SECONDS));
        secondClose.start();
        join(firstClose); join(secondClose);
        assertNull(String.valueOf(failure.get()), failure.get());
        assertEquals(2, backend.uninstalls.get());
        environment.close();
    }

    private static void directCloseAndReplaceDoNotInvertLocks() throws Exception {
        Backend backend = new Backend();
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AtomicBoolean pauseResolution = new AtomicBoolean();
        java.lang.reflect.Method target = LifecycleLockRegressionTest.class.getMethod("target");
        HookEnvironment environment = HookEnvironment.create(backend, (descriptor, loader) -> {
            if (pauseResolution.get()) {
                resolverEntered.countDown();
                await(releaseResolver);
            }
            return Collections.singletonList(target);
        }, DiagnosticsSink.NONE);
        TargetSpec descriptor = TargetSpec.descriptor(TargetKind.METHOD,
                LifecycleLockRegressionTest.class.getName(), "target").parameters().returning("int").build();
        HookSpec first = HookSpec.builder("close.replace", "lifecycle-test", descriptor).replaceConstant(1).build();
        HookSpec replacement = HookSpec.builder("close.replace", "lifecycle-test", descriptor).replaceConstant(2).build();
        HookRegistration registration = environment.install(first).registration();
        assertNotNull(registration);
        backend.onFirstUninstall = () -> {
            backend.firstEntered.countDown();
            environment.activeRegistrationCount();
        };
        pauseResolution.set(true);
        AtomicReference<RegistrationReceipt> receipt = new AtomicReference<RegistrationReceipt>();
        AtomicReference<CloseReport> report = new AtomicReference<CloseReport>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread replace = worker("replace-during-close", failure, () -> receipt.set(environment.replace(replacement)));
        Thread close = worker("close-during-replace", failure, () -> report.set(registration.close()));
        replace.start();
        try {
            assertTrue(resolverEntered.await(5, TimeUnit.SECONDS));
            close.start();
            assertTrue(backend.firstEntered.await(5, TimeUnit.SECONDS));
        } finally { releaseResolver.countDown(); }
        join(replace); join(close);
        assertNull(String.valueOf(failure.get()), failure.get());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, receipt.get().status());
        assertEquals(CloseReport.EntryRestoration.RESTORED, report.get().entryRestoration());
        assertEquals(1, backend.uninstalls.get());
        environment.close();
    }

    private static void repeatedRegistrationCloseWaits() throws Exception {
        Backend backend = new Backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        HookRegistration registration = install(environment, "registration.repeat");
        backend.onFirstUninstall = () -> { backend.firstEntered.countDown(); await(backend.releaseFirst); };
        AtomicReference<CloseReport> firstReport = new AtomicReference<CloseReport>();
        AtomicReference<CloseReport> repeatedReport = new AtomicReference<CloseReport>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        Thread first = worker("registration-first", failure, () -> firstReport.set(registration.close()));
        Thread repeated = worker("registration-repeat", failure, () -> {
            repeatedReport.set(registration.close());
            restoredInterrupt.set(Thread.currentThread().isInterrupted());
        });
        first.start();
        try {
            assertTrue(backend.firstEntered.await(5, TimeUnit.SECONDS));
            repeated.start();
            awaitBlockedOrWaiting(repeated);
            repeated.interrupt();
        } finally { backend.releaseFirst.countDown(); }
        join(first); join(repeated);
        assertNull(String.valueOf(failure.get()), failure.get());
        assertSame(firstReport.get(), repeatedReport.get());
        assertTrue(restoredInterrupt.get());
        assertEquals(1, backend.uninstalls.get());
        environment.close();
    }

    private static void repeatedEnvironmentCloseWaits() throws Exception {
        Backend backend = new Backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        install(environment, "close.repeat");
        backend.onFirstUninstall = () -> { backend.firstEntered.countDown(); await(backend.releaseFirst); };
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CountDownLatch repeatedDone = new CountDownLatch(1);
        CountDownLatch monitoredDone = new CountDownLatch(1);
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        Thread first = worker("environment-first", failure, environment::close);
        Thread repeat = worker("environment-repeat", failure, () -> { environment.close(); repeatedDone.countDown(); });
        Thread monitored = worker("environment-monitor-repeat", failure, () -> {
            synchronized (environment) {
                environment.close();
                restoredInterrupt.set(Thread.currentThread().isInterrupted());
                monitoredDone.countDown();
            }
        });
        first.start();
        try {
            assertTrue(backend.firstEntered.await(5, TimeUnit.SECONDS));
            repeat.start();
            monitored.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((repeat.getState() != Thread.State.WAITING || monitored.getState() != Thread.State.WAITING)
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(Thread.State.WAITING, repeat.getState());
            assertEquals(Thread.State.WAITING, monitored.getState());
            assertEquals(1L, repeatedDone.getCount());
            assertEquals(1L, monitoredDone.getCount());
            monitored.interrupt();
        } finally { backend.releaseFirst.countDown(); }
        join(first); join(repeat); join(monitored);
        assertNull(String.valueOf(failure.get()), failure.get());
        assertEquals(0L, repeatedDone.getCount());
        assertEquals(0L, monitoredDone.getCount());
        assertTrue(restoredInterrupt.get());
        assertEquals(1, backend.uninstalls.get());
    }

    private static void environmentCloseAllowsAnotherRegistrationToClose() throws Exception {
        Backend backend = new Backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        install(environment, "environment.first");
        HookRegistration second = install(environment, "environment.second");
        CountDownLatch secondUninstalled = new CountDownLatch(1);
        backend.onFirstUninstall = () -> { backend.firstEntered.countDown(); await(secondUninstalled); };
        backend.onOtherUninstall = secondUninstalled::countDown;
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread whole = worker("whole-close", failure, environment::close);
        Thread secondClose = worker("other-registration-close", failure, second::close);
        whole.start();
        assertTrue(backend.firstEntered.await(5, TimeUnit.SECONDS));
        secondClose.start();
        join(secondClose); join(whole);
        assertNull(String.valueOf(failure.get()), failure.get());
        assertEquals(2, backend.uninstalls.get());
    }

    private static void preflightMonitorReentryClosesEnvironment() throws Exception {
        Backend backend = new Backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        HookRegistration registration = install(environment, "preflight.reentry");
        backend.onFirstUninstall = () -> { };
        backend.onProcess = environment::close;
        TargetSpec descriptor = TargetSpec.descriptor(TargetKind.METHOD,
                LifecycleLockRegressionTest.class.getName(), "target").parameters().returning("int").build();
        HookEnvironment.Preparation preparation = environment.prepareConcurrently(
                HookSpec.builder("preflight.test", "lifecycle-test", descriptor).replaceConstant(1).build(), 4);
        assertEquals(HookEnvironment.PreparationStatus.CLOSED, preparation.status());
        assertEquals(RegistrationState.CLOSED, registration.state());
        assertEquals(1, backend.uninstalls.get());
        environment.close();
    }

    private static HookRegistration install(HookEnvironment environment, String id) throws Exception {
        RegistrationReceipt receipt = environment.install(HookSpec.forMember(id, "lifecycle-test",
                LifecycleLockRegressionTest.class.getMethod("target")).replaceConstant(1).build());
        assertTrue(receipt.toString(), receipt.isInstalled());
        return receipt.registration();
    }

    private static Thread worker(String name, AtomicReference<Throwable> failure, Runnable task) {
        return new Thread(() -> {
            try { task.run(); } catch (Throwable problem) { failure.compareAndSet(null, problem); }
        }, name);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitBlockedOrWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.WAITING
                && System.nanoTime() < deadline) {
            if (!thread.isAlive()) fail(thread.getName() + " exited before blocking");
            Thread.sleep(1);
        }
        assertTrue(thread.getName() + " did not wait",
                thread.getState() == Thread.State.BLOCKED || thread.getState() == Thread.State.WAITING);
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5000);
        assertFalse(thread.getName() + " did not terminate", thread.isAlive());
    }

    private static final class Backend implements HookBackend, BackendContext {
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicInteger uninstalls = new AtomicInteger();
        Runnable onFirstUninstall;
        Runnable onProcess;
        Runnable onOtherUninstall = () -> { };
        @Override public String id() { return "lifecycle-test"; }
        @Override public BackendCapabilities capabilities() {
            return new BackendCapabilities.Builder(id(), ChainSemantics.FRAMEWORK_CHAIN)
                    .declare(Capability.ATOMIC_REPLACE, CapabilityResult.of(
                            CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, id(), null, null, "test"))
                    .build();
        }
        @Override public BackendContext context() { return this; }
        @Override public String processName() {
            Runnable callback = onProcess;
            onProcess = null;
            if (callback != null) callback.run();
            return "test";
        }
        @Override public ClassLoader classLoader() { return LifecycleLockRegressionTest.class.getClassLoader(); }
        @Override public long codeEpoch() { return 1L; }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) {
            return () -> {
                if (uninstalls.incrementAndGet() == 1) onFirstUninstall.run();
                else onOtherUninstall.run();
                return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                        CloseReport.PhysicalRelease.NOT_CLAIMED, false, "removed");
            };
        }
    }
}
