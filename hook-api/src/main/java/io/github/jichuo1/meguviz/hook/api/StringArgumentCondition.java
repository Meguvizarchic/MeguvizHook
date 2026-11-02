package io.github.jichuo1.meguviz.hook.api;

/** Immutable install-time declaration of a string-argument conditional constant replacement. */
public final class StringArgumentCondition {
    private final int argumentIndex;
    private final String expected;
    private final Object result;
    private final MatchObserver observer;

    StringArgumentCondition(int argumentIndex, String expected, Object result, MatchObserver observer) {
        if (argumentIndex < 0) throw new IllegalArgumentException("argument index must be non-negative");
        this.argumentIndex = argumentIndex;
        this.expected = expected;
        this.result = result;
        this.observer = observer;
    }

    public int argumentIndex() { return argumentIndex; }
    public String expected() { return expected; }
    public Object result() { return result; }
    public MatchObserver observer() { return observer; }
}
