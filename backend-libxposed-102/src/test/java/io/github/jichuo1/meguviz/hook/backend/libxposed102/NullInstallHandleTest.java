package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
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
import static org.junit.Assert.assertTrue;

/** A broken framework may install an interceptor but return no handle. */
public class NullInstallHandleTest {
    @Test
    public void nullFrameworkHandleIsUnknownAndCannotBePublishedAsSuccess() throws Throwable {
        Stub framework = new Stub();
        LibXposed102Backend backend = new LibXposed102Backend(framework.api(),
                new LibXposed102Context("stub.process", getClass().getClassLoader(), 1L));
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        Method target = NullInstallHandleTest.class.getMethod("add", int.class, int.class);
        HookSpec spec = new HookSpec.Builder(HookId.of("null.framework.102"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(target)).before(invocation -> callbacks.incrementAndGet()).build();
        try {
            Throwable failure = null;
            try {
                environment.install(spec);
            } catch (Throwable observed) {
                failure = observed;
            }
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage(), failure.getMessage().contains("null hook handle"));
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(1, framework.installCalls.get());
            assertEquals(3, framework.invoke(target));
            assertEquals(1, framework.proceedCalls.get());
            assertEquals("the unowned entry must not invoke SDK callbacks", 0, callbacks.get());
        } finally {
            environment.close();
        }
    }

    public static int add(int left, int right) { return left + right; }

    private static final class Stub {
        final AtomicInteger installCalls = new AtomicInteger();
        final AtomicInteger proceedCalls = new AtomicInteger();
        XposedInterface.Hooker hooker;

        XposedInterface api() {
            return (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getApiVersion": return 102;
                            case "getFrameworkName": return "NullHandleStub";
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
                                installCalls.incrementAndGet();
                                hooker = (XposedInterface.Hooker) args[0];
                                return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        Object invoke(Method target) throws Throwable {
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
