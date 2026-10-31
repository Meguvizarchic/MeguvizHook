package io.github.jichuo1.meguviz.hook.api.identity;

/**
 * Code epoch of a scope. Negative caches and bound plans may only be reused while the epoch that
 * produced them is still current; an epoch change invalidates unpublished plans.
 */
public final class CodeEpoch {
    private final long value;

    public CodeEpoch(long value) {
        this.value = value;
    }

    public long value() {
        return value;
    }

    public boolean isCurrent(CodeEpoch other) {
        return other != null && value == other.value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CodeEpoch && value == ((CodeEpoch) other).value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "CodeEpoch[" + value + "]";
    }
}
