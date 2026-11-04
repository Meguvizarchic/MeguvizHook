package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.ChainDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.CoreChainBridge;
import io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor;
import java.util.function.Function;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The flyweight bridge is optional, hands the backend's own accessor and chain to the channel
 * unchanged, and decodes values exactly like {@code CoreAfterBridge}.
 */
public final class CoreChainBridgeTest {
    private static final ChainAccessor<Object> ACCESSOR = new ChainAccessor<Object>() {
        @Override public Object thisObject(Object chain) { return null; }
        @Override public Object argument(Object chain, int index) { throw new IndexOutOfBoundsException(); }
        @Override public Object[] arguments(Object chain) { return new Object[0]; }
        @Override public Object proceed(Object chain, Object[] arguments) { return null; }
    };

    private abstract static class Channel implements ChainDispatchChannel {
        @Override public Object dispatchDirect(BackendInvocation invocation) { throw new AssertionError("wrong path"); }
        @Override public DispatchResult dispatch(BackendInvocation invocation) { throw new AssertionError("wrong path"); }
    }

    @Test public void channelsWithoutTheChainTransportKeepTheirExistingPath() {
        DispatchChannel ordinary = invocation -> DispatchResult.passthrough();
        assertNull(CoreChainBridge.bind(ordinary, new Object(), ACCESSOR));
        DirectAfterDispatchChannel directOnly = new DirectAfterDispatchChannel() {
            @Override public Object dispatchDirect(BackendInvocation invocation) { return NOT_ENTERED; }
            @Override public DispatchResult dispatch(BackendInvocation invocation) { return DispatchResult.passthrough(); }
        };
        assertNull("a third-party direct-after channel stays on CoreAfterBridge",
                CoreChainBridge.bind(directOnly, new Object(), ACCESSOR));
        assertTrue(TestSupport.capture(() -> CoreChainBridge.bind(ordinary, new Object(), null))
                instanceof NullPointerException);
    }

    @Test public void accessorAndChainReachTheChannelUnchanged() {
        Object frameworkChain = new Object();
        Object[] seen = new Object[2];
        Channel channel = new Channel() {
            @Override public <C> Object dispatchChain(ChainAccessor<C> accessor, C chain) {
                seen[0] = accessor;
                seen[1] = chain;
                return "value";
            }
        };
        assertEquals("value", CoreChainBridge.bind(channel, new Object(), ACCESSOR).apply(frameworkChain));
        assertSame(ACCESSOR, seen[0]);
        assertSame(frameworkChain, seen[1]);
    }

    @Test public void transportMarkersAndApplicationWrappersKeepTheirIdentity() {
        Object backendMarker = new Object();
        Object escaped = DirectAfterDispatchChannel.escape(DirectAfterDispatchChannel.NOT_ENTERED);
        Object[] values = {null, new Object(), backendMarker, DirectAfterDispatchChannel.NOT_ENTERED,
                escaped, DirectAfterDispatchChannel.escape(escaped), DispatchResult.passthrough()};
        for (Object expected : values) {
            Channel channel = new Channel() {
                @Override public <C> Object dispatchChain(ChainAccessor<C> accessor, C chain) {
                    return DirectAfterDispatchChannel.escape(expected);
                }
            };
            Object encoded = CoreChainBridge.bind(channel, backendMarker, ACCESSOR).apply(null);
            Object decoded = encoded instanceof DispatchResult ? ((DispatchResult) encoded).value() : encoded;
            assertSame(expected, decoded);
        }
        Channel closed = new Channel() {
            @Override public <C> Object dispatchChain(ChainAccessor<C> accessor, C chain) { return NOT_ENTERED; }
        };
        assertSame(backendMarker, CoreChainBridge.bind(closed, backendMarker, ACCESSOR).apply(null));
    }

    @Test public void checkedAndFatalFailuresLeaveTheBridgeUnchanged() {
        for (Throwable expected : new Throwable[]{new Exception("checked"), new OutOfMemoryError("fatal")}) {
            Channel channel = new Channel() {
                @Override public <C> Object dispatchChain(ChainAccessor<C> accessor, C chain) throws Throwable {
                    throw expected;
                }
            };
            Function<Object, Object> bound = CoreChainBridge.bind(channel, new Object(), ACCESSOR);
            assertSame(expected, TestSupport.capture(() -> bound.apply(null)));
        }
    }
}
