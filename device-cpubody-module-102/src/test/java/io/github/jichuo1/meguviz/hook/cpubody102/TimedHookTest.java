package io.github.jichuo1.meguviz.hook.cpubody102;

import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Clock and chain stubs test the actual callbacks; not a real-framework performance claim. */
public class TimedHookTest {
    @Test public void proceedCostIsOutsideBothB01IntervalsAndThrowableIdentityIsRetained() throws Throwable {
        AtomicLong clock = new AtomicLong(100);
        ProbeEntry.Trace trace = trace(clock::incrementAndGet, true);
        AtomicInteger calls = new AtomicInteger();
        XposedInterface.Chain chain = chain(trace, clock, calls, false);
        assertEquals(42L, new ProbeEntry.TimedNoArgsHooker(trace).intercept(chain));
        assertEquals(1, calls.get());
        assertEquals(101, trace.bs[0]); assertEquals(102, trace.be[0]);
        assertEquals(1_000_000_103L, trace.as[0]); assertEquals(1_000_000_104L, trace.ae[0]);
        assertEquals(15, trace.present[0]); assertEquals(0, trace.fault.get());

        AtomicLong clock2 = new AtomicLong();
        ProbeEntry.Trace trace2 = trace(clock2::incrementAndGet, true);
        IllegalStateException original = new IllegalStateException("original");
        XposedInterface.Chain throwing = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("proceed".equals(method.getName())) { trace2.state[1] = 2; throw original; }
                    throw new AssertionError(method.getName());
                });
        try { new ProbeEntry.TimedNoArgsHooker(trace2).intercept(throwing); fail("expected original"); }
        catch (IllegalStateException caught) { assertSame(original, caught); }
        assertEquals(1, trace2.after[0]);
    }

    @Test public void b02MeasuresBothReadsBeforeProceedAndSdkUsesSameBoundary() throws Throwable {
        AtomicLong clock = new AtomicLong(10);
        ProbeEntry.Trace trace = trace(clock::incrementAndGet, false);
        AtomicInteger calls = new AtomicInteger(), reads = new AtomicInteger();
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("getArgs".equals(method.getName())) return new AbstractList<Object>() {
                        @Override public Object get(int i) { reads.incrementAndGet(); clock.addAndGet(5); return i; }
                        @Override public int size() { return 2; }
                    };
                    if ("proceed".equals(method.getName())) { calls.incrementAndGet(); trace.state[1] = 0; return 7; }
                    throw new AssertionError(method.getName());
                });
        assertEquals(7, new ProbeEntry.TimedReadArgsHooker(trace).intercept(chain));
        assertEquals(2, reads.get()); assertEquals(1, calls.get());
        assertEquals(11, trace.bs[0]); assertEquals(22, trace.be[0]); assertEquals(0, trace.after[0]);

        AtomicLong sdkClock = new AtomicLong(10);
        ProbeEntry.Trace sdk = trace(sdkClock::incrementAndGet, false);
        ReadArgsInvocation invocation = (ReadArgsInvocation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ReadArgsInvocation.class}, (proxy, method, args) -> {
                    if ("getArgument".equals(method.getName())) { sdkClock.addAndGet(5); return 0; }
                    throw new AssertionError(method.getName());
                });
        new ProbeEntry.TimedReadArgsBefore(sdk).before(invocation);
        assertEquals(11, sdk.bs[0]); assertEquals(22, sdk.be[0]);
    }

    @Test public void fatalDownstreamDoesNotEnterAfterOrReplay() throws Throwable {
        AtomicLong clock = new AtomicLong();
        ProbeEntry.Trace trace = trace(clock::incrementAndGet, true);
        AtomicInteger calls = new AtomicInteger();
        ThreadDeath fatal = new ThreadDeath();
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("proceed".equals(method.getName())) { calls.incrementAndGet(); throw fatal; }
                    throw new AssertionError(method.getName());
                });
        try { new ProbeEntry.TimedNoArgsHooker(trace).intercept(chain); fail("expected fatal"); }
        catch (ThreadDeath caught) { assertSame(fatal, caught); }
        assertEquals(1, calls.get()); assertEquals(0, trace.after[0]); assertEquals(2, clock.get());
    }

    @Test public void fatalArgumentReadNeverReadsEndClockAndNonfatalKeepsIdentity() throws Throwable {
        AtomicLong clock = new AtomicLong();
        ProbeEntry.Trace trace = trace(clock::incrementAndGet, false);
        AtomicInteger proceeds = new AtomicInteger();
        ThreadDeath fatal = new ThreadDeath();
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("getArgs".equals(method.getName())) throw fatal;
                    if ("proceed".equals(method.getName())) { proceeds.incrementAndGet(); return 7; }
                    throw new AssertionError(method.getName());
                });
        try { new ProbeEntry.TimedReadArgsHooker(trace).intercept(chain); fail("expected fatal"); }
        catch (ThreadDeath caught) { assertSame(fatal, caught); }
        assertEquals(0, proceeds.get()); assertEquals(1, clock.get());

        AtomicLong sdkClock = new AtomicLong();
        ProbeEntry.Trace sdk = trace(sdkClock::incrementAndGet, false);
        ReadArgsInvocation invocation = (ReadArgsInvocation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ReadArgsInvocation.class}, (proxy, method, args) -> { throw fatal; });
        try { new ProbeEntry.TimedReadArgsBefore(sdk).before(invocation); fail("expected fatal"); }
        catch (ThreadDeath caught) { assertSame(fatal, caught); }
        assertEquals(1, sdkClock.get());

        AtomicLong normalClock = new AtomicLong();
        ProbeEntry.Trace normal = trace(normalClock::incrementAndGet, false);
        IllegalStateException original = new IllegalStateException("argument read");
        XposedInterface.Chain badArgs = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> { throw original; });
        try { new ProbeEntry.TimedReadArgsHooker(normal).intercept(badArgs); fail("expected original"); }
        catch (IllegalStateException caught) { assertSame(original, caught); }
        assertEquals(2, normalClock.get());
    }

    @Test public void unavailableClockIsRecordedWithoutSuppressingOriginal() throws Throwable {
        ProbeEntry.Trace trace = trace(() -> -1L, false);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(7, new ProbeEntry.TimedReadArgsHooker(trace).intercept(chain(trace, new AtomicLong(), calls, true)));
        assertEquals(1, calls.get()); assertEquals(5, trace.fault.get());
        assertEquals(-1, trace.bs[0]); assertEquals(-1, trace.be[0]); assertEquals(3, trace.present[0]);
    }

    private static ProbeEntry.Trace trace(ProbeEntry.CpuClock clock, boolean after) {
        int[] state = new int[6]; state[5] = after ? 1 : 0;
        return new ProbeEntry.Trace(state, new int[1], new int[1], new int[1], new int[1],
                new long[1], new long[1], new long[1], new long[1], new AtomicInteger(), Thread.currentThread(), clock);
    }

    private XposedInterface.Chain chain(ProbeEntry.Trace trace, AtomicLong clock, AtomicInteger calls, boolean argsMode) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("getArgs".equals(method.getName()) && argsMode) return java.util.Arrays.asList(3, 4);
                    if ("proceed".equals(method.getName())) {
                        calls.incrementAndGet(); clock.addAndGet(1_000_000_000L);
                        trace.state[1] = argsMode ? 0 : 2;
                        if (argsMode) return Integer.valueOf(7);
                        return Long.valueOf(42L);
                    }
                    throw new AssertionError(method.getName());
                });
    }
}
