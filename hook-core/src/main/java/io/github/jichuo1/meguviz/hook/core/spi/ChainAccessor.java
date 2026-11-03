package io.github.jichuo1.meguviz.hook.core.spi;

/**
 * Optional flyweight form of {@link BackendInvocation}: a stateless accessor that a backend binds
 * once per framework hooker and that reads the framework's own per-call chain object {@code C}.
 * With {@code io.github.jichuo1.meguviz.hook.core.CoreChainBridge} the core dispatches with
 * (accessor, chain), so a backend needs no per-call adapter object and {@code READ_ARGS_VIEW} needs
 * no view object. Backends that do not implement it keep {@link BackendInvocation}; the semantics
 * below are those of the matching {@code BackendInvocation} methods.
 *
 * <p>The accessor must not keep per-call state: one instance serves concurrent and reentrant calls,
 * each with its own chain. The core passes a chain only during the dispatch that received it, on the
 * thread that received it, and never retains it afterwards.
 */
public interface ChainAccessor<C> {
    /** The receiver, or null for a static target; see {@link BackendInvocation#thisObject()}. */
    Object thisObject(C chain);

    /**
     * One incoming slot of the read-only view of {@code READ_ARGS_VIEW}, read on demand. It must
     * throw {@link IndexOutOfBoundsException} for an index outside the view, and the slots must not
     * change during the invocation, as for {@link BackendInvocation#readOnlyArguments()}. The core
     * never calls it for other shapes.
     */
    Object argument(C chain, int index);

    /**
     * A new array of the incoming slots, under the contract and timing of
     * {@link BackendInvocation#arguments()}: at most once per invocation, at entry for
     * {@code READ_ARGS} and {@code ENTRY_SNAPSHOT}, on first slot access for {@code MUTABLE_ARGS}
     * and {@code AROUND}.
     */
    Object[] arguments(C chain);

    /**
     * Runs the downstream continuation; null keeps the incoming arguments. Same contract as
     * {@link BackendInvocation#proceed(Object[])}: the throwable propagates unchanged, and the core
     * calls it at most once per invocation.
     */
    Object proceed(C chain, Object[] arguments) throws Throwable;
}
