package io.github.jichuo1.meguviz.hook.benchmark102;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Calls the actual production callbacks, request guard, and reflected-control checks. */
public class B10ContractTest {
    static final OutOfMemoryError FATAL = new OutOfMemoryError("controlled B10 fatal");
    public static final class Controls {
        public static void prepare() { }
        public static long call() { return 42; }
        public static long originalCount() { return 0; }
        public long instanceCount() { return 0; }
        public static int intCount() { return 0; }
        public static long withArgs(boolean value) { return 0; }
        public static long fatal() { throw FATAL; }
    }
    public static final class InheritedControls extends ParentControls { }
    public static class ParentControls {
        public static void prepare() { }
        public static long call() { return 42; }
        public static long originalCount() { return 0; }
    }
    private static List<Object> request(String control) {
        return new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755", "A", "B10", control, 0, 1, 1));
    }
    private XposedInterface.Chain chain(AtomicInteger calls, Throwable failure) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if (!"proceed".equals(method.getName())) throw new AssertionError("NO_ARGS callback inspected chain");
                    assertTrue(args == null || args.length == 0); calls.incrementAndGet();
                    if (failure != null) throw failure;
                    return 42L;
                });
    }
    private <T> T unreadable(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new AssertionError("count-only observer read or mutated invocation/result"); }));
    }

    @Test public void directCallbackOnlyCountsAndProceedsExactlyOnce() throws Throwable {
        BenchmarkEntry.B10Callbacks callback = new BenchmarkEntry.B10Callbacks(); AtomicInteger original = new AtomicInteger();
        assertEquals(42L, callback.intercept(chain(original, null)));
        B10Runner.State value = callback.state(original.get()); assertEquals(1, value.before); assertEquals(1, value.after); assertEquals(1, value.original);
    }
    @Test public void actualSdkBeforeAndAfterDoNotReadArgumentsResultsOrThrowable() throws Throwable {
        BenchmarkEntry.B10Callbacks callback = new BenchmarkEntry.B10Callbacks();
        BeforeCallback before = callback; AfterCallback after = callback;
        before.before(unreadable(HookInvocation.class)); after.after(unreadable(HookInvocation.class), unreadable(InvocationResult.class));
        B10Runner.State value = callback.state(1); assertEquals(1, value.before); assertEquals(1, value.after); assertEquals(1, value.original);
    }
    @Test public void directFailureIsNotWrappedOrReplayedAndFatalSkipsAfter() throws Throwable {
        for (Throwable expected : new Throwable[]{new IllegalStateException("ordinary"), FATAL}) {
            BenchmarkEntry.B10Callbacks callback = new BenchmarkEntry.B10Callbacks(); AtomicInteger original = new AtomicInteger();
            try { callback.intercept(chain(original, expected)); fail("must throw original failure"); }
            catch (Throwable actual) { assertSame(expected, actual); }
            assertEquals(1, original.get()); assertEquals(1, callback.state(1).before);
            assertEquals(expected == FATAL ? 0 : 1, callback.state(1).after);
        }
        try { BenchmarkEntry.b09InvokeStatic(Controls.class.getMethod("fatal")); fail("reflection must unwrap fatal"); }
        catch (OutOfMemoryError actual) { assertSame(FATAL, actual); }
    }
    @Test public void fixedB10RequestAcceptsOnlyFreshUnobservedA0OrA2() {
        for (String control : new String[]{"A0", "A2"}) {
            BenchmarkEntry.Request good = BenchmarkEntry.Request.from(request(control));
            assertNotNull(good); assertEquals("B10", good.workload); assertEquals(control, good.control); assertFalse(good.observed); assertNull(good.failureCase);
            for (Object[] change : new Object[][]{{4,"B10_OBSERVED"},{4,"B10_BEFORE"},{4,"B11"},{5,"A1"},
                    {6,1},{7,2},{8,2},{6,0L},{7,true},{8,"1"},{3,"C"},{0,"not-uuid"}}) {
                List<Object> bad = request(control); bad.set((Integer)change[0], change[1]); assertNull(BenchmarkEntry.Request.from(bad));
            }
            for (int[] duplicate : new int[][]{{0,1},{0,2},{1,2}}) {
                List<Object> bad = request(control); bad.set(duplicate[1], bad.get(duplicate[0])); assertNull(BenchmarkEntry.Request.from(bad));
            }
            List<Object> bad = request(control); bad.add("extra"); assertNull(BenchmarkEntry.Request.from(bad));
            bad = request(control); bad.remove(8); assertNull(BenchmarkEntry.Request.from(bad));
        }
    }
    @Test public void reflectionControlsMustBeDeclaredPublicStaticNoArgsWithExactReturnTypes() throws Exception {
        BenchmarkEntry.b10ValidateControls(Controls.class, Controls.class.getMethod("prepare"), Controls.class.getMethod("call"), Controls.class.getMethod("originalCount"));
        for (String invalid : new String[]{"instanceCount", "intCount"}) {
            try {
                BenchmarkEntry.b10ValidateControls(Controls.class, Controls.class.getMethod("prepare"), Controls.class.getMethod("call"), Controls.class.getMethod(invalid));
                fail("wrong control accepted");
            } catch (IllegalStateException expected) { assertEquals("instanceCount".equals(invalid) ? "invalid B10 target controls" : "invalid B10 target control return types", expected.getMessage()); }
        }
        try {
            BenchmarkEntry.b10ValidateControls(Controls.class, Controls.class.getMethod("prepare"), Controls.class.getMethod("withArgs", boolean.class), Controls.class.getMethod("originalCount"));
            fail("arguments accepted");
        } catch (IllegalStateException expected) { assertEquals("invalid B10 target controls", expected.getMessage()); }
        try {
            BenchmarkEntry.b10ValidateControls(InheritedControls.class, InheritedControls.class.getMethod("prepare"), InheritedControls.class.getMethod("call"), InheritedControls.class.getMethod("originalCount"));
            fail("inherited controls accepted");
        } catch (IllegalStateException expected) { assertEquals("invalid B10 target controls", expected.getMessage()); }
    }
}
