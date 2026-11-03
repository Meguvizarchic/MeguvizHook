package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor;

/**
 * The ordinary transport seen through the flyweight accessor shape, so one frame implementation
 * serves both. The "chain" is the backend's per-call {@link BackendInvocation}. A frame on this
 * transport obtains {@code readOnlyArguments()} once at entry and reads that list itself, so
 * {@link #argument} is not used by the core; it is still a correct read of one incoming slot.
 *
 * <p>Typed on {@code Object} with explicit casts rather than on {@code BackendInvocation}: that
 * avoids a generic bridge method, i.e. one extra inlining level on every forwarded call.
 */
final class InvocationAccessor implements ChainAccessor<Object> {
    static final InvocationAccessor INSTANCE = new InvocationAccessor();

    private InvocationAccessor() {
    }

    @Override
    public Object thisObject(Object invocation) {
        return ((BackendInvocation) invocation).thisObject();
    }

    @Override
    public Object argument(Object invocation, int index) {
        return ((BackendInvocation) invocation).readOnlyArguments().get(index);
    }

    @Override
    public Object[] arguments(Object invocation) {
        return ((BackendInvocation) invocation).arguments();
    }

    @Override
    public Object proceed(Object invocation, Object[] arguments) throws Throwable {
        return ((BackendInvocation) invocation).proceed(arguments);
    }
}
