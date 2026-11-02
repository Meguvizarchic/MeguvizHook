package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.ConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.FusedConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Proxy checks for the optional 102 fused route; not a real framework or ART test. */
public class FusedConstantDispatch102Test {
    public static Object target() { return "original"; }

    private XposedInterface.Hooker hooker(DispatchChannel channel) throws Exception {
        Executable target = getClass().getMethod("target");
        HookSpec spec = new HookSpec.Builder(HookId.of("fused.proxy"), HookOwner.of("test"),
                TargetSpec.ofMember(target)).before(invocation -> { }).build();
        Constructor<BoundPlan> constructor = BoundPlan.class.getDeclaredConstructor(HookSpec.class,
                Executable.class, long.class, ClassLoader.class, long.class);
        constructor.setAccessible(true);
        BoundPlan plan = constructor.newInstance(spec, target, 1L, getClass().getClassLoader(), 1L);
        return LibXposed102Backend.hooker(plan, channel);
    }

    private XposedInterface.Chain chain(AtomicInteger proceeds) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if ("proceed".equals(method.getName())) { proceeds.incrementAndGet(); return "original"; }
                    throw new AssertionError("unexpected chain use " + method.getName());
                });
    }

    @Test public void fusedRouteBindsOnceUsesIdentityAndPassesThroughAfterRetirement() throws Throwable {
        Object[] values = {null, new EqualsTrap()};
        for (Object value : values) {
            FusedSpy channel = new FusedSpy(value);
            XposedInterface.Hooker hooker = hooker(channel);
            AtomicInteger proceeds = new AtomicInteger();
            XposedInterface.Chain chain = chain(proceeds);
            assertSame(value, hooker.intercept(chain));
            assertSame(value, hooker.intercept(chain));
            assertEquals(1, channel.valueReads);
            assertEquals(2, channel.fusedCalls);
            assertEquals(0, proceeds.get());
            channel.accept = false;
            assertEquals("original", hooker.intercept(chain));
            assertEquals(1, proceeds.get());
            assertEquals(3, channel.fusedCalls);
            assertEquals(1, channel.valueReads);
        }
    }

    @Test public void sentinelAsLegalConstantUsesUnfusedLeaseAndNeverFallsThrough() throws Throwable {
        CollisionSpy channel = new CollisionSpy();
        XposedInterface.Hooker hooker = hooker(channel);
        AtomicInteger proceeds = new AtomicInteger();
        XposedInterface.Chain chain = chain(proceeds);
        assertSame(FusedConstantDispatchChannel.NOT_ENTERED, hooker.intercept(chain));
        assertEquals(1, channel.enters);
        assertEquals(1, channel.exits);
        assertEquals(1, channel.valueReads);
        assertEquals(0, proceeds.get());
        channel.accept = false;
        assertEquals("original", hooker.intercept(chain));
        assertEquals(2, channel.enters);
        assertEquals(1, channel.exits);
        assertEquals(1, proceeds.get());
    }

    @Test public void realSdkSentinelConstantAndRetiredSnapshotRemainDistinct() throws Throwable {
        Executable target = getClass().getMethod("target");
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<XposedInterface.Hooker>();
        XposedInterface.HookHandle handle = (XposedInterface.HookHandle) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class}, (p, m, a) -> {
                    if ("unhook".equals(m.getName())) return null;
                    if ("getExecutable".equals(m.getName())) return target;
                    throw new AssertionError("unexpected handle use " + m.getName());
                });
        XposedInterface.HookBuilder builder = (XposedInterface.HookBuilder) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class}, (p, m, a) -> {
                    if ("setPriority".equals(m.getName()) || "setExceptionMode".equals(m.getName())) return p;
                    if ("intercept".equals(m.getName())) { installed.set((XposedInterface.Hooker) a[0]); return handle; }
                    throw new AssertionError("unexpected builder use " + m.getName());
                });
        XposedInterface api = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (p, m, a) -> {
                    if ("getApiVersion".equals(m.getName())) return 102;
                    if ("getFrameworkName".equals(m.getName()) || "getFrameworkVersion".equals(m.getName())) return "stub";
                    if ("hook".equals(m.getName())) return builder;
                    throw new AssertionError("unexpected framework use " + m.getName());
                });
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(api,
                new LibXposed102Context("test", getClass().getClassLoader(), 1L)));
        try {
            RegistrationReceipt receipt = env.install(new HookSpec.Builder(HookId.of("fused.sdk.sentinel"),
                    HookOwner.of("test"), TargetSpec.ofMember(target))
                    .replaceConstant(FusedConstantDispatchChannel.NOT_ENTERED).build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            AtomicInteger proceeds = new AtomicInteger();
            XposedInterface.Chain chain = chain(proceeds);
            assertSame(FusedConstantDispatchChannel.NOT_ENTERED, installed.get().intercept(chain));
            assertEquals(0, proceeds.get());
            assertEquals(0, receipt.registration().close().inFlightAtClose());
            assertEquals("original", installed.get().intercept(chain));
            assertEquals(1, proceeds.get());
        } finally { env.close(); }
    }

    @Test public void oldThirdPartyConstantKeepsBoundGetterAndLeaseProtocol() throws Throwable {
        LegacySpy channel = new LegacySpy();
        XposedInterface.Hooker hooker = hooker(channel);
        AtomicInteger proceeds = new AtomicInteger();
        XposedInterface.Chain chain = chain(proceeds);
        assertSame(channel.value, hooker.intercept(chain));
        assertSame(channel.value, hooker.intercept(chain));
        assertEquals(1, channel.valueReads);
        assertEquals(2, channel.enters);
        assertEquals(2, channel.exits);
        assertEquals(0, proceeds.get());
    }

    @Test public void invocationRequiredFusedChannelUsesOrdinaryDispatch() throws Throwable {
        InvocationRequired channel = new InvocationRequired();
        assertEquals("generic", hooker(channel).intercept(chain(new AtomicInteger())));
    }

    @Test public void fusedFailurePropagatesWithoutAdapterRetryOrExtraExit() throws Throwable {
        RuntimeException failure = new RuntimeException("fused failure");
        FusedSpy channel = new FusedSpy("value");
        channel.failure = failure;
        AtomicInteger proceeds = new AtomicInteger();
        try {
            hooker(channel).intercept(chain(proceeds));
            fail("failure should propagate");
        } catch (RuntimeException observed) {
            assertSame(failure, observed);
        }
        assertEquals(0, proceeds.get());
    }

    @SuppressWarnings("removal")
    @Test public void fusedFatalReachesFrameworkWithoutRetryOrExtraExit() throws Throwable {
        ThreadDeath fatal = new ThreadDeath();
        FusedSpy channel = new FusedSpy("value");
        channel.fatal = fatal;
        AtomicInteger proceeds = new AtomicInteger();
        try {
            hooker(channel).intercept(chain(proceeds));
            fail("fatal event should reach the framework");
        } catch (ThreadDeath observed) {
            assertSame(fatal, observed);
        }
        assertEquals(0, proceeds.get());
    }

    private static final class EqualsTrap {
        @Override public boolean equals(Object other) { throw new AssertionError("identity comparison required"); }
        @Override public int hashCode() { return 0; }
    }

    private static final class FusedSpy implements FusedConstantDispatchChannel {
        final Object value;
        boolean accept = true;
        int valueReads;
        int fusedCalls;
        RuntimeException failure;
        Error fatal;
        FusedSpy(Object value) { this.value = value; }
        @Override public Object constantValue() {
            if (++valueReads != 1) throw new AssertionError("getter called after binding");
            return value;
        }
        @Override public Object dispatchValueOrNotEntered() {
            fusedCalls++;
            if (failure != null) throw failure;
            if (fatal != null) throw fatal;
            return accept ? value : NOT_ENTERED;
        }
        @Override public boolean tryEnter() { throw new AssertionError("backend must use fused route"); }
        @Override public void exit() { throw new AssertionError("fused route owns exit"); }
        @Override public DispatchResult dispatch(BackendInvocation invocation) { throw new AssertionError("unexpected fallback"); }
    }

    private static final class CollisionSpy implements FusedConstantDispatchChannel {
        boolean accept = true;
        int valueReads;
        int enters;
        int exits;
        @Override public Object constantValue() { valueReads++; return NOT_ENTERED; }
        @Override public Object dispatchValueOrNotEntered() { throw new AssertionError("sentinel constant requires unfused route"); }
        @Override public boolean tryEnter() { enters++; return accept; }
        @Override public void exit() { exits++; }
        @Override public DispatchResult dispatch(BackendInvocation invocation) { throw new AssertionError("unexpected generic route"); }
    }

    private static final class LegacySpy implements ConstantDispatchChannel {
        final Object value = new Object();
        int valueReads;
        int enters;
        int exits;
        @Override public Object constantValue() { if (++valueReads != 1) throw new AssertionError("getter called again"); return value; }
        @Override public boolean tryEnter() { enters++; return true; }
        @Override public void exit() { exits++; }
        @Override public DispatchResult dispatch(BackendInvocation invocation) { throw new AssertionError("unexpected generic route"); }
    }

    private static final class InvocationRequired implements FusedConstantDispatchChannel {
        @Override public boolean requiresInvocation() { return true; }
        @Override public Object constantValue() { throw new AssertionError("must not bind constant"); }
        @Override public Object dispatchValueOrNotEntered() { throw new AssertionError("must not use fused route"); }
        @Override public boolean tryEnter() { throw new AssertionError("must not use direct route"); }
        @Override public void exit() { throw new AssertionError("must not use direct route"); }
        @Override public DispatchResult dispatch(BackendInvocation invocation) {
            assertNotNull(invocation);
            return DispatchResult.returned("generic");
        }
    }
}
