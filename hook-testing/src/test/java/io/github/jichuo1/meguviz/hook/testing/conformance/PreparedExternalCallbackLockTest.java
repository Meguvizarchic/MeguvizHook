package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verifies that external preparation callbacks do not hold the environment monitor. */
public final class PreparedExternalCallbackLockTest {
    public static int target() { return 1; }

    @Test public void blockedProcessNameDoesNotPreventCloseOrStartResolver() throws Exception {
        exerciseCloseDuringContext(false, false);
    }

    @Test public void blockedIdentityRecheckPreservesResolverFailureWhenCloseWins() throws Exception {
        exerciseCloseDuringContext(true, false);
    }

    @Test public void failingIdentityRecheckAfterClosePreservesBothFailures() throws Exception {
        exerciseCloseDuringContext(true, true);
    }

    private static void exerciseCloseDuringContext(boolean recheck, boolean failGetter) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicReference<Throwable> workerError = new AtomicReference<Throwable>();
        AtomicReference<HookEnvironment.Preparation> preparation = new AtomicReference<HookEnvironment.Preparation>();
        BackendInstallException resolverFailure = new BackendInstallException("synthetic resolver failure");
        IllegalStateException getterFailure = new IllegalStateException("synthetic identity check failure");
        Context context = new Context();
        Backend backend = new Backend(context);
        Runnable blockedGetter = () -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("context release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            if (failGetter) throw getterFailure;
        };
        if (!recheck) context.processAction.set(blockedGetter);
        MemberResolver resolver = (descriptor, loader) -> {
            resolutions.incrementAndGet();
            if (recheck) {
                context.loaderAction.set(blockedGetter);
                throw resolverFailure;
            }
            throw new AssertionError("resolver must not run after close");
        };
        HookEnvironment env = HookEnvironment.create(backend, resolver, DiagnosticsSink.NONE);
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD,
                PreparedExternalCallbackLockTest.class.getName(), "target")
                .parameters().returning("int").match(MatchPolicy.UNIQUE).build();
        HookSpec spec = HookSpec.builder("prepare.context.lock", "test", target).replaceConstant(7).build();
        Thread prepareThread = new Thread(() -> {
            try { preparation.set(env.prepareConcurrently(spec, 4)); }
            catch (Throwable error) { workerError.compareAndSet(null, error); }
        }, "preparation-context-callback");
        Thread closeThread = new Thread(() -> {
            try { env.close(); }
            catch (Throwable error) { workerError.compareAndSet(null, error); }
            finally { closed.countDown(); release.countDown(); }
        }, "preparation-context-close");
        // A broken implementation must not keep the test JVM alive after a bounded failure.
        prepareThread.setDaemon(true);
        closeThread.setDaemon(true);
        try {
            prepareThread.start();
            assertTrue("context getter was not reached", entered.await(5, TimeUnit.SECONDS));
            closeThread.start();
            assertTrue("close waited on a context getter", closed.await(5, TimeUnit.SECONDS));
            closeThread.join(5000);
            prepareThread.join(5000);
            assertFalse(closeThread.isAlive());
            assertFalse(prepareThread.isAlive());
            assertNull(workerError.get());
            HookEnvironment.Preparation result = preparation.get();
            assertNotNull(result);
            assertEquals(HookEnvironment.PreparationStatus.CLOSED, result.status());
            assertNull(result.prepared());
            if (recheck) {
                assertEquals(1, resolutions.get());
                assertSame(resolverFailure, result.failure());
                if (failGetter) assertSame(getterFailure, result.checkFailure());
                else assertNull(result.checkFailure());
            } else {
                assertEquals(0, resolutions.get());
                assertNull(result.failure());
                assertNull(result.checkFailure());
            }
        } finally {
            release.countDown();
            prepareThread.join(12000);
            if (closeThread.getState() != Thread.State.NEW) closeThread.join(12000);
            if (prepareThread.isAlive()) prepareThread.interrupt();
            if (closeThread.isAlive()) closeThread.interrupt();
            prepareThread.join(2000);
            if (closeThread.getState() != Thread.State.NEW) closeThread.join(2000);
            assertFalse("preparation thread did not terminate", prepareThread.isAlive());
            assertFalse("close thread did not terminate", closeThread.isAlive());
            env.close();
        }
    }

    private static final class Context implements BackendContext {
        final AtomicReference<Runnable> processAction = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> loaderAction = new AtomicReference<Runnable>();
        @Override public String processName() { runOnce(processAction); return "allowed"; }
        @Override public ClassLoader classLoader() { runOnce(loaderAction); return PreparedExternalCallbackLockTest.class.getClassLoader(); }
        @Override public long codeEpoch() { return 1L; }
        private static void runOnce(AtomicReference<Runnable> action) {
            Runnable callback = action.getAndSet(null);
            if (callback != null) callback.run();
        }
    }

    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        final BackendContext context;
        Backend(BackendContext context) { this.context = context; }
        @Override public String id() { return fake.id(); }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return context; }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            return fake.install(plan, channel);
        }
    }
}
