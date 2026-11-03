package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Local concurrency contracts for a close request; not a real framework or device test. */
public final class EnvironmentRequestCloseTest {
    public static int existingTarget() { return 11; }
    public static int inFlightTarget() { return 22; }

    private static final Method EXISTING = method("existingTarget");
    private static final Method IN_FLIGHT = method("inFlightTarget");

    private static Method method(String name) {
        try { return EnvironmentRequestCloseTest.class.getDeclaredMethod(name); }
        catch (NoSuchMethodException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static void await(CountDownLatch latch, String message) {
        try { assertTrue(message, latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        final AtomicReference<Runnable> onId = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> onInstall = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> onReplace = new AtomicReference<Runnable>();
        final AtomicReference<Runnable> onUninstall = new AtomicReference<Runnable>();
        Backend() {
            fake.setClassLoader(EnvironmentRequestCloseTest.class.getClassLoader());
            fake.onTarget(EXISTING, (receiver, args) -> 11);
            fake.onTarget(IN_FLIGHT, (receiver, args) -> 22);
        }
        @Override public String id() {
            Runnable action = onId.getAndSet(null);
            if (action != null) action.run();
            return fake.id();
        }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return fake.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            Runnable action = onInstall.getAndSet(null);
            if (action != null) action.run();
            BackendHandle handle = fake.install(plan, channel);
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() {
                    Runnable before = onUninstall.getAndSet(null);
                    if (before != null) before.run();
                    return handle.uninstall();
                }
                @Override public BackendHandle replace(BoundPlan next, DispatchChannel nextChannel)
                        throws BackendInstallException {
                    Runnable before = onReplace.getAndSet(null);
                    if (before != null) before.run();
                    return handle.replace(next, nextChannel);
                }
            };
        }
    }

    private static HookSpec direct(String id, Method target, int constant) {
        return HookSpec.forMember(id, "request-close-test", target).replaceConstant(constant).build();
    }

    private static HookSpec descriptor(String id) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD,
                EnvironmentRequestCloseTest.class.getName(), "inFlightTarget")
                .parameters().returning("int").build();
        return HookSpec.builder(id, "request-close-test", target).replaceConstant(33).build();
    }

    private static HookEnvironment environment(Backend backend) {
        return HookEnvironment.create(backend, (target, loader) -> Collections.singletonList(IN_FLIGHT),
                DiagnosticsSink.NONE);
    }

    @Test public void requestReturnsDuringLockedInstallAndRejectsOnlyNewWork() throws Throwable {
        Backend backend = new Backend();
        HookEnvironment env = environment(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec existing = HookSpec.forMember("existing", "request-close-test", EXISTING)
                .before(invocation -> callbacks.incrementAndGet()).build();
        RegistrationReceipt old = env.install(existing);
        assertTrue(old.isInstalled());
        HookEnvironment.Preparation ready = env.prepareConcurrently(descriptor("prepared"), 4);
        assertEquals(HookEnvironment.PreparationStatus.READY, ready.status());
        HookEnvironment.PreparedInstall token = ready.prepared();
        CountDownLatch installing = new CountDownLatch(1);
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<RegistrationReceipt> admitted = new AtomicReference<RegistrationReceipt>();
        backend.onInstall.set(() -> { installing.countDown(); await(requested, "request did not return"); });
        Thread installer = new Thread(() -> {
            try { admitted.set(env.install(direct("in-flight", IN_FLIGHT, 99))); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "request-close-inflight-install");
        Thread requester = new Thread(() -> {
            try {
                env.requestClose();
                env.requestClose();
                if (!env.isCloseRequested()) throw new AssertionError("request not visible");
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { requested.countDown(); }
        }, "request-close-caller");
        installer.setDaemon(true); requester.setDaemon(true);
        try {
            installer.start();
            assertTrue("backend install not entered", installing.await(5, TimeUnit.SECONDS));
            requester.start();
            assertTrue("request waited for the environment monitor", requested.await(2, TimeUnit.SECONDS));
            installer.join(5000); requester.join(5000);
            assertFalse(installer.isAlive()); assertFalse(requester.isAlive());
            assertNull(failure.get());
            assertNotNull(admitted.get());
            assertEquals(RegistrationReceipt.Status.INSTALLED, admitted.get().status());
            assertNotNull(admitted.get().registration());
            assertTrue(backend.fake.isHooked(EXISTING));
            assertTrue(backend.fake.isHooked(IN_FLIGHT));
            assertEquals(11, backend.fake.call(EXISTING, null));
            assertEquals(1, callbacks.get());
            assertEquals(99, backend.fake.call(IN_FLIGHT, null));

            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    env.install(direct("new", EXISTING, 44)).status());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.replace(existing).status());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.forceReplace(existing).status());
            assertEquals(0, backend.fake.replaceCount());
            AtomicInteger adoptions = new AtomicInteger();
            BackendAdoption adoption = new BackendAdoption() {
                @Override public HookBackend backend() { return backend; }
                @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) {
                    adoptions.incrementAndGet(); throw new AssertionError("adoption should not start");
                }
            };
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    env.adopt(direct("adopt", EXISTING, 55), adoption).status());
            assertEquals(0, adoptions.get());
            assertEquals(HookEnvironment.PreparationStatus.CLOSED,
                    env.prepareConcurrently(descriptor("later"), 4).status());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, token.commit().status());
            assertEquals(2, backend.fake.installCount());
            assertTrue("request itself must not uninstall", backend.fake.isHooked(EXISTING));
        } finally {
            requested.countDown();
            installer.join(7000);
            if (requester.getState() != Thread.State.NEW) requester.join(7000);
            if (!installer.isAlive() && !requester.isAlive()) env.close();
        }
        assertFalse(installer.isAlive()); assertFalse(requester.isAlive());
        assertFalse(backend.fake.isHooked(EXISTING));
        assertFalse(backend.fake.isHooked(IN_FLIGHT));
        assertEquals(11, backend.fake.call(EXISTING, null));
        assertEquals(1, callbacks.get());
    }

    @Test public void requestDuringPreflightRejectsBeforeBackendInstallation() throws Exception {
        Backend backend = new Backend();
        HookEnvironment env = environment(backend);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), requested = new CountDownLatch(1);
        AtomicReference<RegistrationReceipt> result = new AtomicReference<RegistrationReceipt>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        backend.onId.set(() -> { entered.countDown(); await(release, "preflight release timeout"); });
        Thread installer = new Thread(() -> {
            try { result.set(env.install(direct("preflight", EXISTING, 1))); }
            catch (Throwable error) { failure.set(error); }
        }, "request-close-preflight");
        Thread requester = new Thread(() -> {
            try { env.requestClose(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { requested.countDown(); }
        }, "request-close-preflight-requester");
        installer.setDaemon(true); requester.setDaemon(true);
        try {
            installer.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            requester.start();
            assertTrue("request waited for preflight's environment monitor", requested.await(2, TimeUnit.SECONDS));
            assertTrue(env.isCloseRequested());
        } finally {
            release.countDown();
            installer.join(7000);
            if (requester.getState() != Thread.State.NEW) requester.join(7000);
            if (!installer.isAlive() && !requester.isAlive()) env.close();
        }
        assertFalse(installer.isAlive()); assertFalse(requester.isAlive());
        assertNull(failure.get());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, result.get().status());
        assertEquals(0, backend.fake.installCount());
    }

    @Test public void requestDuringLockedReplaceKeepsTheNewGenerationUntilClose() throws Throwable {
        Backend backend = new Backend();
        HookEnvironment env = environment(backend);
        RegistrationReceipt original = env.install(direct("replace-race", EXISTING, 1));
        assertTrue(original.isInstalled());
        assertEquals(1, backend.fake.call(EXISTING, null));
        CountDownLatch replacing = new CountDownLatch(1), requested = new CountDownLatch(1);
        AtomicReference<RegistrationReceipt> replacement = new AtomicReference<RegistrationReceipt>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        backend.onReplace.set(() -> { replacing.countDown(); await(requested, "replace request did not return"); });
        Thread replacer = new Thread(() -> {
            try { replacement.set(env.replace(direct("replace-race", EXISTING, 2))); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "request-close-inflight-replace");
        Thread requester = new Thread(() -> {
            try { env.requestClose(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { requested.countDown(); }
        }, "request-close-during-replace");
        replacer.setDaemon(true); requester.setDaemon(true);
        try {
            replacer.start();
            assertTrue("backend replace not entered", replacing.await(5, TimeUnit.SECONDS));
            requester.start();
            assertTrue("request waited for the replacement's environment monitor", requested.await(2, TimeUnit.SECONDS));
            replacer.join(5000); requester.join(5000);
            assertFalse(replacer.isAlive()); assertFalse(requester.isAlive());
            assertNull(failure.get());
            RegistrationReceipt next = replacement.get();
            assertNotNull(next);
            assertEquals(RegistrationReceipt.Status.INSTALLED, next.status());
            assertNotNull(next.registration());
            assertTrue(next.generation() > original.generation());
            assertEquals(2, backend.fake.call(EXISTING, null));
            assertEquals(1, backend.fake.replaceCount());
            original.registration().close();
            assertTrue("old generation must not uninstall the replacement", backend.fake.isHooked(EXISTING));
            assertEquals(2, backend.fake.call(EXISTING, null));
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    env.forceReplace(direct("replace-race", EXISTING, 3)).status());
            assertEquals(1, backend.fake.replaceCount());
        } finally {
            requested.countDown();
            replacer.join(7000);
            if (requester.getState() != Thread.State.NEW) requester.join(7000);
            if (!replacer.isAlive() && !requester.isAlive()) env.close();
        }
        assertFalse(replacer.isAlive()); assertFalse(requester.isAlive());
        assertFalse(backend.fake.isHooked(EXISTING));
        assertEquals(11, backend.fake.call(EXISTING, null));
    }

    @Test public void closeStillWaitsForUninstallAfterRequest() throws Exception {
        Backend backend = new Backend();
        HookEnvironment env = environment(backend);
        assertTrue(env.install(direct("wait-close", EXISTING, 7)).isInstalled());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        backend.onUninstall.set(() -> { entered.countDown(); await(release, "uninstall release timeout"); });
        env.requestClose();
        assertTrue(backend.fake.isHooked(EXISTING));
        Thread closer = new Thread(() -> {
            try { env.close(); } catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        }, "request-close-sync-uninstall");
        closer.setDaemon(true);
        try {
            closer.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse("close returned before uninstall finished", done.await(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            closer.join(7000);
        }
        assertFalse(closer.isAlive());
        assertNull(failure.get());
        assertFalse(backend.fake.isHooked(EXISTING));
        assertTrue(env.isCloseRequested());
        env.close();
    }

    @Test public void rejectedReentrantCloseDoesNotPublishARequest() {
        Backend backend = new Backend();
        HookEnvironment env = environment(backend);
        AtomicInteger rejected = new AtomicInteger();
        backend.onInstall.set(() -> {
            assertThrows(IllegalStateException.class, env::close);
            assertFalse(env.isCloseRequested());
            rejected.incrementAndGet();
        });
        try {
            assertTrue(env.install(direct("reentrant-close", EXISTING, 3)).isInstalled());
            assertEquals(1, rejected.get());
            assertFalse(env.isCloseRequested());
        } finally { env.close(); }
        assertTrue(env.isCloseRequested());
    }
}
