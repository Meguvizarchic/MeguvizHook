package io.github.jichuo1.meguviz.hook.api;

/**
 * Result slot visible to after callbacks. Exactly one of returned/thrown holds at a time; setting
 * one replaces the other. Replacing a throwable is explicit, never an automatic "recovery" that
 * would re-run the original method.
 */
public interface InvocationResult {
    boolean hasReturned();

    boolean hasThrown();

    Object getResult();

    Throwable getThrowable();

    void setResult(Object value);

    void setThrowable(Throwable throwable);
}
