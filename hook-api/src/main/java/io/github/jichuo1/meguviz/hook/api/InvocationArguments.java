package io.github.jichuo1.meguviz.hook.api;

import java.util.Objects;

/** Utilities for explicitly copying borrowed invocation argument slots. */
public final class InvocationArguments {
    private InvocationArguments() {
    }

    /**
     * Reads the current live argument slots in index order into a new array. This is a shallow
     * copy: referenced objects are shared. It is not an automatic entry snapshot or an atomic
     * observation of all slots. The invocation's usual thread and callback lifetime rules apply.
     *
     * @param invocation a currently readable borrowed invocation
     * @return an independent array containing the references observed during this call
     */
    public static Object[] copyOf(ReadArgsInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        int count = invocation.argumentCount();
        Object[] copy = new Object[count];
        for (int index = 0; index < count; index++) {
            copy[index] = invocation.getArgument(index);
        }
        return copy;
    }
}
