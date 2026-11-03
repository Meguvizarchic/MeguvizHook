package io.github.jichuo1.meguviz.hook.api;

/**
 * After callback that reads argument slots without a cast. Registering it through
 * {@link HookSpec.Builder#afterArgs} selects {@link ParameterAccess#READ_ARGS_VIEW} unless the
 * builder's access was chosen explicitly, in which case that shape must be readable.
 */
@FunctionalInterface
public interface ArgsAfterCallback {
    void after(ReadArgsInvocation invocation, InvocationResult result) throws Throwable;
}
