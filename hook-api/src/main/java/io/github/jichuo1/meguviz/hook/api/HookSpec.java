package io.github.jichuo1.meguviz.hook.api;

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

    private final HookId id;
    private final HookOwner owner;
    private final TargetSpec target;
    private final ParameterAccess access;
    private final List<BeforeCallback> beforeCallbacks;
    private final List<AfterCallback> afterCallbacks;
    private final AroundCallback aroundCallback;
    private final ErrorPolicy errorPolicy;
    private final Set<Capability> requiredCapabilities;
    private final ProcessFilter processFilter;
    private final int priority;

    private HookSpec(Builder builder) {
        this.id = builder.id;
        this.owner = builder.owner;
        this.target = builder.target;
        this.access = builder.access;
        this.beforeCallbacks = Collections.unmodifiableList(new ArrayList<BeforeCallback>(builder.beforeCallbacks));
        this.afterCallbacks = Collections.unmodifiableList(new ArrayList<AfterCallback>(builder.afterCallbacks));
        this.aroundCallback = builder.aroundCallback;
        this.errorPolicy = builder.errorPolicy;
        this.requiredCapabilities = Collections.unmodifiableSet(new LinkedHashSet<Capability>(builder.requiredCapabilities));
        this.processFilter = builder.processFilter;
        this.priority = builder.priority;

        if (access == ParameterAccess.AROUND) {
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
     * Full-semantics comparison used for idempotent reinstall. Callback identity is part of the
     * semantics: a re-created lambda is a different registration, not an idempotent repeat.
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
                && beforeCallbacks.equals(other.beforeCallbacks)
                && afterCallbacks.equals(other.afterCallbacks)
                && Objects.equals(aroundCallback, other.aroundCallback)
                && target.toString().equals(other.target.toString());
    }

    public static final class Builder {
        private final HookId id;
        private final HookOwner owner;
        private final TargetSpec target;
        private ParameterAccess access = ParameterAccess.NO_ARGS;
        private final List<BeforeCallback> beforeCallbacks = new ArrayList<BeforeCallback>();
        private final List<AfterCallback> afterCallbacks = new ArrayList<AfterCallback>();
        private AroundCallback aroundCallback;
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
            return this;
        }

        public Builder before(BeforeCallback callback) {
            beforeCallbacks.add(Objects.requireNonNull(callback, "callback"));
            return this;
        }

        public Builder after(AfterCallback callback) {
            afterCallbacks.add(Objects.requireNonNull(callback, "callback"));
            return this;
        }

        public Builder around(AroundCallback callback) {
            this.aroundCallback = Objects.requireNonNull(callback, "callback");
            this.access = ParameterAccess.AROUND;
            return this;
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
