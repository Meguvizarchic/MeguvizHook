package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

/**
 * Optional backend transport that returns the dispatch value itself instead of a
 * {@link DispatchResult}. The core implements it for single-after plans and for the generic plan
 * channel (before callbacks, several callbacks, around); the historical name is kept for
 * compatibility. Backends reach it only through {@link CoreAfterBridge}; every implementation
 * still supports the ordinary {@link #dispatch} path with identical semantics.
 *
 * <p>{@link #dispatchDirect} returns {@link #NOT_ENTERED} where {@code dispatch} would return
 * PASSTHROUGH (the backend must run the original exactly once without SDK callbacks), otherwise
 * the RETURNED value with one level of {@link #escape escaping}, and throws the identical
 * throwable {@code dispatch} would report as THREW. Fatal VM events propagate unchanged.
 */
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
