package io.github.jichuo1.meguviz.hook.api;

/** Around callback; see {@link AroundInvocation} for the continuation contract. */
public interface AroundCallback {
    void around(AroundInvocation invocation) throws Throwable;
}
