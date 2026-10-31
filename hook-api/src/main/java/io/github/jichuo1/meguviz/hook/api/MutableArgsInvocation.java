package io.github.jichuo1.meguviz.hook.api;

/**
 * Writable argument slots. Writes are type-checked against the declared parameter types and become
 * visible to the downstream continuation; an arbitrary external array cannot bypass validation.
 */
public interface MutableArgsInvocation extends ReadArgsInvocation {
    void setArgument(int index, Object value);
}
