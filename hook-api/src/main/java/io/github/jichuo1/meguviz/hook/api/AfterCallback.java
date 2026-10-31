package io.github.jichuo1.meguviz.hook.api;

/**
 * After callback. Runs after the downstream outcome exists, in reverse registration order, also on
 * early return and on downstream throw. It never causes the original method to run again.
 */
public interface AfterCallback {
    void after(HookInvocation invocation, InvocationResult result) throws Throwable;
}
