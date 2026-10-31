package io.github.jichuo1.meguviz.hook.core;

/**
 * Explicit dispatch outcome. Every ordinary failure is a value, not an exception thrown at the
 * adapter, so backends cannot accidentally swallow or replay it.
 *
 * <p>{@link Kind#PASSTHROUGH} means the registration no longer accepts SDK work (for example it was
 * closed while the framework entry was still installed): the backend must run the original
 * downstream exactly once with the incoming arguments and must not invoke SDK callbacks.
 */
public final class DispatchResult {
    public enum Kind {
        RETURNED,
        THREW,
        PASSTHROUGH
    }

    private static final DispatchResult PASSTHROUGH = new DispatchResult(Kind.PASSTHROUGH, null, null);

    private final Kind kind;
    private final Object value;
    private final Throwable throwable;

    private DispatchResult(Kind kind, Object value, Throwable throwable) {
        this.kind = kind;
        this.value = value;
        this.throwable = throwable;
    }

    public static DispatchResult returned(Object value) {
        return new DispatchResult(Kind.RETURNED, value, null);
    }

    public static DispatchResult threw(Throwable throwable) {
        if (throwable == null) {
            throw new NullPointerException("throwable");
        }
        return new DispatchResult(Kind.THREW, null, throwable);
    }

    public static DispatchResult passthrough() {
        return PASSTHROUGH;
    }

    public Kind kind() {
        return kind;
    }

    public Object value() {
        return value;
    }

    public Throwable throwable() {
        return throwable;
    }

    @Override
    public String toString() {
        return "DispatchResult[" + kind + (kind == Kind.RETURNED ? " " + value : "")
                + (kind == Kind.THREW ? " " + throwable : "") + "]";
    }
}
