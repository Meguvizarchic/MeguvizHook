package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
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
import java.util.Collections;

import static org.junit.Assert.*;

/** Local API-contract model; it does not prove real framework exception handling. */
public final class FrameworkProtective102Test {
    @Test public void successReplacementRunsDownstreamOnceAndKeepsFailureIdentity() throws Throwable {
        StubFramework stub = new StubFramework(target());
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(stub.api(),
                new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
        try {
            RegistrationReceipt first = env.install(spec("protect.success", target(), ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .replaceResultOnSuccess(7).build());
            assertTrue(first.toString(), first.isInstalled());
            assertEquals(7, stub.call("hit", null));
            RuntimeException failure = new RuntimeException("original");
            assertSame(failure, capture(() -> stub.call("hit", failure)));
            assertEquals(2, stub.originalCalls);
            first.registration().close();
            assertEquals(5, stub.call("hit", null));
            assertEquals(3, stub.originalCalls);
        } finally { env.close(); }
    }
    public static int target(String key) { return 5; }

    private static Method target() throws Exception {
        return FrameworkProtective102Test.class.getMethod("target", String.class);
    }

    private static HookSpec.Builder spec(String id, Method target, ErrorPolicy policy) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("protect-test"), TargetSpec.ofMember(target))
                .errorPolicy(policy);
    }

    @Test public void protectiveBeforeFailureContinuesOnceAndDefaultStillPropagates() throws Throwable {
        for (ErrorPolicy policy : ErrorPolicy.values()) {
            StubFramework stub = new StubFramework(target());
            HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(stub.api(),
                    new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
            RuntimeException failure = new RuntimeException("before");
            try {
                assertTrue(env.install(spec("protect.before", target(), policy).before(i -> { throw failure; }).build()).isInstalled());
                assertEquals(policy == ErrorPolicy.FRAMEWORK_PROTECTIVE ? XposedInterface.ExceptionMode.PROTECTIVE
                        : XposedInterface.ExceptionMode.PASSTHROUGH, stub.mode);
                if (policy == ErrorPolicy.FRAMEWORK_PROTECTIVE) {
                    assertEquals(5, stub.call("miss", null));
                    assertEquals(1, stub.originalCalls);
                } else {
                    assertSame(failure, capture(() -> stub.call("miss", null)));
                    assertEquals(0, stub.originalCalls);
                }
            } finally { env.close(); }
        }
    }

    @Test public void protectiveAfterFailureKeepsPriorResultAndOriginalFailure() throws Throwable {
        StubFramework stub = new StubFramework(target());
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(stub.api(),
                new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
        RuntimeException callbackFailure = new RuntimeException("after");
        try {
            assertTrue(env.install(spec("protect.after", target(), ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .after((i, result) -> { throw callbackFailure; }).build()).isInstalled());
            assertEquals(5, stub.call("miss", null));
            assertEquals(1, stub.originalCalls);
            RuntimeException originalFailure = new RuntimeException("original");
            assertSame(originalFailure, capture(() -> stub.call("miss", originalFailure)));
            assertEquals(2, stub.originalCalls);
        } finally { env.close(); }
    }

    @Test public void conditionalObserverFailureFallsThroughOnlyByFrameworkModel() throws Throwable {
        StubFramework stub = new StubFramework(target());
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(stub.api(),
                new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
        try {
            assertTrue(env.install(spec("protect.conditional", target(), ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .replaceIfStringArgumentEquals(0, "hit", 10, () -> { throw new RuntimeException("observer"); })
                    .build()).isInstalled());
            assertEquals(5, stub.call("hit", null));
            assertEquals(1, stub.originalCalls);
            assertEquals(5, stub.call("miss", null));
            assertEquals(2, stub.originalCalls);
        } finally { env.close(); }
    }

    @SuppressWarnings("removal")
    @Test public void fatalLeavesTheSdkAndIsSubjectToTheFrameworkModel() throws Throwable {
        StubFramework stub = new StubFramework(target());
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(stub.api(),
                new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
        ThreadDeath fatal = new ThreadDeath();
        try {
            assertTrue(env.install(spec("protect.fatal", target(), ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .before(i -> { throw fatal; }).build()).isInstalled());
            assertEquals(5, stub.call("unused", null));
            assertSame("the SDK rethrows fatal to the framework", fatal, stub.lastEscaped);
            assertEquals("only the simulated framework invokes the downstream once", 1, stub.originalCalls);
        } finally { env.close(); }
    }

    @Test public void replacementCannotChangeFrameworkExceptionMode() throws Throwable {
        StubFramework stub = new StubFramework(target());
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(stub.api(),
                new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
        try {
            RegistrationReceipt first = env.install(spec("protect.replace", target(), ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .replaceConstant(7).build());
            assertTrue(first.toString(), first.isInstalled());
            RegistrationReceipt rejected = env.replace(spec("protect.replace", target(), ErrorPolicy.PROPAGATE)
                    .replaceConstant(8).build());
            assertEquals(RegistrationReceipt.Status.CONFLICT, rejected.status());
            assertEquals(0, stub.replaceCalls);
            assertEquals(7, stub.call("unused", null));
            RegistrationReceipt next = env.replace(spec("protect.replace", target(), ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .replaceConstant(9).build());
            assertTrue(next.toString(), next.isInstalled());
            assertEquals(1, stub.replaceCalls);
            first.registration().close();
            assertEquals(9, stub.call("unused", null));
        } finally { env.close(); }
    }

    @Test public void propagationCannotBeReplacedByProtectiveMode() throws Throwable {
        StubFramework stub = new StubFramework(target());
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(stub.api(),
                new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
        try {
            assertTrue(env.install(spec("protect.reverse", target(), ErrorPolicy.PROPAGATE)
                    .replaceConstant(7).build()).isInstalled());
            RegistrationReceipt rejected = env.replace(spec("protect.reverse", target(), ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .replaceConstant(8).build());
            assertEquals(RegistrationReceipt.Status.CONFLICT, rejected.status());
            assertEquals(0, stub.replaceCalls);
            assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, stub.mode);
            assertEquals(7, stub.call("unused", null));
        } finally { env.close(); }
    }

    private interface ThrowingCall { Object run() throws Throwable; }
    private static Throwable capture(ThrowingCall call) {
        try { call.run(); fail("expected throwable"); return null; } catch (Throwable failure) { return failure; }
    }

    private static final class StubFramework {
        final Method target;
        XposedInterface.Hooker hooker;
        XposedInterface.ExceptionMode mode;
        Throwable lastEscaped;
        int originalCalls;
        int replaceCalls;
        StubFramework(Method target) { this.target = target; }
        XposedInterface api() {
            return proxy(XposedInterface.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getApiVersion": return 102;
                    case "getFrameworkName": return "protective-model";
                    case "getFrameworkVersion": return "fixture";
                    case "hook": return builder();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
        XposedInterface.HookBuilder builder() {
            return proxy(XposedInterface.HookBuilder.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "setPriority": return object;
                    case "setExceptionMode": mode = (XposedInterface.ExceptionMode) args[0]; return object;
                    case "intercept": hooker = (XposedInterface.Hooker) args[0]; return handle();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
        XposedInterface.HookHandle handle() {
            return proxy(XposedInterface.HookHandle.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "unhook": return null;
                    case "getExecutable": return target;
                    case "getId": return null;
                    case "replaceHook": replaceCalls++; hooker = (XposedInterface.Hooker) args[0]; return handle();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
        Object call(String argument, Throwable failure) throws Throwable {
            final boolean[] proceeded = {false};
            final Object[] result = {null};
            final Throwable[] downstreamFailure = {null};
            XposedInterface.Chain chain = proxy(XposedInterface.Chain.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getExecutable": return target;
                    case "getThisObject": return null;
                    case "getArg": return argument;
                    case "getArgs": return Collections.singletonList(argument);
                    case "proceed":
                        originalCalls++;
                        proceeded[0] = true;
                        if (failure != null) { downstreamFailure[0] = failure; throw failure; }
                        result[0] = 5;
                        return result[0];
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
            try {
                return hooker.intercept(chain);
            } catch (Throwable escaped) {
                lastEscaped = escaped;
                if (mode != XposedInterface.ExceptionMode.PROTECTIVE) throw escaped;
                // This explicitly models the upstream contract; production SDK does not replay.
                if (!proceeded[0]) return chain.proceed();
                if (downstreamFailure[0] != null) throw downstreamFailure[0];
                return result[0];
            }
        }
        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }
    }
}
