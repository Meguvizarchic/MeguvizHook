package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.AroundCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.StringArgumentCondition;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Immutable per-generation execution plan. Built entirely at install time: resolved target, actual
 * loader, code epoch, frozen callback lists and parameter shape. A hot callback only reads these
 * fields; it never re-parses declarations, discovers a manager or reads configuration from disk.
 *
 * <p>Facts that reflection can answer without resolving signature types (shape, receiver kind,
 * parameter count, callback arrays) are copied into final fields at construction. Parameter and
 * return types are resolved lazily on first use, outside any SDK monitor, because resolving them may
 * invoke a custom class loader; the cached check data is immutable and safely published through
 * final fields, so simultaneous first readers may compute equivalent copies independently.
 *
 * <p>A bound plan holds runtime references and must not be persisted or reused across processes or
 * launches. Static descriptors are the persistable form.
 */
public final class BoundPlan {
    private static final BeforeCallback[] NO_BEFORE = new BeforeCallback[0];
    private static final AfterCallback[] NO_AFTER = new AfterCallback[0];

    private final HookSpec spec;
    private final Executable target;
    private final long generation;
    private final ClassLoader loader;
    private final long codeEpoch;
    private final ParameterAccess access;
    private final boolean instanceMethod;
    private final boolean constructor;
    private final int parameterCount;
    private final DispatchResult constantResult;
    private final StringArgumentCondition stringArgumentCondition;
    private final DispatchResult conditionalResult;
    private final BeforeCallback singleBeforeOnly;
    private final AfterCallback singleAfterOnly;
    private final AroundCallback aroundCallback;
    private final BeforeCallback[] befores;
    private final AfterCallback[] afters;
    // Lazily resolved; see the class documentation. Plain fields: the holders are immutable.
    private Signature signature;
    private ResultType resultType;

    BoundPlan(HookSpec spec, Executable target, long generation, ClassLoader loader, long codeEpoch) {
        this.spec = spec;
        this.target = target;
        this.generation = generation;
        this.loader = loader;
        this.codeEpoch = codeEpoch;
        this.access = spec.access();
        this.instanceMethod = target instanceof Method && !Modifier.isStatic(target.getModifiers());
        this.constructor = target instanceof Constructor<?>;
        this.parameterCount = target.getParameterCount();
        this.constantResult = spec.hasConstantReplacement()
                ? DispatchResult.returned(spec.constantResult()) : null;
        this.stringArgumentCondition = spec.stringArgumentCondition();
        this.conditionalResult = stringArgumentCondition == null
                ? null : DispatchResult.returned(stringArgumentCondition.result());
        List<BeforeCallback> before = spec.beforeCallbacks();
        List<AfterCallback> after = spec.afterCallbacks();
        this.singleBeforeOnly = before.size() == 1 && after.isEmpty()
                && access != ParameterAccess.AROUND ? before.get(0) : null;
        this.singleAfterOnly = before.isEmpty() && after.size() == 1
                && access != ParameterAccess.AROUND ? after.get(0) : null;
        this.aroundCallback = spec.aroundCallback();
        // HookSpec freezes both lists at construction; arrays keep the same order without
        // per-call List/Iterator indirection.
        this.befores = before.isEmpty() ? NO_BEFORE : before.toArray(new BeforeCallback[0]);
        this.afters = after.isEmpty() ? NO_AFTER : after.toArray(new AfterCallback[0]);
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
        return access;
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

    AroundCallback aroundCallback() {
        return aroundCallback;
    }

    /** Frozen before callbacks in registration order. Never modified; never exposed. */
    BeforeCallback[] befores() {
        return befores;
    }

    /** Frozen after callbacks in registration order. Never modified; never exposed. */
    AfterCallback[] afters() {
        return afters;
    }

    /** Non-static method: the only target kind whose receiver {@code thisObject()} exposes. */
    boolean isInstanceMethod() {
        return instanceMethod;
    }

    boolean isConstructor() {
        return constructor;
    }

    int parameterCount() {
        return parameterCount;
    }

    Class<?> parameterType(int index) {
        return signature().types[index];
    }

    Class<?> checkedParameterType(int index) {
        // Preserve negative-index short circuit: resolving types may call a custom loader.
        if (index < 0) throw new IndexOutOfBoundsException("argument index " + index + " of " + target);
        Class<?>[] types = signature().types;
        if (index >= types.length) throw new IndexOutOfBoundsException("argument index " + index + " of " + target);
        return types[index];
    }

    /** Boxed class accepted by a slot already bounds-checked; the declared type for references. */
    Class<?> parameterBox(int index) {
        return signature().boxed[index];
    }

    ResultType resultType() {
        ResultType current = resultType;
        if (current == null) {
            // Same loader rule as the signature: no monitor is held while reflection runs.
            current = new ResultType(TypeChecks.returnType(target));
            resultType = current;
        }
        return current;
    }

    private Signature signature() {
        Signature current = signature;
        if (current == null) {
            // Reflection may invoke a custom loader. Do not hold a monitor around that work;
            // simultaneous first readers may compute equivalent private arrays independently.
            current = new Signature(target.getParameterTypes());
            signature = current;
        }
        return current;
    }

    @Override
    public String toString() {
        return "BoundPlan[" + spec.id() + " gen=" + generation + " target=" + target + " epoch=" + codeEpoch + "]";
    }

    /** Resolved parameter types and the boxed class each slot accepts. Immutable. */
    private static final class Signature {
        final Class<?>[] types;
        final Class<?>[] boxed;

        Signature(Class<?>[] types) {
            this.types = types;
            Class<?>[] accepted = new Class<?>[types.length];
            for (int i = 0; i < types.length; i++) accepted[i] = TypeChecks.boxed(types[i]);
            this.boxed = accepted;
        }
    }

    /** Resolved return type check data. Immutable. Void and constructor targets only accept null. */
    static final class ResultType {
        final Class<?> type;
        final Class<?> boxed;
        final boolean isVoid;
        final boolean primitive;

        ResultType(Class<?> type) {
            this.type = type;
            this.isVoid = type == void.class;
            this.primitive = type.isPrimitive();
            this.boxed = TypeChecks.boxed(type);
        }
    }
}
