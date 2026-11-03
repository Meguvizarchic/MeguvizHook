package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.core.advanced.AdvancedHookOperations;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** API101 JVM proxy mapping tests; not a framework, ART, class-init or device test. */
public final class LibXposed101AdvancedTest {
    public static class Parent { public Parent() { } public String value(String arg) { return arg; } }
    public static final class Child extends Parent { public Child() { } }
    public static String staticMethod() { return "static"; }

    private static final class Stub {
        int apiVersion = 101;
        Object selectedType;
        Object receiver;
        Object[] args;
        Method method;
        Constructor<?> constructor;
        Class<?> subclass;
        Throwable invocationFailure;
        XposedInterface.Hooker hooker;
        XposedInterface.ExceptionMode mode;
        int priority;
        int unhooks;
        int deoptimizations;
        int proceedCalls;
        int invocations;
        boolean deoptResult;
        boolean returnNullHandle;

        final XposedInterface.Invoker<?, Method> methodInvoker = proxy(XposedInterface.Invoker.class, (proxy, method, args) -> {
            if (method.getName().equals("setType")) { selectedType = args[0]; return proxy; }
            if (method.getName().equals("invoke") || method.getName().equals("invokeSpecial")) {
                invocations++; receiver = args[0]; this.args = (Object[]) args[1];
                if (invocationFailure != null) throw invocationFailure;
                return method.getName();
            }
            throw new AssertionError(method.getName());
        });
        final XposedInterface.CtorInvoker<?> ctorInvoker = proxy(XposedInterface.CtorInvoker.class, (proxy, method, args) -> {
            if (method.getName().equals("setType")) { selectedType = args[0]; return proxy; }
            if (method.getName().equals("newInstance")) { invocations++; this.args = (Object[]) args[0]; return new Parent(); }
            if (method.getName().equals("newInstanceSpecial")) {
                invocations++; subclass = (Class<?>) args[0]; this.args = (Object[]) args[1]; return new Child();
            }
            throw new AssertionError(method.getName());
        });
        final XposedInterface.HookHandle handle = proxy(XposedInterface.HookHandle.class, (proxy, method, args) -> {
            if (method.getName().equals("unhook")) { unhooks++; return null; }
            throw new AssertionError(method.getName());
        });
        final XposedInterface.HookBuilder builder = proxy(XposedInterface.HookBuilder.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "setPriority": priority = (Integer) args[0]; return proxy;
                case "setExceptionMode": mode = (XposedInterface.ExceptionMode) args[0]; return proxy;
                case "intercept": hooker = (XposedInterface.Hooker) args[0]; return returnNullHandle ? null : handle;
                default: throw new AssertionError(method.getName());
            }
        });
        final XposedInterface framework = proxy(XposedInterface.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getApiVersion": return apiVersion;
                case "deoptimize": deoptimizations++; assertNotNull(args[0]); return deoptResult;
                case "getInvoker":
                    if (args[0] instanceof Method) { this.method = (Method) args[0]; return methodInvoker; }
                    constructor = (Constructor<?>) args[0]; return ctorInvoker;
                case "hookClassInitializer": assertEquals(Parent.class, args[0]); return builder;
                default: throw new AssertionError(method.getName());
            }
        });

        XposedInterface.Chain chain() {
            return proxy(XposedInterface.Chain.class, (proxy, method, args) -> {
                if (method.getName().equals("proceed") && (args == null || args.length == 0)) {
                    proceedCalls++; return null;
                }
                throw new AssertionError(method.getName());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @Test public void mapsExplicitRoutesAndPreservesFrameworkExceptions() throws Exception {
        Stub stub = new Stub();
        LibXposed101Advanced advanced = new LibXposed101Advanced(stub.framework);
        Method target = Parent.class.getMethod("value", String.class);
        Parent receiver = new Child();
        assertFalse(advanced.deoptimizeCaller(target));
        assertEquals(1, stub.deoptimizations);
        assertEquals(CapabilityResult.Status.UNKNOWN, advanced.availability(AdvancedHookOperations.Feature.DEOPTIMIZE_CALLER).status());
        assertEquals("invoke", advanced.invokeOriginal(target, receiver, "a"));
        assertSame(XposedInterface.Invoker.Type.ORIGIN, stub.selectedType);
        assertSame(receiver, stub.receiver);
        assertArrayEquals(new Object[]{"a"}, stub.args);
        assertEquals("invoke", advanced.invoke(target, receiver, AdvancedHookOperations.InvocationRoute.FULL_CHAIN, "chain"));
        assertSame(XposedInterface.Invoker.Type.Chain.FULL, stub.selectedType);
        assertEquals("invokeSpecial", advanced.invokeSpecial(target, receiver, AdvancedHookOperations.InvocationRoute.FULL_CHAIN, "b"));
        assertSame(XposedInterface.Invoker.Type.Chain.FULL, stub.selectedType);
        assertEquals("invokeSpecial", advanced.invokeSpecial(target, receiver, AdvancedHookOperations.InvocationRoute.ORIGINAL, "c"));
        assertSame(XposedInterface.Invoker.Type.ORIGIN, stub.selectedType);
        int before = stub.invocations;
        assertThrows(IllegalArgumentException.class, () -> advanced.invokeSpecial(
                LibXposed101AdvancedTest.class.getMethod("staticMethod"), receiver, AdvancedHookOperations.InvocationRoute.ORIGINAL));
        assertThrows(IllegalArgumentException.class, () -> advanced.invokeSpecial(target, "wrong", AdvancedHookOperations.InvocationRoute.ORIGINAL));
        assertEquals(before, stub.invocations);
        InvocationTargetException failure = new InvocationTargetException(new IllegalStateException("original"));
        stub.invocationFailure = failure;
        assertSame(failure, assertThrows(InvocationTargetException.class, () -> advanced.invokeOriginal(target, receiver, "x")));
        assertEquals(before + 1, stub.invocations);
    }

    @Test public void constructorRoutesAreExplicitAndRejectUnrelatedTypes() throws Exception {
        Stub stub = new Stub();
        LibXposed101Advanced advanced = new LibXposed101Advanced(stub.framework);
        Constructor<Parent> constructor = Parent.class.getConstructor();
        assertTrue(advanced.newInstance(constructor, AdvancedHookOperations.InvocationRoute.ORIGINAL) instanceof Parent);
        assertSame(XposedInterface.Invoker.Type.ORIGIN, stub.selectedType);
        assertTrue(advanced.newInstanceSpecial(constructor, Child.class, AdvancedHookOperations.InvocationRoute.FULL_CHAIN) instanceof Child);
        assertSame(Child.class, stub.subclass);
        assertSame(XposedInterface.Invoker.Type.Chain.FULL, stub.selectedType);
        int before = stub.invocations;
        assertThrows(IllegalArgumentException.class, () -> advanced.newInstanceSpecial(constructor, String.class,
                AdvancedHookOperations.InvocationRoute.ORIGINAL));
        assertEquals(before, stub.invocations);
    }

    @Test public void everyAdvancedCapabilityRemainsUnknownWithoutRuntimeEvidence() {
        LibXposed101Advanced advanced = new LibXposed101Advanced(new Stub().framework);
        for (AdvancedHookOperations.Feature feature : AdvancedHookOperations.Feature.values()) {
            assertEquals(feature.toString(), CapabilityResult.Status.UNKNOWN, advanced.availability(feature).status());
        }
        assertThrows(NullPointerException.class, () -> advanced.availability(null));
    }

    @Test public void initializerMapsPriorityPolicyAndCloseToFramework() throws Throwable {
        Stub stub = new Stub();
        LibXposed101Advanced advanced = new LibXposed101Advanced(stub.framework);
        AtomicInteger callbacks = new AtomicInteger();
        AdvancedHookOperations.ClassInitializerRegistration registration = advanced.hookClassInitializer(
                Parent.class, 77, ErrorPolicy.FRAMEWORK_PROTECTIVE, invocation -> {
                    assertSame(Parent.class, invocation.targetClass()); callbacks.incrementAndGet(); invocation.proceed();
                });
        assertEquals(77, stub.priority);
        assertEquals(XposedInterface.ExceptionMode.PROTECTIVE, stub.mode);
        assertNull(stub.hooker.intercept(stub.chain()));
        assertEquals(1, callbacks.get());
        assertEquals(1, stub.proceedCalls);
        CloseReport report = registration.close();
        assertEquals(CloseReport.EntryRestoration.RESTORED, report.entryRestoration());
        assertEquals(CloseReport.PhysicalRelease.NOT_CLAIMED, report.physicalRelease());
        assertEquals(1, stub.unhooks);
        assertNull(stub.hooker.intercept(stub.chain()));
        assertEquals(1, callbacks.get());
        assertEquals(2, stub.proceedCalls);
        registration.close();
        assertEquals(1, stub.unhooks);
    }

    @Test public void unavailableOrNullInstallDoesNotPretendSuccess() throws Throwable {
        Stub stub = new Stub();
        stub.apiVersion = 100;
        assertThrows(IllegalArgumentException.class, () -> new LibXposed101Advanced(stub.framework));
        stub.apiVersion = 101;
        stub.returnNullHandle = true;
        LibXposed101Advanced advanced = new LibXposed101Advanced(stub.framework);
        AtomicInteger callbacks = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> advanced.hookClassInitializer(
                Parent.class, 50, ErrorPolicy.PROPAGATE, invocation -> callbacks.incrementAndGet()));
        assertNull(stub.hooker.intercept(stub.chain()));
        assertEquals(1, stub.proceedCalls);
        assertEquals(0, callbacks.get());
    }
}
