package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.CoreAfterBridge;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

import java.lang.reflect.Executable;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Function;

/**
 * Backend adapter strategies modelled on the libxposed 101/102 backends, driving a
 * {@link VectorLikeChain}. Both use the same hooker logic as the real backends: probe the optional
 * {@link CoreAfterBridge} binding once for every channel that requires an invocation, and fall back
 * to {@link DispatchChannel#dispatch} plus a kind switch otherwise.
 */
public enum ChainAdapter {
    /** Strategy of the backends before hot-path round 1: {@code getArgs().toArray()} and {@code getArgs()}. */
    TO_ARRAY {
        @Override
        ModelHooker hooker(BoundPlan plan, DispatchChannel channel) {
            return new ModelHooker(plan, channel) {
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
            return new ModelHooker(plan, channel) {
                @Override
                BackendInvocation invocation(VectorLikeChain chain) {
                    return new GetArgInvocation(target, chain, parameterCount);
                }
            };
        }
    };

    abstract ModelHooker hooker(BoundPlan plan, DispatchChannel channel);

    /** Mirrors the generic and direct hooker paths of LibXposed101Backend / LibXposed102Backend. */
    abstract static class ModelHooker {
        final Executable target;
        final int parameterCount;
        private final DispatchChannel channel;
        private final Object notEntered = new Object();
        private final Function<BackendInvocation, Object> direct;

        ModelHooker(BoundPlan plan, DispatchChannel channel) {
            this.target = plan.target();
            this.parameterCount = target.getParameterCount();
            this.channel = channel;
            this.direct = channel.requiresInvocation() ? CoreAfterBridge.bind(channel, notEntered) : null;
        }

        abstract BackendInvocation invocation(VectorLikeChain chain);

        boolean usesDirectTransport() {
            return direct != null;
        }

        final Object intercept(VectorLikeChain chain) throws Throwable {
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
