package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
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

/** JVM mapping fixture against the real 101 interfaces; not a real framework/device qualification. */
public class LibXposed101BackendTest {
    private StubFramework framework;
    private LibXposed101Backend backend;
    private HookEnvironment environment;

    @Before
    public void setUp() {
        framework = new StubFramework();
        backend = new LibXposed101Backend(framework.api(),
                new LibXposed101Context("stub.process", getClass().getClassLoader(), 1L));
        environment = HookEnvironment.create(backend);
    }

    private Method sampleTarget() throws NoSuchMethodException {
        return SampleTargets.class.getMethod("add", int.class, int.class);
    }

    @Test
    public void installForcesPassThroughAndPropagatesPriorityWithout102Id() throws Exception {
        RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                        HookId.of("adapter101.mapping"), HookOwner.of("adapter-test"),
                        TargetSpec.ofMember(sampleTarget()))
                .priority(70)
                .around(invocation -> invocation.proceed())
                .build());
        assertTrue(receipt.toString(), receipt.isInstalled());
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, framework.exceptionMode);
        assertEquals(70, framework.priority);
        assertEquals("the 101 builder must only receive calls present in the 101 API",
                0, framework.unsupportedCalls.get());
    }

    @Test
    public void api102OnlyCapabilitiesAreUnsupported() {
        assertEquals(CapabilityResult.Status.UNSUPPORTED,
                backend.capabilities().lookup(Capability.ATOMIC_REPLACE).status());
        assertEquals(CapabilityResult.Status.UNSUPPORTED,
                backend.capabilities().lookup(Capability.HOT_RELOAD).status());
        assertEquals(CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL,
                backend.capabilities().lookup(Capability.FRAMEWORK_CHAIN).status());
    }

    @Test
    public void returnedValueFlowsThroughInterceptor() throws Throwable {
        framework.proceedResult = 3;
        install(new HookSpec.Builder(HookId.of("adapter101.return"), HookOwner.of("adapter-test"),
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
        install(new HookSpec.Builder(HookId.of("adapter101.throw"), HookOwner.of("adapter-test"),
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
        HookRegistration registration = install(new HookSpec.Builder(
                        HookId.of("adapter101.close"), HookOwner.of("adapter-test"),
                        TargetSpec.ofMember(sampleTarget()))
                .before(invocation -> callbacks.incrementAndGet())
                .build());
        CloseReport report = registration.close();
        assertEquals(CloseReport.PhysicalRelease.NOT_CLAIMED, report.physicalRelease());
        assertEquals(1, framework.unhookCalls.get());

        assertEquals(11, framework.invokeHook(sampleTarget(), null, 1, 2));
        assertEquals(1, framework.proceedCalls.get());
        assertEquals(0, callbacks.get());
        registration.close();
        assertEquals(1, framework.unhookCalls.get());
    }

    private HookRegistration install(HookSpec spec) {
        RegistrationReceipt receipt = environment.install(spec);
        assertTrue(receipt.toString(), receipt.isInstalled());
        return receipt.registration();
    }

    public static final class SampleTargets {
        private SampleTargets() {
        }

        public static int add(int left, int right) {
            return left + right;
        }
    }

    /** Proxy stub rejects any method outside the small 101 surface used by the adapter. */
    private static final class StubFramework implements InvocationHandler {
        private XposedInterface.Hooker hooker;
        private XposedInterface.ExceptionMode exceptionMode;
        private int priority = -1;
        private Object proceedResult;
        private final AtomicInteger proceedCalls = new AtomicInteger();
        private final AtomicInteger unhookCalls = new AtomicInteger();
        private final AtomicInteger unsupportedCalls = new AtomicInteger();

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
                                unsupportedCalls.incrementAndGet();
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hooker.intercept(chain);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getApiVersion":
                    return 101;
                case "getFrameworkName":
                    return "StubFramework101";
                case "getFrameworkVersion":
                    return "1.0";
                case "hook":
                    return builderProxy();
                default:
                    unsupportedCalls.incrementAndGet();
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
                            case "intercept":
                                hooker = (XposedInterface.Hooker) args[0];
                                return handleProxy();
                            default:
                                unsupportedCalls.incrementAndGet();
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private XposedInterface.HookHandle handleProxy() {
            return (XposedInterface.HookHandle) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {XposedInterface.HookHandle.class}, (proxy, method, args) -> {
                        if ("unhook".equals(method.getName())) {
                            unhookCalls.incrementAndGet();
                            return null;
                        }
                        unsupportedCalls.incrementAndGet();
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
