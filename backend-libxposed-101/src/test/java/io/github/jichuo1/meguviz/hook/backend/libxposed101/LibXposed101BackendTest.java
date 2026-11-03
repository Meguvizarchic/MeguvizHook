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
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.libxposed.api.XposedInterface;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    public void sdkLevelReplacementAndApi102OnlyHotReloadAreDistinguished() {
        assertEquals(CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL,
                backend.capabilities().lookup(Capability.ATOMIC_REPLACE).status());
        assertEquals(CapabilityResult.Status.UNSUPPORTED,
                backend.capabilities().lookup(Capability.HOT_RELOAD).status());
        assertEquals(CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL,
                backend.capabilities().lookup(Capability.FRAMEWORK_CHAIN).status());
    }

    @Test
    public void replacementKeepsSingleFrameworkEntryAndTransfersCloseOwnership() throws Throwable {
        framework.proceedResult = 3;
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        HookSpec oldSpec = replacementSpec(oldCalls);
        HookSpec newSpec = replacementSpec(newCalls);
        HookRegistration old = install(oldSpec);
        XposedInterface.Hooker originalHooker = framework.hooker;
        assertEquals(3, framework.invokeHook(sampleTarget(), null, 1, 2));

        RegistrationReceipt receipt = environment.replace(newSpec);
        assertTrue(receipt.toString(), receipt.isInstalled());
        assertSame(originalHooker, framework.hooker);
        assertEquals(1, framework.installCalls.get());
        old.close();
        assertEquals(0, framework.unhookCalls.get());
        assertEquals(3, framework.invokeHook(sampleTarget(), null, 1, 2));
        assertEquals(1, oldCalls.get());
        assertEquals(1, newCalls.get());
        receipt.registration().close();
        assertEquals(1, framework.unhookCalls.get());
        receipt.registration().close();
        assertEquals(1, framework.unhookCalls.get());
    }

    @Test
    public void forceReplaceIdenticalConstantTransfersOwnershipWithoutReinstall() throws Throwable {
        HookSpec spec = new HookSpec.Builder(HookId.of("adapter101.force"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(sampleTarget())).replaceConstant(99).build();
        HookRegistration old = install(spec);
        XposedInterface.Hooker originalHooker = framework.hooker;

        RegistrationReceipt replaced = environment.forceReplace(spec);
        assertTrue(replaced.toString(), replaced.isInstalled());
        assertTrue(replaced.generation() > old.generation());
        assertSame(originalHooker, framework.hooker);
        assertEquals(1, framework.installCalls.get());
        old.close();
        assertEquals(0, framework.unhookCalls.get());
        assertEquals(99, framework.invokeHook(sampleTarget(), null, 1, 2));
        replaced.registration().close();
        assertEquals(1, framework.unhookCalls.get());
    }

    @Test
    public void capturedOldChannelPassesThroughWhenRetiredBeforeDispatch() throws Throwable {
        framework.proceedResult = 3;
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        install(replacementSpec(oldCalls));
        Field field = framework.hooker.getClass().getDeclaredField("route");
        field.setAccessible(true);
        Object route = field.get(framework.hooker);
        Field channelField = route.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        DispatchChannel previous = (DispatchChannel) channelField.get(route);
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DispatchChannel delayed = invocation -> {
            selected.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return previous.dispatch(invocation);
        };
        java.lang.reflect.Constructor<?> routeConstructor = route.getClass().getDeclaredConstructor(DispatchChannel.class);
        routeConstructor.setAccessible(true);
        field.set(framework.hooker, routeConstructor.newInstance(delayed));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                framework.invokeHook(sampleTarget(), null, 1, 2);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        caller.start();
        try {
            assertTrue(selected.await(10, TimeUnit.SECONDS));
            RegistrationReceipt receipt = environment.replace(replacementSpec(newCalls));
            assertTrue(receipt.toString(), receipt.isInstalled());
        } finally {
            release.countDown();
            caller.join(10000);
        }
        assertTrue("caller did not finish", !caller.isAlive());
        assertEquals(null, failure.get());
        assertEquals(0, oldCalls.get());
        assertEquals(0, newCalls.get());
        assertEquals(1, framework.proceedCalls.get());
        framework.invokeHook(sampleTarget(), null, 1, 2);
        assertEquals(1, newCalls.get());
        assertEquals(2, framework.proceedCalls.get());
    }

    @Test
    public void repeatedReplacementDoesNotWaitForInFlightAndOldCloseCannotUnhookNew() throws Throwable {
        framework.proceedResult = 3;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger middleCalls = new AtomicInteger();
        AtomicInteger newestCalls = new AtomicInteger();
        HookSpec oldSpec = new HookSpec.Builder(HookId.of("adapter101.replace"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(sampleTarget())).before(invocation -> {
                    oldCalls.incrementAndGet();
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out");
                }).build();
        HookRegistration old = install(oldSpec);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                framework.invokeHook(sampleTarget(), null, 1, 2);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        caller.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            RegistrationReceipt middle = environment.replace(replacementSpec(middleCalls));
            assertTrue(middle.toString(), middle.isInstalled());
            framework.invokeHook(sampleTarget(), null, 1, 2);
            RegistrationReceipt newest = environment.replace(replacementSpec(newestCalls));
            assertTrue(newest.toString(), newest.isInstalled());
            old.close();
            middle.registration().close();
            assertEquals(0, framework.unhookCalls.get());
            framework.invokeHook(sampleTarget(), null, 1, 2);
            assertEquals(1, framework.installCalls.get());
            assertEquals(1, middleCalls.get());
            assertEquals(1, newestCalls.get());
            newest.registration().close();
            assertEquals(1, framework.unhookCalls.get());
        } finally {
            release.countDown();
            caller.join(10000);
        }
        assertTrue("caller did not finish", !caller.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1, oldCalls.get());
        assertEquals(3, framework.proceedCalls.get());
    }

    @Test
    public void closeRacingReplacementUnhooksAtMostOneFrameworkEntry() throws Throwable {
        framework.proceedResult = 3;
        HookRegistration old = install(replacementSpec(new AtomicInteger()));
        HookSpec replacement = replacementSpec(new AtomicInteger());
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<RegistrationReceipt> receipt = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                start.await();
                old.close();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        Thread replacer = new Thread(() -> {
            try {
                start.await();
                receipt.set(environment.replace(replacement));
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        closer.start();
        replacer.start();
        start.countDown();
        closer.join(10000);
        replacer.join(10000);
        assertTrue("race did not finish", !closer.isAlive() && !replacer.isAlive());
        assertEquals(null, failure.get());
        assertTrue(framework.unhookCalls.get() <= 1);
        if (receipt.get().isInstalled()) receipt.get().registration().close();
        assertEquals(1, framework.unhookCalls.get());
        assertEquals(1, framework.installCalls.get());
    }

    private HookSpec replacementSpec(AtomicInteger calls) throws NoSuchMethodException {
        return new HookSpec.Builder(HookId.of("adapter101.replace"), HookOwner.of("adapter-test"),
                TargetSpec.ofMember(sampleTarget()))
                .before(invocation -> calls.incrementAndGet()).build();
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
            HookRegistration registration = install(HookSpec.forMember("adapter101.after." + i, "adapter-test", target)
                    .after((invocation, result) -> { callbacks.incrementAndGet(); result.setResult(expected); }).build());
            Field routeField = framework.hooker.getClass().getDeclaredField("route");
            routeField.setAccessible(true);
            Object route = routeField.get(framework.hooker);
            Field bindingField = route.getClass().getDeclaredField("directAfter");
            bindingField.setAccessible(true);
            assertTrue("direct route must be selected", bindingField.get(route) != null);
            Object downstream = new Object();
            framework.proceedResult = downstream;
            assertSame(expected, framework.invokeHook(target, null));
            registration.close();
            assertSame(downstream, framework.invokeHook(target, null));
        }
        assertEquals(values.length, callbacks.get());
        assertEquals(values.length * 2, framework.proceedCalls.get());
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

        public static Object objectValue() { return null; }
    }

    /** Proxy stub rejects any method outside the small 101 surface used by the adapter. */
    private static final class StubFramework implements InvocationHandler {
        private XposedInterface.Hooker hooker;
        private XposedInterface.ExceptionMode exceptionMode;
        private int priority = -1;
        private Object proceedResult;
        private final AtomicInteger proceedCalls = new AtomicInteger();
        private final AtomicInteger unhookCalls = new AtomicInteger();
        private final AtomicInteger installCalls = new AtomicInteger();
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
                                installCalls.incrementAndGet();
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
