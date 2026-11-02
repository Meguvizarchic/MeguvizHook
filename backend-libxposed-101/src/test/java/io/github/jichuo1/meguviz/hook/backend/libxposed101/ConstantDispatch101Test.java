package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.core.ConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** JVM proxy coverage of the optional direct-constant adapter path, not a framework runtime. */
public class ConstantDispatch101Test {
    public static String target() { return "original"; }

    @Test public void directConstantUsesLeaseAndClosedEntryProceedsOnce() throws Throwable {
        Spy channel = new Spy();
        Class<?> type = Class.forName(LibXposed101Backend.class.getName() + "$SwitchingHooker");
        Constructor<?> constructor = type.getDeclaredConstructor(Executable.class, DispatchChannel.class);
        constructor.setAccessible(true);
        XposedInterface.Hooker hooker = (XposedInterface.Hooker) constructor.newInstance(
                ConstantDispatch101Test.class.getMethod("target"), channel);
        assertEquals(1, channel.valueReads);
        AtomicInteger proceeds = new AtomicInteger();
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (p, m, a) -> {
                    if ("proceed".equals(m.getName())) { proceeds.incrementAndGet(); return "original"; }
                    throw new AssertionError("constant route read " + m.getName());
                });
        assertNull(hooker.intercept(chain));
        assertEquals(1, channel.enters);
        assertEquals(1, channel.exits);
        assertEquals(0, proceeds.get());
        channel.accept = false;
        assertEquals("original", hooker.intercept(chain));
        assertEquals(2, channel.enters);
        assertEquals(1, channel.exits);
        assertEquals(1, proceeds.get());
        assertEquals("constant is bound once", 1, channel.valueReads);
    }

    @Test public void thirdPartyConstantRequiringInvocationUsesGenericDispatch() throws Throwable {
        ConstantDispatchChannel channel = new InvocationRequiredSpy();
        Class<?> type = Class.forName(LibXposed101Backend.class.getName() + "$SwitchingHooker");
        Constructor<?> constructor = type.getDeclaredConstructor(Executable.class, DispatchChannel.class);
        constructor.setAccessible(true);
        XposedInterface.Hooker hooker = (XposedInterface.Hooker) constructor.newInstance(
                ConstantDispatch101Test.class.getMethod("target"), channel);
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (p, m, a) -> { throw new AssertionError("unexpected chain use " + m.getName()); });
        assertEquals("generic", hooker.intercept(chain));
    }

    private static final class InvocationRequiredSpy implements ConstantDispatchChannel {
        @Override public boolean requiresInvocation() { return true; }
        @Override public boolean tryEnter() { throw new AssertionError("must not use direct route"); }
        @Override public Object constantValue() { throw new AssertionError("must not bind constant"); }
        @Override public void exit() { throw new AssertionError("must not use direct route"); }
        @Override public DispatchResult dispatch(BackendInvocation invocation) {
            assertNotNull(invocation);
            return DispatchResult.returned("generic");
        }
    }

    private static final class Spy implements ConstantDispatchChannel {
        boolean accept = true;
        int enters;
        int exits;
        int valueReads;
        @Override public boolean tryEnter() { enters++; return accept; }
        @Override public Object constantValue() { valueReads++; return null; }
        @Override public void exit() { exits++; }
        @Override public DispatchResult dispatch(BackendInvocation invocation) {
            throw new AssertionError("direct backend must not use the generic dispatch path");
        }
    }
}
