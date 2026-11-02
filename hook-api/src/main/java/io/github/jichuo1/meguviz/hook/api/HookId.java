package io.github.jichuo1.meguviz.hook.api;

import java.util.Objects;

/**
 * Stable logical identity of a hook registration.
 *
 * <p>The value is chosen by the consumer and must be stable across builds: line numbers, enum
 * ordinals, object identities and {@code ClassLoader.toString()} are not acceptable sources.
 * Within one HookEnvironment, registrations with the same id on the same target are compared by
 * full semantics, never silently overwritten (CONTRACT-001 §5). The API102 adapter does not expose
 * this logical id as a framework hook id; separate environments remain separate registrations.
 */
public final class HookId {
    private static final int MAX_LENGTH = 128;

    private final String value;

    private HookId(String value) {
        this.value = value;
    }

    public static HookId of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("hook id length must be 1.." + MAX_LENGTH);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == ':' || c == '/';
            if (!ok) {
                throw new IllegalArgumentException("hook id contains unsupported character at " + i);
            }
        }
        return new HookId(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HookId && value.equals(((HookId) other).value);
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
