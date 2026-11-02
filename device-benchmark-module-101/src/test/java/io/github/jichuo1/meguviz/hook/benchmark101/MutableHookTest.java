package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.libxposed.api.XposedInterface;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** B04 semantics against JVM stubs; does not establish framework/device behavior. */
public class MutableHookTest {
    public static final class Targets {
        static AtomicLong[] counters;
        public static AtomicLong[] observationCounters() { return counters.clone(); }
        public static int mutableAdd(int left, int right) { return left + right; }
    }

    private BenchmarkEntry.Observations observations;

    @Before public void prepare() throws Throwable {
        Targets.counters = new AtomicLong[]{new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
        observations = BenchmarkEntry.Observations.from(Targets.class);
    }

    @Test public void directControlRewritesOneSlotAndProceedsOnce() throws Throwable {
        for (boolean observed : new boolean[]{false, true}) {
            prepare();
            List<Object> incoming = new ArrayList<>(Arrays.asList(3, 4));
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, args) -> {
                        if ("getArgs".equals(method.getName())) return incoming;
                        if ("proceed".equals(method.getName())) {
                            assertNotNull(args);
                            assertEquals(1, args.length);
                            Object[] forwarded = (Object[]) args[0];
                            assertEquals(5, forwarded[0]);
                            assertEquals(4, forwarded[1]);
                            Targets.counters[2].incrementAndGet();
                            return ((Integer) forwarded[0]) + ((Integer) forwarded[1]);
                        }
                        throw new AssertionError("unexpected " + method.getName());
                    });
            XposedInterface.Hooker hook = observed ? new BenchmarkEntry.ObservedMutableHooker(observations)
                    : new BenchmarkEntry.MeasurementMutableHooker();
            assertEquals(9, hook.intercept(chain));
            assertEquals(Arrays.asList(3, 4), incoming);
            assertEquals(observed ? 1L : 0L, Targets.counters[0].get());
            assertEquals(0L, Targets.counters[1].get());
            assertEquals(1L, Targets.counters[2].get());
            assertEquals(0L, Targets.counters[3].get());
        }
    }

    @Test public void sdkControlWritesCorrectSlotInBothModes() throws Throwable {
        for (boolean observed : new boolean[]{false, true}) {
            prepare();
            Object[] slots = {3, 4};
            MutableArgsInvocation invocation = (MutableArgsInvocation) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{MutableArgsInvocation.class},
                    (proxy, method, args) -> {
                        if ("setArgument".equals(method.getName())) {
                            assertEquals(0, args[0]);
                            slots[(Integer) args[0]] = args[1];
                            return null;
                        }
                        throw new AssertionError("unexpected " + method.getName());
                    });
            if (observed) new BenchmarkEntry.ObservedMutableBefore(observations).before(invocation);
            else new BenchmarkEntry.MeasurementMutableBefore().before(invocation);
            assertEquals(9, (Integer) slots[0] + (Integer) slots[1]);
            assertEquals(observed ? 1L : 0L, Targets.counters[0].get());
            assertEquals(0L, Targets.counters[1].get());
        }
    }

    @Test public void unhookedBaselineAndRequestAreExplicit() throws Throwable {
        Method invoke = BenchmarkEntry.class.getDeclaredMethod("invokeTarget", Method.class, String.class);
        invoke.setAccessible(true);
        assertEquals(7, invoke.invoke(null, Targets.class.getMethod("mutableAdd", int.class, int.class), "B04"));
        List<Object> input = new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755",
                "A", "B04", "A2", 0, 1, 1));
        assertFalse(BenchmarkEntry.Request.from(input).observed);
        input.set(4, "B04_OBSERVED");
        BenchmarkEntry.Request observed = BenchmarkEntry.Request.from(input);
        assertNotNull(observed);
        assertTrue(observed.observed);
        assertEquals("B04", observed.workload);
        input.set(4, "B04_UNKNOWN");
        assertNull(BenchmarkEntry.Request.from(input));
    }
}
