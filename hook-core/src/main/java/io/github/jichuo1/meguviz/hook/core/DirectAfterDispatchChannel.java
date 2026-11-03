package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

/** Backend transport for a single after callback; use one-level escaping for application values. */
public interface DirectAfterDispatchChannel extends DispatchChannel {
    Object NOT_ENTERED = new Object();

    Object dispatchDirect(BackendInvocation invocation) throws Throwable;

    static Object escape(Object value) {
        return value == NOT_ENTERED || value instanceof Escaped ? new Escaped(value) : value;
    }

    static Object unescape(Object value) {
        return value instanceof Escaped ? ((Escaped) value).value : value;
    }

    final class Escaped {
        private final Object value;
        private Escaped(Object value) { this.value = value; }
    }
}
