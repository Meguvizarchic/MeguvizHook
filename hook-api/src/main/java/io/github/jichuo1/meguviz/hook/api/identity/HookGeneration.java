package io.github.jichuo1.meguviz.hook.api.identity;

/**
 * Generation of one hook registration. An in-flight call keeps the immutable plan of the
 * generation it started with; it never reads half of a newer generation.
 */
public final class HookGeneration {
    private final long value;

    public HookGeneration(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("generation must be >= 0");
        }
        this.value = value;
    }

    public long value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HookGeneration && value == ((HookGeneration) other).value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "HookGeneration[" + value + "]";
    }
}
