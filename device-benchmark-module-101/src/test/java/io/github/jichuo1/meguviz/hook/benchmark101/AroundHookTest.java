package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.libxposed.api.XposedInterface;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** B05 callbacks use stubs, not a real framework or device. */
public class AroundHookTest {
    public static final class Targets {
        static AtomicLong[] counters;
        public static AtomicLong[] observationCounters() { return counters.clone(); }
        public static long aroundValue() { return 42L; }
    }

    private BenchmarkEntry.Observations observations;

    @Before public void prepare() throws Throwable {
        Targets.counters = new AtomicLong[]{new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
        observations = BenchmarkEntry.Observations.from(Targets.class);
    }

    @Test public void directAroundProceedsOnceAndReturnsSameResult() throws Throwable {
        for (boolean observed : new boolean[]{false, true}) {
            prepare();
            AtomicInteger calls = new AtomicInteger();
            Object value = new Object();
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, args) -> {
                        assertEquals("proceed", method.getName());
                        assertNull(args);
                        calls.incrementAndGet();
                        return value;
                    });
            XposedInterface.Hooker hook = observed ? new BenchmarkEntry.ObservedAroundHooker(observations)
                    : new BenchmarkEntry.MeasurementAroundHooker();
            assertSame(value, hook.intercept(chain));
            assertEquals(1, calls.get());
            assertEquals(observed ? 1L : 0L, Targets.counters[0].get());
            assertEquals(observed ? 1L : 0L, Targets.counters[1].get());
            assertEquals(0L, Targets.counters[3].get());
        }
    }

    @Test public void sdkAroundProceedsOnceWithoutOverwritingOutcome() throws Throwable {
        for (boolean observed : new boolean[]{false, true}) {
            prepare();
            AtomicInteger calls = new AtomicInteger();
            Object value = new Object();
            CallOutcome outcome = CallOutcome.returned(value);
            AroundInvocation invocation = (AroundInvocation) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{AroundInvocation.class},
                    (proxy, method, args) -> {
                        assertEquals("proceed", method.getName());
                        calls.incrementAndGet();
                        return outcome;
                    });
            if (observed) new BenchmarkEntry.ObservedAroundCallback(observations).around(invocation);
            else new BenchmarkEntry.MeasurementAroundCallback().around(invocation);
            assertEquals(1, calls.get());
            assertSame(value, outcome.value());
            assertEquals(observed ? 1L : 0L, Targets.counters[0].get());
            assertEquals(observed ? 1L : 0L, Targets.counters[1].get());
        }
    }

    @Test public void failedContinuationIsNotCountedAsNormalExit() throws Throwable {
        RuntimeException failure = new RuntimeException("original");
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> { throw failure; });
        try {
            new BenchmarkEntry.ObservedAroundHooker(observations).intercept(chain);
            fail("original exception must propagate");
        } catch (RuntimeException actual) {
            assertSame(failure, actual);
        }
        assertEquals(1L, Targets.counters[0].get());
        assertEquals(0L, Targets.counters[1].get());
        prepare();
        CallOutcome outcome = CallOutcome.threw(failure);
        AroundInvocation invocation = (AroundInvocation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AroundInvocation.class},
                (proxy, method, args) -> { assertEquals("proceed", method.getName()); return outcome; });
        new BenchmarkEntry.ObservedAroundCallback(observations).around(invocation);
        assertSame(failure, outcome.throwable());
        assertEquals(1L, Targets.counters[0].get());
        assertEquals(0L, Targets.counters[1].get());
    }

    @Test public void targetAndRequestAreExplicit() throws Throwable {
        Method invoke = BenchmarkEntry.class.getDeclaredMethod("invokeTarget", Method.class, String.class);
        invoke.setAccessible(true);
        assertEquals(42L, invoke.invoke(null, Targets.class.getMethod("aroundValue"), "B05"));
        List<Object> input = new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755",
                "A", "B05", "A2", 0, 1, 1));
        assertFalse(BenchmarkEntry.Request.from(input).observed);
        input.set(4, "B05_OBSERVED");
        BenchmarkEntry.Request observed = BenchmarkEntry.Request.from(input);
        assertNotNull(observed);
        assertTrue(observed.observed);
        assertEquals("B05", observed.workload);
        input.set(4, "B05_UNKNOWN");
        assertNull(BenchmarkEntry.Request.from(input));
    }
}
