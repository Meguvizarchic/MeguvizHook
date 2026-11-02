package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchObserver;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** JVM mapping tests only; these proxies do not establish real framework or ART behavior. */
public class ConditionalDispatch101Test {
    public static boolean target(String key, boolean fallback) { return fallback; }
    public static String referenceTarget(String key) { return "original"; }

    @Test public void matchMissAndMissingArgumentUseOnlyTheSelectedArgument() throws Throwable {
        Fixture f = new Fixture();
        AtomicInteger notified = new AtomicInteger();
        HookEnvironment env = f.environment();
        try {
            HookRegistration first = installed(env, f.spec("hit", () -> notified.incrementAndGet()));
            assertEquals(true, f.current().intercept(f.chain("hit", false, null, null, null)));
            assertEquals(1, notified.get());
            assertEquals(0, f.proceeds.get());
            assertEquals(false, f.current().intercept(f.chain("miss", false, null, null, null)));
            assertEquals(false, f.current().intercept(f.chain(null, true, null, null, null)));
            assertEquals(2, f.proceeds.get());
            assertEquals(1, notified.get());
            RegistrationReceipt second = env.replace(f.spec(null, () -> notified.incrementAndGet()));
            assertTrue(second.toString(), second.isInstalled());
            first.close();
            assertEquals(0, f.unhooks.get());
            assertEquals(true, f.current().intercept(f.chain(null, true, null, null, null)));
            assertEquals(2, notified.get());
            assertEquals("missing matched null without running original", 2, f.proceeds.get());
            second.registration().close();
            assertEquals(1, f.unhooks.get());
            assertEquals(0, f.arrayReads.get());
        } finally { env.close(); }
    }

    @Test(timeout = 10000L) public void closeDuringMissCountsOriginalAndDoesNotWaitForIt() throws Throwable {
        Fixture f = new Fixture();
        HookEnvironment env = f.environment();
        CountDownLatch originalEntered = new CountDownLatch(1);
        CountDownLatch releaseOriginal = new CountDownLatch(1);
        AtomicReference<Object> returned = new AtomicReference<Object>();
        AtomicReference<Throwable> failed = new AtomicReference<Throwable>();
        Thread worker = null;
        try {
            HookRegistration registration = installed(env, f.spec("hit", null));
            XposedInterface.Hooker captured = f.current();
            worker = new Thread(() -> {
                try { returned.set(captured.intercept(f.chain("miss", false, originalEntered, releaseOriginal, null))); }
                catch (Throwable failure) { failed.set(failure); }
            }, "conditional-miss");
            worker.start();
            assertTrue(originalEntered.await(5L, TimeUnit.SECONDS));
            CloseReport close = registration.close();
            assertEquals("downstream original is still in flight", 1, close.inFlightAtClose());
            assertEquals(1, f.unhooks.get());
            int reads = f.argumentReads.get();
            assertEquals(false, captured.intercept(f.chain("hit", false, null, null, null)));
            assertEquals("retired route must bypass argument checks", reads, f.argumentReads.get());
            releaseOriginal.countDown();
            worker.join(5000L);
            assertFalse(worker.isAlive());
            assertNull(failed.get());
            assertEquals(false, returned.get());
            assertEquals(2, f.proceeds.get());
        } finally {
            releaseOriginal.countDown();
            if (worker != null) worker.join(5000L);
            env.close();
        }
    }

    @Test public void observerAndDownstreamFailuresPreserveIdentityAndReleaseEntry() throws Throwable {
        Fixture f = new Fixture();
        HookEnvironment env = f.environment();
        RuntimeException observerFailure = new RuntimeException("observer");
        ThreadDeath fatal = new ThreadDeath();
        AtomicReference<Throwable> observed = new AtomicReference<Throwable>(observerFailure);
        try {
            HookRegistration registration = installed(env, f.spec("hit", () -> { throw observed.get(); }));
            assertSame(observerFailure, capture(() -> f.current().intercept(f.chain("hit", false, null, null, null))));
            assertEquals(0, f.proceeds.get());
            observed.set(fatal);
            assertSame(fatal, capture(() -> f.current().intercept(f.chain("hit", false, null, null, null))));
            assertEquals(0, f.proceeds.get());
            RuntimeException originalFailure = new RuntimeException("original");
            assertSame(originalFailure, capture(() -> f.current().intercept(f.chain("miss", false, null, null, originalFailure))));
            assertEquals(1, f.proceeds.get());
            assertEquals("all failed calls must release their in-flight count", 0, registration.close().inFlightAtClose());
        } finally { env.close(); }
    }

    @Test public void observerIndexFailureIsNotMistakenForMissingArgument() throws Throwable {
        Fixture f = new Fixture();
        HookEnvironment env = f.environment();
        IndexOutOfBoundsException failure = new IndexOutOfBoundsException("observer");
        try {
            HookRegistration registration = installed(env, f.spec("hit", () -> { throw failure; }));
            assertSame(failure, capture(() -> f.current().intercept(f.chain("hit", false, null, null, null))));
            assertEquals(0, f.proceeds.get());
            assertEquals(0, registration.close().inFlightAtClose());
        } finally { env.close(); }
    }

    @Test public void nullConstantResultIsAValidConditionalHit() throws Throwable {
        Fixture f = new Fixture(ConditionalDispatch101Test.class.getMethod("referenceTarget", String.class));
        HookEnvironment env = f.environment();
        try {
            HookRegistration registration = installed(env, f.builder()
                    .replaceIfStringArgumentEquals(0, "hit", null).build());
            assertNull(f.current().intercept(f.chain("hit", false, null, null, null)));
            assertEquals(0, f.proceeds.get());
            registration.close();
            assertEquals(1, f.unhooks.get());
        } finally { env.close(); }
    }

    @Test public void conditionalNormalAndConstantReplacementsTransferOwnership() throws Throwable {
        Fixture f = new Fixture();
        HookEnvironment env = f.environment();
        try {
            HookRegistration first = installed(env, f.spec("hit", null));
            assertEquals(true, f.current().intercept(f.chain("hit", false, null, null, null)));
            HookSpec ordinary = f.builder().before(i -> i.setEarlyResult(false)).build();
            RegistrationReceipt second = env.replace(ordinary);
            assertTrue(second.toString(), second.isInstalled());
            first.close();
            assertEquals(0, f.unhooks.get());
            assertEquals(false, f.current().intercept(f.chain("hit", false, null, null, null)));
            RegistrationReceipt third = env.replace(f.builder().replaceConstant(true).build());
            assertTrue(third.toString(), third.isInstalled());
            second.registration().close();
            assertEquals(0, f.unhooks.get());
            assertEquals(true, f.current().intercept(f.chain("miss", false, null, null, null)));
            RegistrationReceipt fourth = env.replace(f.spec("hit", null));
            assertTrue(fourth.toString(), fourth.isInstalled());
            third.registration().close();
            assertEquals(0, f.unhooks.get());
            assertEquals(false, f.current().intercept(f.chain("miss", false, null, null, null)));
            assertEquals(true, f.current().intercept(f.chain("hit", false, null, null, null)));
            fourth.registration().close();
            assertEquals(1, f.unhooks.get());
            assertEquals("no replacement may reinstall the framework entry", 1, f.installs.get());
        } finally { env.close(); }
    }

    @Test public void unknownReplacementRetiresBothConditionalGenerationsOn102() throws Throwable {
        if (101 != 102) return;
        Fixture f = new Fixture();
        HookEnvironment env = f.environment();
        try {
            installed(env, f.spec("hit", null));
            XposedInterface.Hooker old = f.current();
            f.unknownReplacement = true;
            Throwable failure = capture(() -> env.replace(f.spec("different", null)));
            assertTrue(failure instanceof IllegalStateException);
            XposedInterface.Hooker next = f.current();
            int reads = f.argumentReads.get();
            assertEquals(false, old.intercept(f.chain("hit", false, null, null, null)));
            assertEquals(false, next.intercept(f.chain("different", false, null, null, null)));
            assertEquals("quarantined channels must skip selected argument", reads, f.argumentReads.get());
            assertEquals(0, f.unhooks.get());
        } finally { env.close(); }
    }

    private static HookRegistration installed(HookEnvironment env, HookSpec spec) {
        RegistrationReceipt receipt = env.install(spec);
        assertTrue(receipt.toString(), receipt.isInstalled());
        return receipt.registration();
    }

    private static Throwable capture(Throwing call) {
        try { call.run(); return null; } catch (Throwable error) { return error; }
    }

    private interface Throwing { void run() throws Throwable; }

    private static final class Fixture {
        final Method target;
        final AtomicInteger installs = new AtomicInteger();
        final AtomicInteger unhooks = new AtomicInteger();
        final AtomicInteger proceeds = new AtomicInteger();
        final AtomicInteger argumentReads = new AtomicInteger();
        final AtomicInteger arrayReads = new AtomicInteger();
        final AtomicReference<XposedInterface.Hooker> hooker = new AtomicReference<XposedInterface.Hooker>();
        boolean unknownReplacement;
        private final XposedInterface api;

        Fixture() throws NoSuchMethodException {
            this(ConditionalDispatch101Test.class.getMethod("target", String.class, boolean.class));
        }

        Fixture(Method selectedTarget) {
            target = selectedTarget;
            api = proxy(XposedInterface.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getApiVersion": return 101;
                    case "getFrameworkName": return "proxy-101";
                    case "getFrameworkVersion": return "1";
                    case "hook": installs.incrementAndGet(); return frameworkBuilder();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        HookEnvironment environment() {
            return HookEnvironment.create(new LibXposed101Backend(api,
                    new LibXposed101Context("conditional.test", getClass().getClassLoader(), 1L)));
        }

        HookSpec.Builder builder() {
            return new HookSpec.Builder(HookId.of("conditional.test"), HookOwner.of("test"), TargetSpec.ofMember(target));
        }

        HookSpec spec(String expected, MatchObserver observer) {
            return builder().replaceIfStringArgumentEquals(0, expected, true, observer).build();
        }

        XposedInterface.Hooker current() { return hooker.get(); }

        XposedInterface.HookBuilder builderProxy;
        XposedInterface.HookBuilder frameworkBuilder() {
            if (builderProxy == null) builderProxy = proxy(XposedInterface.HookBuilder.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "setPriority": case "setExceptionMode": return object;
                    case "intercept": hooker.set((XposedInterface.Hooker) args[0]); return handle();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
            return builderProxy;
        }

        XposedInterface.HookHandle handle() {
            return proxy(XposedInterface.HookHandle.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "unhook": unhooks.incrementAndGet(); return null;
                    case "replaceHook":
                        hooker.set((XposedInterface.Hooker) args[0]);
                        return unknownReplacement ? null : handle();
                    case "getExecutable": return target;
                    case "getId": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        XposedInterface.Chain chain(Object argument, boolean missing, CountDownLatch entered,
                                    CountDownLatch release, Throwable downstreamFailure) {
            return proxy(XposedInterface.Chain.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getArg":
                        argumentReads.incrementAndGet();
                        assertEquals(0, args[0]);
                        if (missing) throw new IndexOutOfBoundsException("missing");
                        return argument;
                    case "getArgs": arrayReads.incrementAndGet(); throw new AssertionError("selected-argument path copied args");
                    case "proceed":
                        assertEquals(0, method.getParameterCount());
                        proceeds.incrementAndGet();
                        if (entered != null) entered.countDown();
                        if (release != null && !release.await(5L, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                        if (downstreamFailure != null) throw downstreamFailure;
                        return false;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
