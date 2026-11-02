package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** API102 may violate @NonNull after an already committed replacement. */
public class NullReplacementHandleTest {
    @Test
    public void nullReplacementNeverPublishesAHandleOrClaimsTheOldGenerationIsValid() throws Throwable {
        Stub framework = new Stub();
        HookEnvironment environment = HookEnvironment.create(new LibXposed102Backend(framework.api(),
                new LibXposed102Context("stub.process", getClass().getClassLoader(), 1L)));
        Method target = NullReplacementHandleTest.class.getMethod("add", int.class, int.class);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        HookSpec oldSpec = spec(target, 99, oldCalls);
        HookSpec newSpec = spec(target, 100, newCalls);
        try {
            RegistrationReceipt installed = environment.install(oldSpec);
            assertTrue(installed.toString(), installed.isInstalled());
            HookRegistration old = installed.registration();
            Throwable failure = null;
            try {
                environment.replace(newSpec);
            } catch (Throwable observed) {
                failure = observed;
            }
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage(), failure.getMessage().contains("replacement outcome is unknown"));
            assertNotNull(framework.newHooker);
            assertEquals(3, framework.invoke(framework.oldHooker, target));
            assertEquals(3, framework.invoke(framework.newHooker, target));
            assertEquals(0, oldCalls.get());
            assertEquals(0, newCalls.get());
            assertEquals(2, framework.proceedCalls.get());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, old.close().entryRestoration());
            assertEquals(0, framework.unhookCalls.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(oldSpec).status());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.replace(newSpec).status());
            assertEquals(1, framework.replaceCalls.get());
        } finally {
            environment.close();
        }
        assertEquals(0, framework.unhookCalls.get());
    }

    private static HookSpec spec(Method target, int value, AtomicInteger callbacks) {
        return new HookSpec.Builder(HookId.of("null.replace.102"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(target)).before(invocation -> {
                    callbacks.incrementAndGet();
                    invocation.setEarlyResult(value);
                }).build();
    }

    public static int add(int left, int right) { return left + right; }

    private static final class Stub {
        final AtomicInteger replaceCalls = new AtomicInteger();
        final AtomicInteger unhookCalls = new AtomicInteger();
        final AtomicInteger proceedCalls = new AtomicInteger();
        XposedInterface.Hooker oldHooker;
        XposedInterface.Hooker newHooker;

        XposedInterface api() {
            return (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getApiVersion": return 102;
                            case "getFrameworkName": return "NullReplacementStub";
                            case "getFrameworkVersion": return "1";
                            case "hook": return builder();
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        XposedInterface.HookBuilder builder() {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setPriority":
                            case "setExceptionMode":
                            case "setId": return proxy;
                            case "intercept":
                                oldHooker = (XposedInterface.Hooker) args[0];
                                return handle();
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        XposedInterface.HookHandle handle() {
            return (XposedInterface.HookHandle) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookHandle.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "replaceHook":
                                replaceCalls.incrementAndGet();
                                newHooker = (XposedInterface.Hooker) args[0];
                                return null;
                            case "unhook": unhookCalls.incrementAndGet(); return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        Object invoke(XposedInterface.Hooker hooker, Method target) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getExecutable": return target;
                            case "getThisObject": return null;
                            case "getArgs": return Arrays.asList(1, 2);
                            case "proceed": proceedCalls.incrementAndGet(); return 3;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hooker.intercept(chain);
        }
    }
}
