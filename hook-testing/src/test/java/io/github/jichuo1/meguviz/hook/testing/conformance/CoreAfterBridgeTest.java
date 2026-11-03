package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.CoreAfterBridge;
import io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import java.util.function.Function;
import org.junit.Test;
import static org.junit.Assert.*;

/** The bridge is optional and preserves application values that overlap transport sentinels. */
public final class CoreAfterBridgeTest {
    @Test public void ordinaryChannelsStayOnExistingDispatch() {
        DispatchChannel ordinary = invocation -> DispatchResult.passthrough();
        assertNull(CoreAfterBridge.bind(ordinary, new Object()));
    }

    @Test public void transportMarkersAndApplicationWrappersKeepTheirIdentity() {
        Object backendMarker = new Object();
        Object escaped = DirectAfterDispatchChannel.escape(DirectAfterDispatchChannel.NOT_ENTERED);
        Object[] values = {null, new Object(), backendMarker, DirectAfterDispatchChannel.NOT_ENTERED,
                escaped, DirectAfterDispatchChannel.escape(escaped), DispatchResult.passthrough()};
        for (Object expected : values) {
            DirectAfterDispatchChannel channel = new DirectAfterDispatchChannel() {
                @Override public Object dispatchDirect(BackendInvocation invocation) {
                    return DirectAfterDispatchChannel.escape(expected);
                }
                @Override public DispatchResult dispatch(BackendInvocation invocation) {
                    throw new AssertionError("wrong path");
                }
            };
            Object encoded = CoreAfterBridge.bind(channel, backendMarker).apply(null);
            Object decoded = encoded instanceof DispatchResult ? ((DispatchResult) encoded).value() : encoded;
            assertSame(expected, decoded);
        }
        DirectAfterDispatchChannel closed = new DirectAfterDispatchChannel() {
            @Override public Object dispatchDirect(BackendInvocation invocation) { return NOT_ENTERED; }
            @Override public DispatchResult dispatch(BackendInvocation invocation) { return DispatchResult.passthrough(); }
        };
        assertSame(backendMarker, CoreAfterBridge.bind(closed, backendMarker).apply(null));
    }

    @Test public void checkedAndFatalFailuresLeaveTheBridgeUnchanged() {
        for (Throwable expected : new Throwable[]{new Exception("checked"), new OutOfMemoryError("fatal")}) {
            DirectAfterDispatchChannel channel = new DirectAfterDispatchChannel() {
                @Override public Object dispatchDirect(BackendInvocation invocation) throws Throwable { throw expected; }
                @Override public DispatchResult dispatch(BackendInvocation invocation) {
                    throw new AssertionError("wrong path");
                }
            };
            Function<BackendInvocation, Object> bound = CoreAfterBridge.bind(channel, new Object());
            assertSame(expected, TestSupport.capture(() -> bound.apply(null)));
        }
    }
}
