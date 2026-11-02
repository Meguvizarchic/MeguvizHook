package io.github.jichuo1.meguviz.hook.benchmark102;

import io.github.jichuo1.meguviz.hook.api.AroundCallback;
import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.libxposed.api.XposedInterface;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** B06 nested callback and request tests against local stubs, not an Android framework. */
public class ReentrantHookTest {
    public static final class Targets {
        static AtomicLong[] counters;
        public static AtomicLong[] observationCounters() { return counters.clone(); }
        public static int reentrant(int depth) { return depth == 0 ? 1 : 1 + reentrant(depth - 1); }
    }

    private BenchmarkEntry.Observations observations;

    @Before public void prepare() throws Throwable {
        Targets.counters = new AtomicLong[]{new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
        observations = BenchmarkEntry.Observations.from(Targets.class);
    }

    private static XposedInterface.Chain chain(int depth, XposedInterface.Hooker hook, AtomicInteger calls) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(ReentrantHookTest.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("getArgs".equals(method.getName())) return Collections.singletonList(depth);
                    if ("proceed".equals(method.getName())) {
                        assertNull(args);
                        calls.incrementAndGet();
                        return depth == 0 ? 1 : 1 + (Integer) hook.intercept(chain(depth - 1, hook, calls));
                    }
                    throw new AssertionError("unexpected " + method.getName());
                });
    }

    private static int sdkInvocation(int depth, AroundCallback callback, AtomicInteger calls) throws Throwable {
        CallOutcome[] result = new CallOutcome[1];
        AroundInvocation invocation = (AroundInvocation) Proxy.newProxyInstance(ReentrantHookTest.class.getClassLoader(),
                new Class<?>[]{AroundInvocation.class}, (proxy, method, args) -> {
                    if ("getArgument".equals(method.getName())) { assertEquals(0, args[0]); return depth; }
                    if ("proceed".equals(method.getName())) {
                        assertNull(args);
                        calls.incrementAndGet();
                        result[0] = CallOutcome.returned(depth == 0 ? 1 : 1 + sdkInvocation(depth - 1, callback, calls));
                        return result[0];
                    }
                    throw new AssertionError("unexpected " + method.getName());
                });
        callback.around(invocation);
        return (Integer) result[0].value();
    }

    @Test public void directAndSdkCallbacksPreserveFourIndependentDepths() throws Throwable {
        for (boolean observed : new boolean[]{false, true}) {
            prepare();
            AtomicInteger directCalls = new AtomicInteger();
            XposedInterface.Hooker direct = observed ? new BenchmarkEntry.ObservedReentrantHooker(observations)
                    : new BenchmarkEntry.MeasurementReentrantHooker();
            assertEquals(4, direct.intercept(chain(3, direct, directCalls)));
            assertEquals(4, directCalls.get());
            assertEquals(observed ? 4 : 0, Targets.counters[0].get());
            assertEquals(observed ? 4 : 0, Targets.counters[1].get());
            assertEquals(0, Targets.counters[3].get());
            prepare();
            AtomicInteger sdkCalls = new AtomicInteger();
            AroundCallback sdk = observed ? new BenchmarkEntry.ObservedReentrantCallback(observations)
                    : new BenchmarkEntry.MeasurementReentrantCallback();
            assertEquals(4, sdkInvocation(3, sdk, sdkCalls));
            assertEquals(4, sdkCalls.get());
            assertEquals(observed ? 4 : 0, Targets.counters[0].get());
            assertEquals(observed ? 4 : 0, Targets.counters[1].get());
            assertEquals(0, Targets.counters[3].get());
        }
    }

    @Test public void alteredDepthAndWrongResultAreRejected() throws Throwable {
        AtomicInteger reads = new AtomicInteger();
        XposedInterface.Chain changed = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("getArgs".equals(method.getName())) return Collections.singletonList(reads.getAndIncrement() == 0 ? 3 : 2);
                    if ("proceed".equals(method.getName())) return 4;
                    throw new AssertionError(method.getName());
                });
        try { new BenchmarkEntry.MeasurementReentrantHooker().intercept(changed); fail("changed depth accepted"); }
        catch (IllegalStateException expected) { assertEquals("reentrant depth changed", expected.getMessage()); }
        AroundInvocation wrong = (AroundInvocation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AroundInvocation.class}, (proxy, method, args) -> {
                    if ("getArgument".equals(method.getName())) return 3;
                    if ("proceed".equals(method.getName())) return CallOutcome.returned(5);
                    throw new AssertionError(method.getName());
                });
        try { new BenchmarkEntry.MeasurementReentrantCallback().around(wrong); fail("wrong result accepted"); }
        catch (IllegalStateException expected) { assertEquals("invalid reentrant result", expected.getMessage()); }
    }

    @Test public void sdkFailureRemainsOriginalAndIsNotNormalExit() throws Throwable {
        RuntimeException failure = new RuntimeException("downstream");
        CallOutcome outcome = CallOutcome.threw(failure);
        AroundInvocation invocation = (AroundInvocation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AroundInvocation.class}, (proxy, method, args) -> {
                    if ("getArgument".equals(method.getName())) return 3;
                    if ("proceed".equals(method.getName())) return outcome;
                    throw new AssertionError(method.getName());
                });
        new BenchmarkEntry.ObservedReentrantCallback(observations).around(invocation);
        assertSame(failure, outcome.throwable());
        assertEquals(1, Targets.counters[0].get());
        assertEquals(0, Targets.counters[1].get());
    }

    @Test public void targetAndBudgetGuardAreExplicit() throws Throwable {
        Method invoke = BenchmarkEntry.class.getDeclaredMethod("invokeTarget", Method.class, String.class);
        invoke.setAccessible(true);
        assertEquals(4, invoke.invoke(null, Targets.class.getMethod("reentrant", int.class), "B06"));
        List<Object> input = new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755",
                "A", "B06", "A2", 0, 2, 1_000_000));
        assertNotNull(BenchmarkEntry.Request.from(input));
        input.set(4, "B06_OBSERVED");
        assertTrue(BenchmarkEntry.Request.from(input).observed);
        input.set(7, 3);
        assertNull(BenchmarkEntry.Request.from(input));
        input.set(4, "B05");
        assertNotNull(BenchmarkEntry.Request.from(input));
    }
}
