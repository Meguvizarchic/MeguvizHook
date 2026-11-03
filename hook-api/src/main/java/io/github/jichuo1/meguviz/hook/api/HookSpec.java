package io.github.jichuo1.meguviz.hook.api;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable install request. Everything the hot path needs is declared here and frozen into the
 * bound plan at install time: callbacks, parameter shape, error policy, required capabilities,
 * process pruning and external priority.
 *
 * <p>Default priority is 50, matching the libxposed default; the value only positions this
 * registration inside the framework's own chain and never reorders other modules.
 */
public final class HookSpec {
    public static final int DEFAULT_PRIORITY = 50;

    /** Create a normal builder using validated logical identity and an explicit target. */
    public static Builder builder(String id, String owner, TargetSpec target) {
        return new Builder(HookId.of(id), HookOwner.of(owner), target);
    }

    /** Create a normal builder for an already resolved method or constructor. */
    public static Builder forMember(String id, String owner, Executable target) {
        return new Builder(HookId.of(id), HookOwner.of(owner), TargetSpec.ofMember(target));
    }

    private final HookId id;
    private final HookOwner owner;
    private final TargetSpec target;
    private final ParameterAccess access;
    private final List<BeforeCallback> beforeCallbacks;
    private final List<AfterCallback> afterCallbacks;
    private final AroundCallback aroundCallback;
    private final boolean constantReplacement;
    private final Object constantResult;
    private final boolean successReplacement;
    private final Object successResult;
    private final StringArgumentCondition stringArgumentCondition;
    private final ErrorPolicy errorPolicy;
    private final Set<Capability> requiredCapabilities;
    private final ProcessFilter processFilter;
    private final int priority;

    private HookSpec(Builder builder) {
        this.id = builder.id;
        this.owner = builder.owner;
        this.target = builder.target;
        this.access = builder.effectiveAccess();
        this.beforeCallbacks = Collections.unmodifiableList(new ArrayList<BeforeCallback>(builder.beforeCallbacks));
        this.afterCallbacks = Collections.unmodifiableList(new ArrayList<AfterCallback>(builder.afterCallbacks));
        this.aroundCallback = builder.aroundCallback;
        this.constantReplacement = builder.constantReplacement;
        this.constantResult = builder.constantResult;
        this.successReplacement = builder.successReplacement;
        this.successResult = builder.successResult;
        this.stringArgumentCondition = builder.stringArgumentCondition;
        this.errorPolicy = builder.errorPolicy;
        this.requiredCapabilities = Collections.unmodifiableSet(new LinkedHashSet<Capability>(builder.requiredCapabilities));
        this.processFilter = builder.processFilter;
        this.priority = builder.priority;

        if (stringArgumentCondition != null) {
            if (constantReplacement || successReplacement || access != ParameterAccess.READ_ARGS_VIEW || aroundCallback != null
                    || !beforeCallbacks.isEmpty() || !afterCallbacks.isEmpty()) {
                throw new IllegalArgumentException("string argument replacement requires READ_ARGS_VIEW and no other callbacks or replacement");
            }
        } else if (constantReplacement) {
            if (successReplacement || access != ParameterAccess.NO_ARGS || aroundCallback != null
                    || !beforeCallbacks.isEmpty() || !afterCallbacks.isEmpty()) {
                throw new IllegalArgumentException("constant replacement requires NO_ARGS and no callbacks");
            }
        } else if (successReplacement) {
            if (access != ParameterAccess.NO_ARGS || aroundCallback != null
                    || !beforeCallbacks.isEmpty() || !afterCallbacks.isEmpty()) {
                throw new IllegalArgumentException("success replacement requires NO_ARGS and no callbacks");
            }
        } else if (access == ParameterAccess.AROUND) {
            if (aroundCallback == null) {
                throw new IllegalArgumentException("AROUND requires an around callback");
            }
            if (!beforeCallbacks.isEmpty() || !afterCallbacks.isEmpty()) {
                throw new IllegalArgumentException("AROUND is exclusive with before/after in this version");
            }
        } else {
            if (aroundCallback != null) {
                throw new IllegalArgumentException("around callback requires ParameterAccess.AROUND");
            }
            if (beforeCallbacks.isEmpty() && afterCallbacks.isEmpty()) {
                throw new IllegalArgumentException("at least one before or after callback is required");
            }
        }
    }

    public HookId id() {
        return id;
    }

    public HookOwner owner() {
        return owner;
    }

    public TargetSpec target() {
        return target;
    }

    public ParameterAccess access() {
        return access;
    }

    public List<BeforeCallback> beforeCallbacks() {
        return beforeCallbacks;
    }

    public List<AfterCallback> afterCallbacks() {
        return afterCallbacks;
    }

    public AroundCallback aroundCallback() {
        return aroundCallback;
    }

    /** Whether this registration returns a fixed result without invoking user callbacks. */
    public boolean hasConstantReplacement() {
        return constantReplacement;
    }

    /** The exact result reference, including null, declared by {@link Builder#replaceConstant}. */
    public Object constantResult() {
        if (!constantReplacement) throw new IllegalStateException("not a constant replacement");
        return constantResult;
    }

    /** True when a fixed result replaces only a successful downstream outcome. */
    public boolean hasSuccessReplacement() { return successReplacement; }

    /** The exact supplied result reference, including null. */
    public Object successResult() {
        if (!successReplacement) throw new IllegalStateException("not a success replacement");
        return successResult;
    }

    /** The explicit string-argument conditional replacement, or null for an ordinary plan. */
    public StringArgumentCondition stringArgumentCondition() {
        return stringArgumentCondition;
    }

    public ErrorPolicy errorPolicy() {
        return errorPolicy;
    }

    public Set<Capability> requiredCapabilities() {
        return requiredCapabilities;
    }

    public ProcessFilter processFilter() {
        return processFilter;
    }

    public int priority() {
        return priority;
    }

    /**
     * Full-semantics comparison used for idempotent reinstall. Callback and observer identity is
     * part of the semantics. Re-evaluating a lambda does not guarantee the same object; retain and
     * reuse the same instances when relying on idempotent installation.
     */
    public boolean hasSameSemantics(HookSpec other) {
        return other != null
                && id.equals(other.id)
                && owner.equals(other.owner)
                && access == other.access
                && errorPolicy == other.errorPolicy
                && priority == other.priority
                && processFilter.equals(other.processFilter)
                && requiredCapabilities.equals(other.requiredCapabilities)
                && sameCallbackInstances(beforeCallbacks, other.beforeCallbacks)
                && sameCallbackInstances(afterCallbacks, other.afterCallbacks)
                && aroundCallback == other.aroundCallback
                && constantReplacement == other.constantReplacement
                && (!constantReplacement || constantResult == other.constantResult)
                && successReplacement == other.successReplacement
                && (!successReplacement || successResult == other.successResult)
                && sameStringArgumentCondition(stringArgumentCondition, other.stringArgumentCondition)
                && sameTarget(target, other.target);
    }

    private static boolean sameStringArgumentCondition(StringArgumentCondition left, StringArgumentCondition right) {
        if (left == right) return true;
        return left != null && right != null && left.argumentIndex() == right.argumentIndex()
                && Objects.equals(left.expected(), right.expected()) && left.result() == right.result()
                && left.observer() == right.observer();
    }

    private static boolean sameTarget(TargetSpec left, TargetSpec right) {
        if (left instanceof TargetSpec.MemberTarget && right instanceof TargetSpec.MemberTarget) {
            return ((TargetSpec.MemberTarget) left).executable()
                    .equals(((TargetSpec.MemberTarget) right).executable());
        }
        if (!(left instanceof TargetSpec.DescriptorTarget) || !(right instanceof TargetSpec.DescriptorTarget)) {
            return false;
        }
        TargetSpec.DescriptorTarget a = (TargetSpec.DescriptorTarget) left;
        TargetSpec.DescriptorTarget b = (TargetSpec.DescriptorTarget) right;
        return a.kind() == b.kind()
                && a.className().equals(b.className())
                && a.memberName().equals(b.memberName())
                && Objects.equals(a.parameterTypeNames(), b.parameterTypeNames())
                && Objects.equals(a.returnTypeName(), b.returnTypeName())
                && a.initialize() == b.initialize()
                && a.matchPolicy() == b.matchPolicy();
    }

    private static boolean sameCallbackInstances(List<?> first, List<?> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            Object left = first.get(i);
            Object right = second.get(i);
            if (left != right && !TypedCallbacks.sameDelegate(left, right)) {
                return false;
            }
        }
        return true;
    }

    public static final class Builder {
        private final HookId id;
        private final HookOwner owner;
        private final TargetSpec target;
        private ParameterAccess access = ParameterAccess.NO_ARGS;
        // Typed callbacks only choose a shape while no shape-defining call was made.
        private boolean accessExplicit;
        private boolean typedRead;
        private boolean typedMutable;
        private final List<BeforeCallback> beforeCallbacks = new ArrayList<BeforeCallback>();
        private final List<AfterCallback> afterCallbacks = new ArrayList<AfterCallback>();
        private AroundCallback aroundCallback;
        private boolean constantReplacement;
        private Object constantResult;
        private boolean successReplacement;
        private Object successResult;
        private StringArgumentCondition stringArgumentCondition;
        private ErrorPolicy errorPolicy = ErrorPolicy.PROPAGATE;
        private final Set<Capability> requiredCapabilities = new LinkedHashSet<Capability>();
        private ProcessFilter processFilter = ProcessFilter.all();
        private int priority = DEFAULT_PRIORITY;

        public Builder(HookId id, HookOwner owner, TargetSpec target) {
            this.id = Objects.requireNonNull(id, "id");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.target = Objects.requireNonNull(target, "target");
        }

        public Builder access(ParameterAccess value) {
            this.access = Objects.requireNonNull(value, "access");
            this.accessExplicit = true;
            return this;
        }

        public Builder before(BeforeCallback callback) {
            beforeCallbacks.add(Objects.requireNonNull(callback, "callback"));
            return this;
        }

        /**
         * Typed before callback that reads arguments without a cast. Without an explicit
         * {@link #access} choice the spec uses {@link ParameterAccess#READ_ARGS_VIEW} (or
         * MUTABLE_ARGS when a mutable typed callback is also registered). With an explicit choice,
         * that shape must be READ_ARGS, READ_ARGS_VIEW, ENTRY_SNAPSHOT or MUTABLE_ARGS, otherwise
         * {@link #build()} fails. Callback order and lifetime rules are those of {@link #before}.
         */
        public Builder beforeArgs(ArgsBeforeCallback callback) {
            beforeCallbacks.add(new TypedCallbacks.ReadBefore(callback));
            typedRead = true;
            return this;
        }

        /**
         * Typed before callback that may call {@code setArgument} without a cast. Without an
         * explicit {@link #access} choice the spec uses {@link ParameterAccess#MUTABLE_ARGS};
         * with an explicit choice it must be MUTABLE_ARGS, otherwise {@link #build()} fails.
         */
        public Builder beforeMutableArgs(MutableArgsBeforeCallback callback) {
            beforeCallbacks.add(new TypedCallbacks.MutableBefore(callback));
            typedMutable = true;
            return this;
        }

        public Builder after(AfterCallback callback) {
            afterCallbacks.add(Objects.requireNonNull(callback, "callback"));
            return this;
        }

        /**
         * Typed after callback that reads arguments without a cast; shape selection follows
         * {@link #beforeArgs}. Callback order and lifetime rules are those of {@link #after}.
         */
        public Builder afterArgs(ArgsAfterCallback callback) {
            afterCallbacks.add(new TypedCallbacks.ReadAfter(callback));
            typedRead = true;
            return this;
        }

        public Builder around(AroundCallback callback) {
            this.aroundCallback = Objects.requireNonNull(callback, "callback");
            this.access = ParameterAccess.AROUND;
            this.accessExplicit = true;
            return this;
        }

        private ParameterAccess effectiveAccess() {
            if (!accessExplicit) {
                if (typedMutable) return ParameterAccess.MUTABLE_ARGS;
                if (typedRead) return ParameterAccess.READ_ARGS_VIEW;
                return access;
            }
            if (typedMutable && access != ParameterAccess.MUTABLE_ARGS) {
                throw new IllegalArgumentException("beforeMutableArgs requires ParameterAccess.MUTABLE_ARGS, not " + access);
            }
            if (typedRead && access != ParameterAccess.READ_ARGS && access != ParameterAccess.READ_ARGS_VIEW
                    && access != ParameterAccess.ENTRY_SNAPSHOT && access != ParameterAccess.MUTABLE_ARGS) {
                throw new IllegalArgumentException("typed argument callbacks require a readable ParameterAccess, not " + access);
            }
            return access;
        }

        /**
         * Return the exact supplied reference without running the downstream call or callbacks.
         * Null is supported for reference and void/constructor targets. For every resolved target,
         * the SDK checks the result type before installing; an incompatible value fails the request.
         * This mode is exclusive with callbacks and any parameter access beyond NO_ARGS.
         */
        public Builder replaceConstant(Object value) {
            this.constantReplacement = true;
            this.constantResult = value;
            return this;
        }

        /**
         * Run the downstream chain once with its original arguments and, only when it succeeds,
         * replace its result with the exact supplied reference. Downstream exceptions retain their
         * identity; the SDK does not replay the call. A null result is valid for reference and
         * void/constructor targets. Every resolved target is checked before any installation.
         * This mode requires NO_ARGS and is exclusive with callbacks and other replacements.
         * The selected framework's own exception policy may have additional recovery behavior.
         */
        public Builder replaceResultOnSuccess(Object value) {
            this.successReplacement = true;
            this.successResult = value;
            return this;
        }

        /**
         * For a String argument equal to expected, notify the observer, then return the exact result
         * reference without proceeding. Otherwise proceed once with the original arguments. A
         * missing argument is treated as null. Only this explicitly declared shape is optimized;
         * it is exclusive with other callbacks and replacements. Every target's argument and return
         * types are checked before installation. The observer may be null and may throw; it cannot
         * read or modify a borrowed invocation or the selected result. This method selects the
         * READ_ARGS_VIEW shape; a later incompatible access selection is rejected at build time.
         */
        public Builder replaceIfStringArgumentEquals(int index, String expected, Object result,
                                                     MatchObserver observer) {
            this.stringArgumentCondition = new StringArgumentCondition(index, expected, result, observer);
            this.access = ParameterAccess.READ_ARGS_VIEW;
            this.accessExplicit = true;
            return this;
        }

        public Builder replaceIfStringArgumentEquals(int index, String expected, Object result) {
            return replaceIfStringArgumentEquals(index, expected, result, null);
        }

        public Builder errorPolicy(ErrorPolicy value) {
            this.errorPolicy = Objects.requireNonNull(value, "errorPolicy");
            return this;
        }

        public Builder require(Capability capability) {
            requiredCapabilities.add(Objects.requireNonNull(capability, "capability"));
            return this;
        }

        public Builder processes(ProcessFilter value) {
            this.processFilter = Objects.requireNonNull(value, "processFilter");
            return this;
        }

        public Builder priority(int value) {
            this.priority = value;
            return this;
        }

        public HookSpec build() {
            return new HookSpec(this);
        }
    }
}
