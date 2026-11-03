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

    /**
     * Returns the constructor receiver supplied by the backend, or {@code null} for other targets,
     * when unavailable, or for an older implementation that does not provide it. Availability and
     * identity depend on the backend; a non-null value does not establish that construction has
     * completed successfully. It may be uninitialized or only partially initialized, including
     * when downstream execution is skipped or fails. The usual borrowed-invocation thread and
     * callback lifetime rules still apply. Callers control any retention of the returned object.
     *
     * <p>The default supplies a null fallback for an implementation that does not override it.
     * Callers must use an API version that declares this method; as with other default methods,
     * conflicting interface defaults may require an explicit override.
     */
    default Object constructorReceiver() { return null; }

    ParameterAccess access();

    /** Thread that owns this borrowed invocation. */
    Thread ownerThread();

    /**
     * In a before callback, or around before {@code proceed()}, requests a return without running
     * the downstream call. Around may also use this after {@code proceed()} to replace its outcome;
     * the downstream call is never replayed. After callbacks use {@link InvocationResult} instead.
     * The value is checked against the target return type.
     */
    void setEarlyResult(Object value);

    /** Requests an early throw, or explicitly replaces a completed around outcome with a throw. */
    void setEarlyThrowable(Throwable throwable);

    boolean hasEarlyOutcome();
}
