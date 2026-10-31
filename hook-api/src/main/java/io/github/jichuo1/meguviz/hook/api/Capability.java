package io.github.jichuo1.meguviz.hook.api;

import java.util.Objects;

/**
 * A capability key in a backend namespace. Keys are namespaced strings, not framework API integers:
 * an API level, a manager name or a service protocol number is not capability evidence by itself.
 */
public final class Capability {
    public static final Capability FRAMEWORK_CHAIN = new Capability("chain.framework");
    public static final Capability MODULE_LOCAL_CHAIN = new Capability("chain.module_local");
    public static final Capability CLASSIC_CALLBACKS = new Capability("chain.classic");
    public static final Capability ATOMIC_REPLACE = new Capability("hook.atomic_replace");
    public static final Capability HOT_RELOAD = new Capability("hook.hot_reload");

    private final String key;

    private Capability(String key) {
        this.key = key;
    }

    public static Capability of(String key) {
        Objects.requireNonNull(key, "key");
        if (key.trim().isEmpty()) {
            throw new IllegalArgumentException("capability key must not be blank");
        }
        return new Capability(key);
    }

    public String key() {
        return key;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Capability && key.equals(((Capability) other).key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
