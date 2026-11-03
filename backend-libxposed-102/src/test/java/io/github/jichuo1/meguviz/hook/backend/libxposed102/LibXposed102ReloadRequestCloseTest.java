package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled JVM proxies exercise SDK integration, not a real framework or Android runtime. */
public final class LibXposed102ReloadRequestCloseTest {
    public static int target(int value) { return value; }

    private static HookSpec spec(String id, Method target) {
        return HookSpec.forMember(id, "reload-test", target).replaceConstant(42).build();
    }

    @Test public void requestDoesNotWaitForFrameworkAndClaimedAdoptionCompletes() throws Throwable {
        Stub stub = new Stub();
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param());
        BackendAdoption token = session.adoption(stub.old);
        CountDownLatch inFramework = new CountDownLatch(1);
        CountDownLatch requestReturned = new CountDownLatch(1);
        AtomicReference<RegistrationReceipt> receipt = new AtomicReference<RegistrationReceipt>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        stub.onReplace.set(() -> {
            inFramework.countDown();
            assertTrue("framework waited too long for request", requestReturned.await(5, TimeUnit.SECONDS));
        });
        Thread adopter = new Thread(() -> {
            try { receipt.set(env.adopt(spec("reload.request.active", stub.target), token)); }
            catch (Throwable problem) { failure.compareAndSet(null, problem); }
        }, "reload-adopter");
        Thread requester = new Thread(() -> {
            try {
                session.requestClose();
                assertTrue(session.isCloseRequested());
            } catch (Throwable problem) { failure.compareAndSet(null, problem); }
            finally { requestReturned.countDown(); }
        }, "reload-requester");
        adopter.setDaemon(true);
        requester.setDaemon(true);
        try {
            assertFalse(session.isCloseRequested());
            adopter.start();
            assertTrue("replacement was not entered", inFramework.await(5, TimeUnit.SECONDS));
            requester.start();
            assertTrue("request waited for the session monitor", requestReturned.await(2, TimeUnit.SECONDS));
            adopter.join(5000);
            requester.join(5000);
            assertFalse("adopter remained blocked", adopter.isAlive());
            assertFalse("requester remained blocked", requester.isAlive());
            if (failure.get() != null) throw failure.get();
            assertNotNull(receipt.get());
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipt.get().status());
            assertEquals(1, stub.replacements.get());
            assertEquals(42, stub.invoke());
            assertThrows(IllegalStateException.class, session::oldHandles);
            assertThrows(IllegalStateException.class, () -> session.adoption(stub.old));
            assertThrows(IllegalStateException.class,
                    () -> env.adopt(spec("reload.request.reuse", stub.target), token));
            assertEquals(1, stub.replacements.get());
            assertEquals(CloseReport.EntryRestoration.RESTORED,
                    receipt.get().registration().close().entryRestoration());
            assertEquals(0, stub.oldUnhooks.get());
            assertEquals(1, stub.newUnhooks.get());
        } finally {
            requestReturned.countDown();
            adopter.join(6000);
            requester.join(6000);
            if (!adopter.isAlive() && !requester.isAlive()) {
                session.close();
                env.close();
            }
        }
        assertTrue(session.isCloseRequested());
        assertEquals(1, stub.newUnhooks.get());
    }

    @Test public void requestBeforeClaimIsKnownUncommittedButClosedTokenStillThrows() throws Exception {
        Stub stub = new Stub();
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param());
        BackendAdoption token = session.adoption(stub.old);
        try {
            session.requestClose();
            session.requestClose();
            assertTrue(session.isCloseRequested());
            assertThrows(IllegalStateException.class, session::oldHandles);
            assertThrows(IllegalStateException.class, () -> session.adoption(stub.old));
            RegistrationReceipt rejected = env.adopt(spec("reload.request.first", stub.target), token);
            assertEquals(RegistrationReceipt.Status.FAILED, rejected.status());
            assertNull(rejected.registration());
            assertEquals(0, stub.executableReads.get());
            assertEquals(0, stub.replacements.get());
            // A known-uncommitted rejection must not quarantine this logical ID.
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.adopt(spec("reload.request.first", stub.target), token).status());
            assertEquals(0, stub.replacements.get());
            session.close();
            assertThrows(IllegalStateException.class,
                    () -> env.adopt(spec("reload.request.closed", stub.target), token));
            assertEquals(0, stub.replacements.get());
            assertEquals(0, stub.oldUnhooks.get());
            assertEquals(0, stub.newUnhooks.get());
        } finally {
            session.close();
            env.close();
        }
    }

    @Test public void requestDuringPrecheckDoesNotCancelClaimAndReentrantReuseFails() throws Exception {
        Stub stub = new Stub();
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        HookEnvironment nestedEnv = HookEnvironment.create(backend);
        LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param());
        BackendAdoption token = session.adoption(stub.old);
        try {
            // getExecutable runs after token admission. A same-thread request is safe and does not
            // revoke the admitted replacement, while a second use still cannot reach the framework.
            stub.onGetExecutable.set(session::requestClose);
            stub.onReplace.set(() -> assertThrows(IllegalStateException.class,
                    () -> nestedEnv.adopt(spec("reload.request.nested", stub.target), token)));
            RegistrationReceipt accepted = env.adopt(spec("reload.request.precheck", stub.target), token);
            assertEquals(RegistrationReceipt.Status.INSTALLED, accepted.status());
            assertTrue(session.isCloseRequested());
            assertEquals(1, stub.executableReads.get());
            assertEquals(1, stub.replacements.get());
            assertEquals(CloseReport.EntryRestoration.RESTORED,
                    accepted.registration().close().entryRestoration());
            assertEquals(0, stub.oldUnhooks.get());
            assertEquals(1, stub.newUnhooks.get());
        } finally {
            session.close();
            nestedEnv.close();
            env.close();
        }
    }

    @Test public void closeReenteredFromTargetPrecheckIsKnownUncommitted() throws Exception {
        Stub stub = new Stub();
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param());
        BackendAdoption token = session.adoption(stub.old);
        try {
            stub.onGetExecutable.set(session::close);
            RegistrationReceipt rejected = env.adopt(spec("reload.precheck.close", stub.target), token);
            assertEquals(RegistrationReceipt.Status.FAILED, rejected.status());
            assertNull(rejected.registration());
            assertEquals(1, stub.executableReads.get());
            assertEquals(0, stub.replacements.get());
            assertEquals(0, stub.oldUnhooks.get());
            assertEquals(0, stub.newUnhooks.get());

            // A fresh token for the same untouched handle can use the same logical ID: the first
            // rejected attempt must not leave the environment in unknown-outcome quarantine.
            stub.onGetExecutable.set(null);
            try (LibXposed102ReloadSession fresh = new LibXposed102ReloadSession(backend, stub.param())) {
                RegistrationReceipt retry = env.adopt(spec("reload.precheck.close", stub.target),
                        fresh.adoption(stub.old));
                assertEquals(RegistrationReceipt.Status.INSTALLED, retry.status());
                assertEquals(1, stub.replacements.get());
                assertEquals(CloseReport.EntryRestoration.RESTORED,
                        retry.registration().close().entryRestoration());
            }
            assertEquals(0, stub.oldUnhooks.get());
            assertEquals(1, stub.newUnhooks.get());
        } finally {
            session.close();
            env.close();
        }
    }

    @Test public void nestedAdoptionDuringTargetPrecheckCannotReplaceTwice() throws Exception {
        Stub stub = new Stub();
        LibXposed102Backend backend = stub.backend();
        HookEnvironment outerEnv = HookEnvironment.create(backend);
        HookEnvironment nestedEnv = HookEnvironment.create(backend);
        LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param());
        BackendAdoption token = session.adoption(stub.old);
        AtomicBoolean entered = new AtomicBoolean();
        AtomicReference<RegistrationReceipt> nestedReceipt = new AtomicReference<RegistrationReceipt>();
        try {
            stub.onGetExecutable.set(() -> {
                if (entered.compareAndSet(false, true)) {
                    nestedReceipt.set(nestedEnv.adopt(spec("reload.precheck.nested", stub.target), token));
                    // Both attempted and closed are now true; the outer attempt must not claim
                    // the nested framework replacement was uncommitted.
                    session.close();
                }
            });
            assertThrows(IllegalStateException.class,
                    () -> outerEnv.adopt(spec("reload.precheck.outer", stub.target), token));
            assertNotNull(nestedReceipt.get());
            assertEquals(RegistrationReceipt.Status.INSTALLED, nestedReceipt.get().status());
            assertTrue(session.isCloseRequested());
            assertEquals(2, stub.executableReads.get());
            assertEquals(1, stub.replacements.get());
            assertEquals(0, stub.oldUnhooks.get());
            assertEquals(0, stub.newUnhooks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    outerEnv.adopt(spec("reload.precheck.outer", stub.target), token).status());
            assertEquals(1, stub.replacements.get());
            assertEquals(CloseReport.EntryRestoration.RESTORED,
                    nestedReceipt.get().registration().close().entryRestoration());
            assertEquals(1, stub.newUnhooks.get());
        } finally {
            session.close();
            nestedEnv.close();
            outerEnv.close();
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static final class Stub {
        final Method target;
        final AtomicInteger executableReads = new AtomicInteger();
        final AtomicInteger replacements = new AtomicInteger();
        final AtomicInteger oldUnhooks = new AtomicInteger();
        final AtomicInteger newUnhooks = new AtomicInteger();
        final AtomicReference<Runnable> onGetExecutable = new AtomicReference<Runnable>();
        final AtomicReference<CheckedAction> onReplace = new AtomicReference<CheckedAction>();
        final AtomicReference<XposedInterface.Hooker> hooker = new AtomicReference<XposedInterface.Hooker>();
        final XposedInterface.HookHandle old;

        Stub() throws NoSuchMethodException {
            target = LibXposed102ReloadRequestCloseTest.class.getMethod("target", int.class);
            old = proxy(XposedInterface.HookHandle.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getExecutable":
                        executableReads.incrementAndGet();
                        Runnable before = onGetExecutable.get();
                        if (before != null) before.run();
                        return target;
                    case "replaceHook":
                        replacements.incrementAndGet();
                        hooker.set((XposedInterface.Hooker) args[0]);
                        CheckedAction action = onReplace.get();
                        if (action != null) action.run();
                        return proxy(XposedInterface.HookHandle.class, (p, m, a) -> {
                            if ("unhook".equals(m.getName())) { newUnhooks.incrementAndGet(); return null; }
                            throw new UnsupportedOperationException(m.getName());
                        });
                    case "unhook": oldUnhooks.incrementAndGet(); return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        LibXposed102Backend backend() {
            XposedInterface api = proxy(XposedInterface.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getApiVersion": return 102;
                    case "getFrameworkName": return "reload-request-proxy";
                    case "getFrameworkVersion": return "1";
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
            return new LibXposed102Backend(api,
                    new LibXposed102Context("reload.request", getClass().getClassLoader(), 1L));
        }

        XposedModuleInterface.HotReloadedParam param() {
            return proxy(XposedModuleInterface.HotReloadedParam.class, (object, method, args) -> {
                if ("getOldHookHandles".equals(method.getName())) return Collections.singletonList(old);
                throw new UnsupportedOperationException(method.getName());
            });
        }

        Object invoke() throws Throwable {
            XposedInterface.Chain chain = proxy(XposedInterface.Chain.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getExecutable": return target;
                    case "getThisObject": return null;
                    case "getArgs": return Arrays.asList(1);
                    case "proceed": return 1;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
            return hooker.get().intercept(chain);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
