package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.CoreAfterBridge;
import io.github.jichuo1.meguviz.hook.core.CoreChainBridge;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor;

import java.lang.reflect.Executable;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Function;

/**
 * Backend adapter strategies modelled on the libxposed 101/102 backends, driving a
 * {@link VectorLikeChain}. They use the same hooker logic as the real backends: probe the optional
 * {@link CoreChainBridge} (FLYWEIGHT only) or {@link CoreAfterBridge} binding once for every channel
 * that requires an invocation, and fall back to {@link DispatchChannel#dispatch} plus a kind switch.
 */
public enum ChainAdapter {
    /** Strategy of the backends before hot-path round 1: {@code getArgs().toArray()} and {@code getArgs()}. */
    TO_ARRAY {
        @Override
        ModelHooker hooker(BoundPlan plan, DispatchChannel channel) {
            return new ModelHooker(plan, channel, Transport.DIRECT) {
                @Override
                BackendInvocation invocation(VectorLikeChain chain) {
                    return new ToArrayInvocation(target, chain);
                }
            };
        }
    },
    /** Round-1 strategy: one array filled by {@code getArg(i)} and an index-backed read-only view. */
    GET_ARG {
        @Override
        ModelHooker hooker(BoundPlan plan, DispatchChannel channel) {
            return new ModelHooker(plan, channel, Transport.DIRECT) {
                @Override
                BackendInvocation invocation(VectorLikeChain chain) {
                    return new GetArgInvocation(target, chain, parameterCount);
                }
            };
        }
    },
    /** GET_ARG without the optional bridge: always {@code dispatch()} plus a kind switch (isolates the transport). */
    GET_ARG_DISPATCH {
        @Override
        ModelHooker hooker(BoundPlan plan, DispatchChannel channel) {
            return new ModelHooker(plan, channel, Transport.DISPATCH) {
                @Override
                BackendInvocation invocation(VectorLikeChain chain) {
                    return new GetArgInvocation(target, chain, parameterCount);
                }
            };
        }
    },
    /**
     * Round-2 strategy: a stateless accessor bound once per hooker and the flyweight chain transport,
     * so no per-call adapter or view object. Channels without it keep the GET_ARG adapter.
     */
    FLYWEIGHT {
        @Override
        ModelHooker hooker(BoundPlan plan, DispatchChannel channel) {
            return new ModelHooker(plan, channel, Transport.CHAIN) {
                @Override
                BackendInvocation invocation(VectorLikeChain chain) {
                    return new GetArgInvocation(target, chain, parameterCount);
                }
            };
        }
    };

    enum Transport { DISPATCH, DIRECT, CHAIN }

    abstract ModelHooker hooker(BoundPlan plan, DispatchChannel channel);

    /** Mirrors the generic and direct hooker paths of LibXposed101Backend / LibXposed102Backend. */
    abstract static class ModelHooker implements VectorLikeChain.Level {
        final Executable target;
        final int parameterCount;
        private final DispatchChannel channel;
        private final Object notEntered = new Object();
        private final Function<Object, Object> flyweight;
        private final Function<BackendInvocation, Object> direct;

        ModelHooker(BoundPlan plan, DispatchChannel channel, Transport transport) {
            this.target = plan.target();
            this.parameterCount = target.getParameterCount();
            this.channel = channel;
            boolean invocation = channel.requiresInvocation();
            this.flyweight = transport == Transport.CHAIN && invocation
                    ? CoreChainBridge.bind(channel, notEntered, new VectorChainAccessor(parameterCount)) : null;
            this.direct = flyweight == null && transport != Transport.DISPATCH && invocation
                    ? CoreAfterBridge.bind(channel, notEntered) : null;
        }

        abstract BackendInvocation invocation(VectorLikeChain chain);

        boolean usesDirectTransport() {
            return direct != null;
        }

        boolean usesChainTransport() {
            return flyweight != null;
        }

        @Override
        public final Object intercept(VectorLikeChain chain) throws Throwable {
            if (flyweight != null) {
                Object value = flyweight.apply(chain);
                if (value == notEntered) return chain.proceed();
                return value instanceof DispatchResult ? ((DispatchResult) value).value() : value;
            }
            if (direct != null) {
                Object value = direct.apply(invocation(chain));
                if (value == notEntered) return chain.proceed();
                return value instanceof DispatchResult ? ((DispatchResult) value).value() : value;
            }
            DispatchResult result = channel.dispatch(invocation(chain));
            switch (result.kind()) {
                case RETURNED:
                    return result.value();
                case THREW:
                    throw result.throwable();
                case PASSTHROUGH:
                default:
                    return chain.proceed();
            }
        }
    }

    /** Mirrors the libxposed 101/102 chain accessors: bounded single-slot reads, getArg fill. */
    static final class VectorChainAccessor implements ChainAccessor<VectorLikeChain> {
        private final int count;

        VectorChainAccessor(int count) {
            this.count = count;
        }

        @Override public Object thisObject(VectorLikeChain chain) { return chain.getThisObject(); }

        @Override
        public Object argument(VectorLikeChain chain, int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException("argument index " + index);
            return chain.getArg(index);
        }

        @Override
        public Object[] arguments(VectorLikeChain chain) {
            Object[] values = new Object[count];
            try {
                for (int i = 0; i < count; i++) values[i] = chain.getArg(i);
            } catch (IndexOutOfBoundsException mismatch) {
                return chain.getArgs().toArray();
            }
            return values;
        }

        @Override
        public Object proceed(VectorLikeChain chain, Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }

    static final class ToArrayInvocation implements BackendInvocation {
        private final Executable target;
        private final VectorLikeChain chain;

        ToArrayInvocation(Executable target, VectorLikeChain chain) {
            this.target = target;
            this.chain = chain;
        }

        @Override public Executable target() { return target; }
        @Override public Object thisObject() { return chain.getThisObject(); }
        @Override public Object[] arguments() { return chain.getArgs().toArray(); }
        @Override public List<?> readOnlyArguments() { return chain.getArgs(); }
        @Override public Object proceed(Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }

    static final class GetArgInvocation implements BackendInvocation {
        private final Executable target;
        private final VectorLikeChain chain;
        private final int count;

        GetArgInvocation(Executable target, VectorLikeChain chain, int count) {
            this.target = target;
            this.chain = chain;
            this.count = count;
        }

        @Override public Executable target() { return target; }
        @Override public Object thisObject() { return chain.getThisObject(); }

        @Override
        public Object[] arguments() {
            Object[] values = new Object[count];
            try {
                for (int i = 0; i < count; i++) values[i] = chain.getArg(i);
            } catch (IndexOutOfBoundsException mismatch) {
                return chain.getArgs().toArray();
            }
            return values;
        }

        @Override public List<?> readOnlyArguments() { return new ArgumentView(chain, count); }

        @Override public Object proceed(Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }

    static final class ArgumentView extends AbstractList<Object> implements RandomAccess {
        private final VectorLikeChain chain;
        private final int count;

        ArgumentView(VectorLikeChain chain, int count) {
            this.chain = chain;
            this.count = count;
        }

        @Override
        public Object get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException("argument index " + index);
            return chain.getArg(index);
        }

        @Override
        public int size() {
            return count;
        }
    }
}
