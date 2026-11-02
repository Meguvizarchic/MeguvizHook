package io.github.jichuo1.meguviz.hook.core;

/**
 * Optional backend fast path for an explicit constant replacement. The exact constant reference
 * is stable for the channel's lifetime and may be {@code null}; a backend may bind it once.
 *
 * <p>For every invocation, a backend must call {@link #tryEnter()}. If admission fails, it must
 * proceed exactly once without this hook. Otherwise it may return the bound constant, but must call
 * {@link #exit()} in a {@code finally} block. The lease preserves the same close, replacement and
 * in-flight reporting semantics as {@link #dispatch}. Backends that do not recognize this optional
 * interface continue to use {@code dispatch(null)} because {@link #requiresInvocation()} defaults
 * to false. A channel overriding that method to return true is not eligible for this fast path;
 * the backend must supply an ordinary invocation and use {@link #dispatch} instead.
 */
public interface ConstantDispatchChannel extends DispatchChannel {
    @Override
    default boolean requiresInvocation() {
        return false;
    }

    boolean tryEnter();

    Object constantValue();

    void exit();
}
