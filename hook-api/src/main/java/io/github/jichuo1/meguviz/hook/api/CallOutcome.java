package io.github.jichuo1.meguviz.hook.api;

import java.util.Objects;

/**
 * Result or throwable of a downstream call. The two outcomes are mutually exclusive and
 * "not called yet" / "currently running" are distinguishable from both.
 */
public final class CallOutcome {
    public enum State {
        NOT_CALLED,
        RUNNING,
        RETURNED,
        THREW
    }

    private static final CallOutcome NOT_CALLED = new CallOutcome(State.NOT_CALLED, null, null);
    private static final CallOutcome RUNNING = new CallOutcome(State.RUNNING, null, null);

    private final State state;
    private final Object value;
    private final Throwable throwable;

    private CallOutcome(State state, Object value, Throwable throwable) {
        this.state = state;
        this.value = value;
        this.throwable = throwable;
    }

    public static CallOutcome notCalled() {
        return NOT_CALLED;
    }

    public static CallOutcome running() {
        return RUNNING;
    }

    public static CallOutcome returned(Object value) {
        return new CallOutcome(State.RETURNED, value, null);
    }

    public static CallOutcome threw(Throwable throwable) {
        return new CallOutcome(State.THREW, null, Objects.requireNonNull(throwable, "throwable"));
    }

    public State state() {
        return state;
    }

    public Object value() {
        if (state != State.RETURNED) {
            throw new IllegalStateException("no return value in state " + state);
        }
        return value;
    }

    public Throwable throwable() {
        if (state != State.THREW) {
            throw new IllegalStateException("no throwable in state " + state);
        }
        return throwable;
    }

    @Override
    public String toString() {
        return "CallOutcome[" + state + (state == State.RETURNED ? " " + value : "")
                + (state == State.THREW ? " " + throwable : "") + "]";
    }
}
