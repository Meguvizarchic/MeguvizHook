package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.EntrySnapshotInvocation;
import io.github.libxposed.api.XposedInterface;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** B03 callback contract against JVM stubs; no framework or device behaviour is asserted. */
public class SnapshotHookTest {
    public static final class Targets {
        static AtomicLong[] counters;
        public static AtomicLong[] observationCounters() { return counters.clone(); }
        public static long snapshot(int[] box, int delta) { box[0] += delta; return 42L; }
        public static long invalidSnapshot(int[] box, int delta) { box[0] += 2 * delta; return 42L; }
    }

    private BenchmarkEntry.Observations observations;

    @Before public void prepare() throws Throwable {
        Targets.counters = new AtomicLong[]{new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
        observations = BenchmarkEntry.Observations.from(Targets.class);
    }

    @Test public void a0CopiesSlotsAndPreservesSharedObjectAcrossProceed() throws Throwable {
        for (boolean observed : new boolean[]{false, true}) {
            prepare();
            int[] box = {0};
            List<Object> original = new ArrayList<>(Arrays.asList(box, 1));
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, args) -> {
                        if ("getArgs".equals(method.getName())) return original;
                        if ("proceed".equals(method.getName())) {
                            assertNotNull(args);
                            assertEquals(1, args.length);
                            Object[] forwarded = (Object[]) args[0];
                            assertSame(box, forwarded[0]);
                            assertEquals(1, forwarded[1]);
                            Targets.counters[2].incrementAndGet();
                            ((int[]) forwarded[0])[0]++;
                            forwarded[0] = null; // must not change the captured entry slots
                            return 42L;
                        }
                        throw new AssertionError("unexpected " + method.getName());
                    });
            XposedInterface.Hooker hook = observed ? new BenchmarkEntry.ObservedSnapshotHooker(observations)
                    : new BenchmarkEntry.MeasurementSnapshotHooker();
            assertEquals(42L, hook.intercept(chain));
            assertEquals(1, box[0]);
            assertSame(box, original.get(0));
            assertEquals(observed ? 1L : 0L, Targets.counters[0].get());
            assertEquals(observed ? 1L : 0L, Targets.counters[1].get());
            assertEquals(1L, Targets.counters[2].get());
            assertEquals(0L, Targets.counters[3].get());
        }
    }

    @Test public void a2ReadsBothEntrySlotsBeforeAndAfterAndCountsObservedCalls() throws Throwable {
        for (boolean observed : new boolean[]{false, true}) {
            prepare();
            int[] box = {0};
            Object[] snapshot = {box, 1};
            List<Integer> indexes = new ArrayList<>();
            EntrySnapshotInvocation invocation = (EntrySnapshotInvocation) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{EntrySnapshotInvocation.class},
                    (proxy, method, args) -> {
                        if ("getEntryArgument".equals(method.getName())) {
                            int index = (Integer) args[0];
                            indexes.add(index);
                            return snapshot[index];
                        }
                        throw new AssertionError("unexpected " + method.getName());
                    });
            if (observed) new BenchmarkEntry.ObservedSnapshotBefore(observations).before(invocation);
            else new BenchmarkEntry.MeasurementSnapshotBefore().before(invocation);
            box[0]++;
            if (observed) new BenchmarkEntry.ObservedSnapshotAfter(observations).after(invocation, null);
            else new BenchmarkEntry.MeasurementSnapshotAfter().after(invocation, null);
            assertEquals(Arrays.asList(0, 1, 0, 1), indexes);
            assertEquals(1, ((int[]) snapshot[0])[0]);
            assertEquals(observed ? 1L : 0L, Targets.counters[0].get());
            assertEquals(observed ? 1L : 0L, Targets.counters[1].get());
        }
    }

    @Test public void preflightRequiresSingleBoxUpdateAndRequestsAreExplicit() throws Throwable {
        Method invoke = BenchmarkEntry.class.getDeclaredMethod("invokeTarget", Method.class, String.class);
        invoke.setAccessible(true);
        assertEquals(42L, invoke.invoke(null, Targets.class.getMethod("snapshot", int[].class, int.class), "B03"));
        try {
            invoke.invoke(null, Targets.class.getMethod("invalidSnapshot", int[].class, int.class), "B03");
            fail("duplicate box update must fail preflight");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        try {
            invoke.invoke(null, Targets.class.getMethod("snapshot", int[].class, int.class), "B04");
            fail("unknown workload must fail");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        List<Object> input = new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755",
                "A", "B03", "A2", 0, 1, 1));
        assertFalse(BenchmarkEntry.Request.from(input).observed);
        input.set(4, "B03_OBSERVED");
        BenchmarkEntry.Request observed = BenchmarkEntry.Request.from(input);
        assertNotNull(observed);
        assertTrue(observed.observed);
        assertEquals("B03", observed.workload);
        input.set(4, "B03_UNKNOWN");
        assertNull(BenchmarkEntry.Request.from(input));
    }
}
