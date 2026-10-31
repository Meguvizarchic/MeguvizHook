package io.github.jichuo1.meguviz.hook.api;

import java.util.Objects;

/**
 * Owner of a registration. Fusion and reordering are only ever allowed inside one owner and one
 * external registration position; callbacks of other modules are never reordered by this SDK.
 */
public final class HookOwner {
    private final String value;

    private HookOwner(String value) {
        this.value = value;
    }

    public static HookOwner of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        return new HookOwner(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HookOwner && value.equals(((HookOwner) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
