package io.github.jichuo1.meguviz.hook.api;

import java.lang.reflect.Executable;

/**
 * One invocation of one registration. Instances are borrowed: they are valid only on the thread
 * that is executing the callback and only until that callback returns. Reentrant calls create
 * independent frames; nothing here may be handed to a worker, coroutine or later callback.
 */
public interface HookInvocation {
    HookId id();

    /** Hook generation bound to this invocation; a reinstalled hook uses a new generation. */
    long generation();

    /** Actual resolved target, bound to its defining loader. */
    Executable target();

    /** Receiver, or {@code null} for static targets and constructors. */
    Object thisObject();

    ParameterAccess access();

    /** Thread that owns this borrowed invocation. */
    Thread ownerThread();

    /**
     * Requests an early return without executing the original method. Valid before the original
     * ran; the value is type-checked against the target return type.
     */
    void setEarlyResult(Object value);

    /** Requests an early throw without executing the original method. */
    void setEarlyThrowable(Throwable throwable);

    boolean hasEarlyOutcome();
}
