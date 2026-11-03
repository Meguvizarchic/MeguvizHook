package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.libxposed.api.XposedInterface;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the adapter's mapping against a stubbed 102 interface: exception mode, priority,
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
    public void installForcesPassThroughAndKeepsFrameworkIdAbsent() throws Exception {
        HookSpec spec = new HookSpec.Builder(HookId.of("adapter.mapping"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(sampleTarget()))
                .priority(70)
                .around(invocation -> invocation.proceed())
                .build();
        RegistrationReceipt receipt = environment.install(spec);
        assertTrue(receipt.toString(), receipt.isInstalled());

        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, framework.exceptionMode);
        assertNull("logical IDs must not become module-wide framework IDs", framework.hookId);
        assertEquals("the adapter must not configure a framework ID", 0, framework.setIdCalls.get());
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

    @Test
    public void replacementSwapsHookerAndTransfersHandleWithoutReinstall() throws Throwable {
        HookSpec original = new HookSpec.Builder(HookId.of("adapter.replace"), HookOwner.of("adapter-test"),
                        TargetSpec.ofMember(sampleTarget()))
                .priority(73)
                .before(invocation -> invocation.setEarlyResult(10))
                .build();
        HookRegistration old = TestSupport.install(environment, original);
        assertEquals(10, framework.invokeHook(sampleTarget(), null, 1, 2));

        HookSpec replacement = new HookSpec.Builder(HookId.of("adapter.replace"), HookOwner.of("adapter-test"),
                        TargetSpec.ofMember(sampleTarget()))
                .priority(73)
                .before(invocation -> invocation.setEarlyResult(20))
                .build();
        RegistrationReceipt replaced = environment.replace(replacement);

        assertTrue(replaced.toString(), replaced.isInstalled());
        assertTrue(replaced.generation() > old.generation());
        assertEquals(20, framework.invokeHook(sampleTarget(), null, 1, 2));
        assertEquals(1, framework.installCalls.get());
        assertEquals(1, framework.replaceCalls.get());
        assertEquals(73, framework.priority);
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, framework.exceptionMode);
        old.close();
        assertEquals("superseded close must not unhook the new handle", 0, framework.unhookCalls.get());
        replaced.registration().close();
        assertEquals(1, framework.unhookCalls.get());
    }

    @Test
    public void forceReplaceIdenticalConstantTransfersOwnershipThroughReplaceHook() throws Throwable {
        HookSpec spec = new HookSpec.Builder(HookId.of("adapter102.force"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(sampleTarget())).replaceConstant(99).build();
        HookRegistration old = TestSupport.install(environment, spec);

        RegistrationReceipt replaced = environment.forceReplace(spec);
        assertTrue(replaced.toString(), replaced.isInstalled());
        assertTrue(replaced.generation() > old.generation());
        assertEquals(1, framework.installCalls.get());
        assertEquals(1, framework.replaceCalls.get());
        old.close();
        assertEquals(0, framework.unhookCalls.get());
        assertEquals(99, framework.invokeHook(sampleTarget(), null, 1, 2));
        replaced.registration().close();
        assertEquals(1, framework.unhookCalls.get());
    }

    @Test
    public void directAfterPreservesReservedResultsAndClosedPassthrough() throws Throwable {
        Method target = SampleTargets.class.getMethod("objectValue");
        Object marker = DirectAfterDispatchChannel.NOT_ENTERED;
        Object escaped = DirectAfterDispatchChannel.escape(marker);
        Object[] values = {null, new Object(), marker, escaped,
                DirectAfterDispatchChannel.escape(escaped), DispatchResult.passthrough()};
        AtomicInteger callbacks = new AtomicInteger();
        for (int i = 0; i < values.length; i++) {
            Object expected = values[i];
            HookRegistration registration = TestSupport.install(environment,
                    HookSpec.forMember("adapter102.after." + i, "adapter-test", target)
                    .after((invocation, result) -> { callbacks.incrementAndGet(); result.setResult(expected); }).build());
            boolean direct = false;
            for (java.lang.reflect.Field field : framework.hooker.getClass().getDeclaredFields()) {
                if (field.getType().getSimpleName().equals("AfterBinding")) direct = true;
            }
            assertTrue("direct route must be selected", direct);
            Object downstream = new Object();
            framework.proceedResult = downstream;
            assertSame(expected, framework.invokeHook(target, null));
            registration.close();
            assertSame(downstream, framework.invokeHook(target, null));
        }
        assertEquals(values.length, callbacks.get());
        assertEquals(values.length * 2, framework.proceedCalls.get());
    }

    @Test
    public void directAfterCloseKeepsAnAdmittedCallbackAndBypassesNewCalls() throws Throwable {
        Method target = SampleTargets.class.getMethod("objectValue");
        Object original = new Object();
        Object changed = new Object();
        framework.proceedResult = original;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Object> observed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HookRegistration registration = TestSupport.install(environment,
                HookSpec.forMember("adapter102.after.inflight", "adapter-test", target)
                        .after((invocation, result) -> {
                            callbacks.incrementAndGet();
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("after timed out");
                            result.setResult(changed);
                        }).build());
        Thread worker = new Thread(() -> {
            try { observed.set(framework.invokeHook(target, null)); }
            catch (Throwable error) { failure.set(error); }
        }, "direct-after-inflight");
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CloseReport report = registration.close();
            assertTrue(report.newCallbacksRejected());
            assertEquals(1, report.inFlightAtClose());
            assertSame(original, framework.invokeHook(target, null));
        } finally {
            release.countDown();
            worker.join(5000);
            environment.close();
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertSame(changed, observed.get());
        assertEquals(1, callbacks.get());
        assertEquals(2, framework.proceedCalls.get());
    }

    /** Small fixture holder: target methods live outside the framework stub. */
    public static final class SampleTargets {
        private SampleTargets() {
        }

        public static int add(int left, int right) {
            return left + right;
        }

        public static Object objectValue() { return null; }
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
        private final AtomicInteger installCalls = new AtomicInteger();
        private final AtomicInteger replaceCalls = new AtomicInteger();
        private final AtomicInteger setIdCalls = new AtomicInteger();

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
                                setIdCalls.incrementAndGet();
                                hookId = (String) args[0];
                                return proxy;
                            case "intercept":
                                installCalls.incrementAndGet();
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
                            case "replaceHook":
                                replaceCalls.incrementAndGet();
                                hooker = (XposedInterface.Hooker) args[0];
                                return handleProxy();
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
