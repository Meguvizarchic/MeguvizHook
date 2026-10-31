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

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the adapter's mapping against a stubbed 102 interface: exception mode, id/priority
 * propagation, result/throwable/passthrough mapping and unhook behaviour.
 *
 * <p>This is a framework-mapping fixture on the JVM, not evidence of a real 102 runtime, ART
 * behaviour, R8 output or class loading on a device.
 */
public class LibXposed102BackendTest {
    private StubFramework framework;
    private HookEnvironment environment;

    @Before
    public void setUp() {
        framework = new StubFramework();
        LibXposed102Backend backend = new LibXposed102Backend(framework.api(),
                new LibXposed102Context("stub.process", getClass().getClassLoader(), 1L));
        environment = HookEnvironment.create(backend);
    }

    private Method sampleTarget() throws NoSuchMethodException {
        return SampleTargets.class.getMethod("add", int.class, int.class);
    }

    @Test
    public void installForcesPassThroughAndPropagatesIdAndPriority() throws Exception {
        HookSpec spec = new HookSpec.Builder(HookId.of("adapter.mapping"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(sampleTarget()))
                .priority(70)
                .around(invocation -> invocation.proceed())
                .build();
        RegistrationReceipt receipt = environment.install(spec);
        assertTrue(receipt.toString(), receipt.isInstalled());

        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, framework.exceptionMode);
        assertEquals("adapter.mapping", framework.hookId);
        assertEquals(70, framework.priority);
        assertEquals("PROTECTIVE would silently swallow callback failures and break PROPAGATE",
                XposedInterface.ExceptionMode.PASSTHROUGH, framework.exceptionMode);
    }

    @Test
    public void returnedValueFlowsThroughInterceptor() throws Throwable {
        framework.proceedResult = 3;
        TestSupport.install(environment, new HookSpec.Builder(HookId.of("adapter.return"), HookOwner.of("adapter-test"),
                        TargetSpec.ofMember(sampleTarget()))
                .around(invocation -> {
                    Object value = invocation.proceed().value();
                    invocation.setEarlyResult((Integer) value * 2);
                })
                .build());
        assertEquals(6, framework.invokeHook(sampleTarget(), null, 1, 2));
        assertEquals(1, framework.proceedCalls.get());
    }

    @Test
    public void callbackThrowablePropagatesWithoutRunningOriginal() throws Exception {
        RuntimeException failure = new RuntimeException("callback");
        TestSupport.install(environment, new HookSpec.Builder(HookId.of("adapter.throw"), HookOwner.of("adapter-test"),
                        TargetSpec.ofMember(sampleTarget()))
                .before(invocation -> {
                    throw failure;
                })
                .build());
        Throwable observed = null;
        try {
            framework.invokeHook(sampleTarget(), null, 1, 2);
        } catch (Throwable error) {
            observed = error;
        }
        assertSame(failure, observed);
        assertEquals(0, framework.proceedCalls.get());
    }

    @Test
    public void closedRegistrationFallsBackToOriginalExactlyOnce() throws Throwable {
        framework.proceedResult = 11;
        AtomicInteger callbacks = new AtomicInteger();
        HookRegistration registration = TestSupport.install(environment,
                new HookSpec.Builder(HookId.of("adapter.close"), HookOwner.of("adapter-test"),
                                TargetSpec.ofMember(sampleTarget()))
                        .before(invocation -> callbacks.incrementAndGet())
                        .build());
        CloseReport report = registration.close();
        assertEquals(CloseReport.PhysicalRelease.NOT_CLAIMED, report.physicalRelease());
        assertEquals(1, framework.unhookCalls.get());

        assertEquals(11, framework.invokeHook(sampleTarget(), null, 1, 2));
        assertEquals("original runs once through chain.proceed", 1, framework.proceedCalls.get());
        assertEquals("no SDK callback after close", 0, callbacks.get());

        registration.close();
        assertEquals("unhook stays idempotent", 1, framework.unhookCalls.get());
    }

    /** Small fixture holder: target methods live outside the framework stub. */
    public static final class SampleTargets {
        private SampleTargets() {
        }

        public static int add(int left, int right) {
            return left + right;
        }
    }

    /** Minimal Java proxy stub of the 102 API surface used by the adapter. */
    private static final class StubFramework implements InvocationHandler {
        private XposedInterface.Hooker hooker;
        private XposedInterface.ExceptionMode exceptionMode;
        private String hookId;
        private int priority = -1;
        private Object proceedResult;
        private final AtomicInteger proceedCalls = new AtomicInteger();
        private final AtomicInteger unhookCalls = new AtomicInteger();

        XposedInterface api() {
            return (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {XposedInterface.class}, this);
        }

        Object invokeHook(Method target, Object thisObject, Object... args) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {XposedInterface.Chain.class},
                    (proxy, method, chainArgs) -> {
                        switch (method.getName()) {
                            case "getExecutable":
                                return target;
                            case "getThisObject":
                                return thisObject;
                            case "getArgs":
                                return (List<Object>) Arrays.asList(args);
                            case "proceed":
                                proceedCalls.incrementAndGet();
                                return proceedResult;
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hooker.intercept(chain);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getApiVersion":
                    return XposedInterface.API_102;
                case "getFrameworkName":
                    return "StubFramework";
                case "getFrameworkVersion":
                    return "1.0";
                case "getFrameworkVersionCode":
                    return 1L;
                case "hook":
                    return builderProxy();
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }

        private XposedInterface.HookBuilder builderProxy() {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {XposedInterface.HookBuilder.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setPriority":
                                priority = (Integer) args[0];
                                return proxy;
                            case "setExceptionMode":
                                exceptionMode = (XposedInterface.ExceptionMode) args[0];
                                return proxy;
                            case "setId":
                                hookId = (String) args[0];
                                return proxy;
                            case "intercept":
                                hooker = (XposedInterface.Hooker) args[0];
                                return handleProxy();
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private XposedInterface.HookHandle handleProxy() {
            return (XposedInterface.HookHandle) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {XposedInterface.HookHandle.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "unhook":
                                unhookCalls.incrementAndGet();
                                return null;
                            case "getId":
                                return hookId;
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    /** Local helper to keep assertions readable. */
    private static final class TestSupport {
        private TestSupport() {
        }

        static HookRegistration install(HookEnvironment environment, HookSpec spec) {
            RegistrationReceipt receipt = environment.install(spec);
            assertTrue(receipt.toString(), receipt.isInstalled());
            return receipt.registration();
        }
    }
}
