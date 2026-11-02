package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.libxposed.api.XposedInterface;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies observed callback behaviour against a chain stub, not an Android framework. */
public class ObservedHookTest {
    public static final class Targets {
        static AtomicLong[] counters;
        public static AtomicLong[] observationCounters() { return counters.clone(); }
    }

    private BenchmarkEntry.Observations observations;

    @Before public void prepare() throws Throwable {
        Targets.counters = new AtomicLong[]{new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
        observations = BenchmarkEntry.Observations.from(Targets.class);
    }

    @Test public void observedA0CallbacksCountOriginalAndReadArguments() throws Throwable {
        AtomicInteger reads = new AtomicInteger();
        XposedInterface.Chain chain = chain(reads);
        XposedInterface.Hooker b01 = new BenchmarkEntry.ObservedNoArgsHooker(observations);
        for (int i = 0; i < 3; i++) assertEquals(7, b01.intercept(chain));
        assertEquals(3L, Targets.counters[0].get());
        assertEquals(3L, Targets.counters[1].get());
        assertEquals(3L, Targets.counters[2].get());
        assertEquals(0L, Targets.counters[3].get());
        assertEquals(0, reads.get());

        prepare();
        XposedInterface.Hooker b02 = new BenchmarkEntry.ObservedReadArgsHooker(observations);
        for (int i = 0; i < 2; i++) assertEquals(7, b02.intercept(chain));
        assertEquals(2L, Targets.counters[0].get());
        assertEquals(0L, Targets.counters[1].get());
        assertEquals(2L, Targets.counters[2].get());
        assertEquals(4, reads.get());
    }

    @Test public void observedA2CallbacksKeepShapesDistinct() throws Throwable {
        new BenchmarkEntry.ObservedNoArgsBefore(observations).before(null);
        Targets.counters[2].incrementAndGet();
        new BenchmarkEntry.ObservedNoArgsAfter(observations).after(null, null);
        assertEquals(1L, Targets.counters[0].get());
        assertEquals(1L, Targets.counters[1].get());
        assertEquals(1L, Targets.counters[2].get());

        prepare();
        AtomicInteger reads = new AtomicInteger();
        ReadArgsInvocation invocation = (ReadArgsInvocation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ReadArgsInvocation.class}, (proxy, method, args) -> {
                    if ("getArgument".equals(method.getName())) {
                        reads.incrementAndGet();
                        return ((Integer) args[0]) == 0 ? 3 : 4;
                    }
                    throw new AssertionError("unexpected " + method.getName());
                });
        new BenchmarkEntry.ObservedReadArgsBefore(observations).before(invocation);
        Targets.counters[2].incrementAndGet();
        assertEquals(1L, Targets.counters[0].get());
        assertEquals(0L, Targets.counters[1].get());
        assertEquals(1L, Targets.counters[2].get());
        assertEquals(2, reads.get());
    }

    @Test public void crossThreadUseIsMarkedAndModeIsExplicit() throws Throwable {
        Thread other = new Thread(() -> observations.count(0));
        other.start();
        other.join();
        assertEquals(1L, Targets.counters[3].get());
        List<Object> input = Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755",
                "A", "B01_OBSERVED", "A0", 0, 1, 1);
        BenchmarkEntry.Request observed = BenchmarkEntry.Request.from(input);
        assertNotNull(observed);
        assertTrue(observed.observed);
        assertEquals("B01", observed.workload);
        input.set(4, "B01");
        assertFalse(BenchmarkEntry.Request.from(input).observed);
        input.set(4, "B01_UNKNOWN");
        assertNull(BenchmarkEntry.Request.from(input));
    }

    private XposedInterface.Chain chain(AtomicInteger reads) {
        List<Object> arguments = new AbstractList<Object>() {
            @Override public Object get(int index) { reads.incrementAndGet(); return index == 0 ? 3 : 4; }
            @Override public int size() { return 2; }
        };
        return (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("getArgs".equals(method.getName())) return arguments;
                    if ("proceed".equals(method.getName())) { Targets.counters[2].incrementAndGet(); return 7; }
                    throw new AssertionError("unexpected " + method.getName());
                });
    }
}
