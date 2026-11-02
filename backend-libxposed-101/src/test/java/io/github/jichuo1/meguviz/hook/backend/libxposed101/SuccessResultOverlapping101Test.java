package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.core.ConditionalDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.SuccessResultDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Binding of overlapping optional SPI interfaces; this is a JVM proxy, not a framework test. */
public class SuccessResultOverlapping101Test {
    public static Object target() { return "original"; }

    @Test public void successRouteNeverReadsUnselectedConditionalOrInvocationProperties() throws Throwable {
        Hybrid channel = new Hybrid();
        Class<?> type = Class.forName(LibXposed101Backend.class.getName() + "$SwitchingHooker");
        Constructor<?> constructor = type.getDeclaredConstructor(Executable.class, DispatchChannel.class);
        constructor.setAccessible(true);
        XposedInterface.Hooker hooker = (XposedInterface.Hooker) constructor.newInstance(
                getClass().getMethod("target"), channel);

        AtomicInteger proceeds = new AtomicInteger();
        Object original = new Object();
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if ("proceed".equals(method.getName()) && (args == null || args.length == 0)) {
                        proceeds.incrementAndGet();
                        return original;
                    }
                    throw new AssertionError("unexpected chain operation: " + method.getName());
                });
        assertEquals(1, channel.valueReads);
        assertEquals(0, channel.requirementReads);
        assertEquals(0, channel.argumentReads);
        assertSame(channel.value, hooker.intercept(chain));
        assertEquals(1, proceeds.get());
        assertEquals(1, channel.enters);
        assertEquals(1, channel.exits);

        channel.accept = false;
        assertSame(original, hooker.intercept(chain));
        assertEquals(2, proceeds.get());
        assertEquals(2, channel.enters);
        assertEquals(1, channel.exits);
        assertEquals(1, channel.valueReads);
        assertEquals(0, channel.requirementReads);
        assertEquals(0, channel.argumentReads);
    }

    private static final class Hybrid implements SuccessResultDispatchChannel, ConditionalDispatchChannel {
        final Object value = new Object();
        int valueReads;
        int requirementReads;
        int argumentReads;
        int enters;
        int exits;
        boolean accept = true;

        @Override public boolean requiresInvocation() { requirementReads++; return true; }
        @Override public boolean tryEnter() { enters++; return accept; }
        @Override public Object constantValue() { valueReads++; return value; }
        @Override public void exit() { exits++; }
        @Override public int argumentIndex() {
            argumentReads++;
            throw new AssertionError("unselected conditional getter must not be called");
        }
        @Override public boolean matches(Object value) { throw new AssertionError("unselected conditional path"); }
        @Override public void notifyMatch() { throw new AssertionError("unselected conditional path"); }
        @Override public DispatchResult dispatch(BackendInvocation invocation) {
            throw new AssertionError("selected success route must not dispatch through fallback");
        }
    }
}
