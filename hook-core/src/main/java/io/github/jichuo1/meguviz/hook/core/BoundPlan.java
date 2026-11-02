package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.StringArgumentCondition;

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
    private final DispatchResult constantResult;
    private final StringArgumentCondition stringArgumentCondition;
    private final DispatchResult conditionalResult;
    private final BeforeCallback singleBeforeOnly;
    private final AfterCallback singleAfterOnly;
    private volatile Class<?>[] parameterTypes;

    BoundPlan(HookSpec spec, Executable target, long generation, ClassLoader loader, long codeEpoch) {
        this.spec = spec;
        this.target = target;
        this.generation = generation;
        this.loader = loader;
        this.codeEpoch = codeEpoch;
        this.constantResult = spec.hasConstantReplacement()
                ? DispatchResult.returned(spec.constantResult()) : null;
        this.stringArgumentCondition = spec.stringArgumentCondition();
        this.conditionalResult = stringArgumentCondition == null
                ? null : DispatchResult.returned(stringArgumentCondition.result());
        List<BeforeCallback> before = spec.beforeCallbacks();
        this.singleBeforeOnly = before.size() == 1 && spec.afterCallbacks().isEmpty()
                && spec.access() != ParameterAccess.AROUND ? before.get(0) : null;
        List<AfterCallback> after = spec.afterCallbacks();
        this.singleAfterOnly = before.isEmpty() && after.size() == 1
                && spec.access() != ParameterAccess.AROUND ? after.get(0) : null;
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

    DispatchResult constantResult() {
        return constantResult;
    }

    StringArgumentCondition stringArgumentCondition() {
        return stringArgumentCondition;
    }

    DispatchResult conditionalResult() {
        return conditionalResult;
    }

    BeforeCallback singleBeforeOnly() {
        return singleBeforeOnly;
    }

    AfterCallback singleAfterOnly() {
        return singleAfterOnly;
    }

    int parameterCount() {
        return parameterTypes().length;
    }

    Class<?> parameterType(int index) {
        return parameterTypes()[index];
    }

    private Class<?>[] parameterTypes() {
        Class<?>[] current = parameterTypes;
        if (current == null) {
            // Reflection may invoke a custom loader. Do not hold a monitor around that work;
            // simultaneous first readers may compute equivalent private arrays independently.
            current = target.getParameterTypes();
            parameterTypes = current;
        }
        return current;
    }

    @Override
    public String toString() {
        return "BoundPlan[" + spec.id() + " gen=" + generation + " target=" + target + " epoch=" + codeEpoch + "]";
    }
}
