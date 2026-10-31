package io.github.jichuo1.meguviz.hook.api;

/** Borrowed read access to the argument slots visible at the current stage. */
public interface ReadArgsInvocation extends HookInvocation {
    int argumentCount();

    Object getArgument(int index);

    Class<?> argumentType(int index);
}
