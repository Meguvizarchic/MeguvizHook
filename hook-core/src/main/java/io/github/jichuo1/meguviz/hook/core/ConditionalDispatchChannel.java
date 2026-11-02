package io.github.jichuo1.meguviz.hook.core;

/**
 * Optional backend fast path for an explicit conditional constant plan. A backend must bind the
 * channel, argument index and exact constant result reference as one immutable route. The index and
 * result may be read at binding time, remain constant for the lifetime of the channel, and the
 * constant result may be null. On each invocation it calls tryEnter; if that
 * fails it proceeds once without this hook. If admitted, it reads the selected argument, compares it,
 * invokes the optional observer and returns the constant on match, or proceeds once on miss. It must
 * call exit in a finally block encompassing argument access, observer, and any downstream call.
 * A missing argument is null; only IndexOutOfBoundsException from that argument read is mapped to
 * null. Observer, comparison, downstream and fatal exceptions must retain their identities.
 *
 * <p>requiresInvocation remains true: an unaware backend continues using dispatch with an ordinary
 * BackendInvocation. Thus implementing this interface is optional for existing backends.
 */
public interface ConditionalDispatchChannel extends DispatchChannel {
    int argumentIndex();
    boolean tryEnter();
    boolean matches(Object value);
    void notifyMatch() throws Throwable;
    Object constantValue();
    void exit();
}
