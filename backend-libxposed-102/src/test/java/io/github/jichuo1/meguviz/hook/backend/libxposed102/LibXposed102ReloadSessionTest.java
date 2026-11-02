package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** JVM proxies exercise API102 mapping and failure contracts; they do not prove framework lifecycle. */
public class LibXposed102ReloadSessionTest {
    public static int add(int a, int b) { return a + b; }
    public static int other(int a, int b) { return a - b; }

    private static Method method(String name) throws Exception {
        return LibXposed102ReloadSessionTest.class.getMethod(name, int.class, int.class);
    }

    private static HookSpec spec(String id, Method target, AtomicInteger calls) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("reload-test"), TargetSpec.ofMember(target))
                .before(invocation -> { calls.incrementAndGet(); invocation.setEarlyResult(42); }).build();
    }

    @Test public void adoptionReplacesOldHandleThenCallsAndClosesNewGeneration() throws Throwable {
        Stub stub = new Stub(method("add"));
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger calls = new AtomicInteger();
        try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param(stub.old))) {
            assertSame(stub.old, session.oldHandles().get(0));
            BackendAdoption token = session.adoption(stub.old);
            RegistrationReceipt receipt = env.adopt(spec("reload.success", stub.target, calls), token);
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertEquals(1, stub.replaces);
            assertEquals(0, stub.installs);
            assertEquals(42, stub.invoke());
            assertEquals(1, calls.get());
            assertEquals(CloseReport.EntryRestoration.RESTORED, receipt.registration().close().entryRestoration());
            assertEquals(1, stub.newUnhooks);
            assertEquals(0, stub.oldUnhooks);
            assertEquals(3, stub.invoke());
            assertEquals(1, calls.get());
        } finally {
            env.close();
        }
        assertEquals(1, stub.newUnhooks);
    }

    @Test public void adoptionRequiresTheCallerDeclaredOldExceptionMode() throws Exception {
        Stub stub = new Stub(method("add"));
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param(stub.old))) {
            BackendAdoption token = session.adoption(stub.old, ErrorPolicy.FRAMEWORK_PROTECTIVE);
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.adopt(spec("reload.mismatch", stub.target, new AtomicInteger()), token).status());
            assertEquals("policy mismatch must not touch the old handle", 0, stub.replaces);
            HookSpec protective = new HookSpec.Builder(HookId.of("reload.protective"), HookOwner.of("reload-test"),
                    TargetSpec.ofMember(stub.target)).errorPolicy(ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .before(invocation -> invocation.setEarlyResult(42)).build();
            RegistrationReceipt receipt = env.adopt(protective, token);
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertEquals(1, stub.replaces);
            receipt.registration().close();
        } finally { env.close(); }
    }

    @Test public void defaultAdoptionDoesNotSilentlyAcceptProtectivePlan() throws Exception {
        Stub stub = new Stub(method("add"));
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param(stub.old))) {
            HookSpec protective = new HookSpec.Builder(HookId.of("reload.default-mode"), HookOwner.of("reload-test"),
                    TargetSpec.ofMember(stub.target)).errorPolicy(ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .before(invocation -> invocation.setEarlyResult(42)).build();
            assertEquals(RegistrationReceipt.Status.FAILED, env.adopt(protective, session.adoption(stub.old)).status());
            assertEquals(0, stub.replaces);
        } finally { env.close(); }
    }

    @Test public void sourceAndSingleUseAreCheckedByIdentityAndBackendInstance() throws Exception {
        Stub stub = new Stub(method("add"));
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param(stub.old))) {
            try { session.adoption(stub.newHandle()); fail("foreign handle accepted"); }
            catch (IllegalArgumentException expected) { }
            BackendAdoption token = session.adoption(stub.old);
            try { session.adoption(stub.old); fail("duplicate token issued"); }
            catch (IllegalStateException expected) { }
            HookEnvironment foreign = HookEnvironment.create(stub.backend());
            try {
                assertEquals(RegistrationReceipt.Status.CONFLICT,
                        foreign.adopt(spec("reload.foreign", stub.target, new AtomicInteger()), token).status());
            } finally { foreign.close(); }
            RegistrationReceipt ok = env.adopt(spec("reload.first", stub.target, new AtomicInteger()), token);
            assertTrue(ok.toString(), ok.isInstalled());
            try {
                env.adopt(spec("reload.second", stub.target, new AtomicInteger()), token);
                fail("consumed token accepted");
            } catch (IllegalStateException expected) { }
            assertEquals(1, stub.replaces);
            ok.registration().close();
        } finally { env.close(); }
    }

    @Test public void targetMismatchIsUncommittedAndTokenCanBeRetried() throws Exception {
        Stub stub = new Stub(method("add"));
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param(stub.old))) {
            BackendAdoption token = session.adoption(stub.old);
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.adopt(spec("reload.wrong", method("other"), new AtomicInteger()), token).status());
            assertEquals(0, stub.replaces);
            RegistrationReceipt receipt = env.adopt(spec("reload.right", stub.target, new AtomicInteger()), token);
            assertTrue(receipt.toString(), receipt.isInstalled());
            receipt.registration().close();
        } finally { env.close(); }
    }

    @Test public void closedSessionInvalidatesTokensAndDoesNotInvokeFramework() throws Exception {
        Stub stub = new Stub(method("add"));
        LibXposed102Backend backend = stub.backend();
        HookEnvironment env = HookEnvironment.create(backend);
        LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param(stub.old));
        BackendAdoption token = session.adoption(stub.old);
        session.close();
        try {
            env.adopt(spec("reload.closed", stub.target, new AtomicInteger()), token);
            fail("expired token accepted");
        } catch (IllegalStateException expected) { }
        try { session.oldHandles(); fail("closed session exposed handles"); }
        catch (IllegalStateException expected) { }
        env.close();
        assertEquals(0, stub.replaces);
        assertEquals(0, stub.oldUnhooks);
    }

    @Test public void nullAndThrowAfterFrameworkCallRetireChannelAndQuarantineId() throws Throwable {
        for (boolean throwsAfterCommit : new boolean[]{false, true}) {
            Stub stub = new Stub(method("add"));
            stub.nullOrThrow = throwsAfterCommit ? 2 : 1;
            LibXposed102Backend backend = stub.backend();
            HookEnvironment env = HookEnvironment.create(backend);
            AtomicInteger calls = new AtomicInteger();
            HookSpec spec = spec("reload.unknown", stub.target, calls);
            try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, stub.param(stub.old))) {
                BackendAdoption token = session.adoption(stub.old);
                try { env.adopt(spec, token); fail("unknown outcome accepted"); }
                catch (IllegalStateException expected) { }
                assertEquals(1, stub.replaces);
                assertNotNull(stub.hooker);
                assertEquals(3, stub.invoke());
                assertEquals(0, calls.get());
                assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.adopt(spec, token).status());
            } finally { env.close(); }
            assertEquals(0, stub.oldUnhooks);
            assertEquals(0, stub.newUnhooks);
        }
    }

    private static final class Stub {
        final Method target;
        final XposedInterface.HookHandle old;
        XposedInterface.Hooker hooker;
        int replaces;
        int installs;
        int oldUnhooks;
        int newUnhooks;
        int nullOrThrow;

        Stub(Method target) {
            this.target = target;
            old = handle(true);
        }

        LibXposed102Backend backend() {
            XposedInterface api = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getApiVersion": return 102;
                            case "getFrameworkName": return "reload-stub";
                            case "getFrameworkVersion": return "1";
                            case "hook": installs++; throw new AssertionError("adoption must not reinstall");
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return new LibXposed102Backend(api,
                    new LibXposed102Context("stub.process", getClass().getClassLoader(), 1L));
        }

        XposedModuleInterface.HotReloadedParam param(XposedInterface.HookHandle handle) {
            return (XposedModuleInterface.HotReloadedParam) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedModuleInterface.HotReloadedParam.class}, (proxy, method, args) -> {
                        if ("getOldHookHandles".equals(method.getName())) return Collections.singletonList(handle);
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        XposedInterface.HookHandle newHandle() { return handle(false); }

        XposedInterface.HookHandle handle(boolean isOld) {
            return (XposedInterface.HookHandle) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookHandle.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getExecutable": return target;
                            case "replaceHook":
                                if (!isOld) throw new AssertionError("unexpected nested replacement");
                                replaces++;
                                hooker = (XposedInterface.Hooker) args[0];
                                if (nullOrThrow == 1) return null;
                                if (nullOrThrow == 2) throw new IllegalStateException("commit unknown");
                                return newHandle();
                            case "unhook": if (isOld) oldUnhooks++; else newUnhooks++; return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        Object invoke() throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getExecutable": return target;
                            case "getThisObject": return null;
                            case "getArgs": return Arrays.asList(1, 2);
                            case "proceed": return 3;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hooker.intercept(chain);
        }
    }
}
