package io.github.jichuo1.meguviz.hook.api;

/**
 * Before callback that may rewrite argument slots without a cast. Registering it through
 * {@link HookSpec.Builder#beforeMutableArgs} selects {@link ParameterAccess#MUTABLE_ARGS} unless
 * the builder's access was chosen explicitly, in which case it must be MUTABLE_ARGS.
 */
@FunctionalInterface
public interface MutableArgsBeforeCallback {
    void before(MutableArgsInvocation invocation) throws Throwable;
}
