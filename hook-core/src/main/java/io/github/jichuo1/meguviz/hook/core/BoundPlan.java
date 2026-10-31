package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;

import java.lang.reflect.Executable;
import java.util.List;

/**
 * Immutable per-generation execution plan. Built entirely at install time: resolved target, actual
 * loader, code epoch, frozen callback lists and parameter shape. A hot callback only reads these
 * fields; it never re-parses declarations, discovers a manager or reads configuration from disk.
 *
 * <p>A bound plan holds runtime references and must not be persisted or reused across processes or
 * launches. Static descriptors are the persistable form.
 */
public final class BoundPlan {
    private final HookSpec spec;
    private final Executable target;
    private final long generation;
    private final ClassLoader loader;
    private final long codeEpoch;

    BoundPlan(HookSpec spec, Executable target, long generation, ClassLoader loader, long codeEpoch) {
        this.spec = spec;
        this.target = target;
        this.generation = generation;
        this.loader = loader;
        this.codeEpoch = codeEpoch;
    }

    public HookSpec spec() {
        return spec;
    }

    public HookId id() {
        return spec.id();
    }

    public Executable target() {
        return target;
    }

    public long generation() {
        return generation;
    }

    public ClassLoader loader() {
        return loader;
    }

    public long codeEpoch() {
        return codeEpoch;
    }

    public ParameterAccess access() {
        return spec.access();
    }

    public List<BeforeCallback> beforeCallbacks() {
        return spec.beforeCallbacks();
    }

    public List<AfterCallback> afterCallbacks() {
        return spec.afterCallbacks();
    }

    @Override
    public String toString() {
        return "BoundPlan[" + spec.id() + " gen=" + generation + " target=" + target + " epoch=" + codeEpoch + "]";
    }
}
