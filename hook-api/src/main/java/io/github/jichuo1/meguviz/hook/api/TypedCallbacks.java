package io.github.jichuo1.meguviz.hook.api;

import java.util.Objects;

/**
 * Adapters behind the typed builder methods. Each adapter is an ordinary callback of the existing
 * type, so the core dispatches it like any other; it only narrows the invocation it receives. Two
 * adapters of the same kind around the identical delegate count as the same callback instance for
 * {@link HookSpec#hasSameSemantics}, so idempotent reinstall keeps working with retained lambdas.
 */
final class TypedCallbacks {
    private TypedCallbacks() {
    }

    static boolean sameDelegate(Object first, Object second) {
        if (first instanceof Adapter && second instanceof Adapter && first.getClass() == second.getClass()) {
            return ((Adapter) first).delegate() == ((Adapter) second).delegate();
        }
        return false;
    }

    private static <T> T narrow(HookInvocation invocation, Class<T> type, String shape) {
        if (!type.isInstance(invocation)) {
            throw new HookContractException("typed callback requires " + shape + " but the invocation is "
                    + (invocation == null ? "null" : invocation.getClass().getName()));
        }
        return type.cast(invocation);
    }

    private interface Adapter {
        Object delegate();
    }

    static final class ReadBefore implements BeforeCallback, Adapter {
        private final ArgsBeforeCallback delegate;

        ReadBefore(ArgsBeforeCallback delegate) {
            this.delegate = Objects.requireNonNull(delegate, "callback");
        }

        @Override
        public void before(HookInvocation invocation) throws Throwable {
            delegate.before(narrow(invocation, ReadArgsInvocation.class, "a readable argument shape"));
        }

        @Override
        public Object delegate() {
            return delegate;
        }
    }

    static final class MutableBefore implements BeforeCallback, Adapter {
        private final MutableArgsBeforeCallback delegate;

        MutableBefore(MutableArgsBeforeCallback delegate) {
            this.delegate = Objects.requireNonNull(delegate, "callback");
        }

        @Override
        public void before(HookInvocation invocation) throws Throwable {
            delegate.before(narrow(invocation, MutableArgsInvocation.class, "MUTABLE_ARGS"));
        }

        @Override
        public Object delegate() {
            return delegate;
        }
    }

    static final class ReadAfter implements AfterCallback, Adapter {
        private final ArgsAfterCallback delegate;

        ReadAfter(ArgsAfterCallback delegate) {
            this.delegate = Objects.requireNonNull(delegate, "callback");
        }

        @Override
        public void after(HookInvocation invocation, InvocationResult result) throws Throwable {
            delegate.after(narrow(invocation, ReadArgsInvocation.class, "a readable argument shape"), result);
        }

        @Override
        public Object delegate() {
            return delegate;
        }
    }
}
