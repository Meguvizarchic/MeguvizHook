package io.github.jichuo1.meguviz.hook.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Declarative hook target. A spec is resolved at install time against the actual class loader;
 * it never carries runtime references into another process or another launch.
 */
public abstract class TargetSpec {
    private final MatchPolicy matchPolicy;

    private TargetSpec(MatchPolicy matchPolicy) {
        this.matchPolicy = matchPolicy;
    }

    public final MatchPolicy matchPolicy() {
        return matchPolicy;
    }

    /** Explicit raw-Member escape: the caller may keep the Member, and close cannot revoke it. */
    public static MemberTarget ofMember(Executable executable) {
        return new MemberTarget(executable);
    }

    public static DescriptorTarget.Builder descriptor(TargetKind kind, String className, String memberName) {
        return new DescriptorTarget.Builder(kind, className, memberName);
    }

    /**
     * A caller-resolved {@link Method} or {@link Constructor}. The defining loader stays reachable
     * through this object; closing a registration does not unload it and does not invalidate the
     * reflective object itself.
     */
    public static final class MemberTarget extends TargetSpec {
        private final Executable executable;

        private MemberTarget(Executable executable) {
            super(MatchPolicy.UNIQUE);
            this.executable = Objects.requireNonNull(executable, "executable");
            if (!(executable instanceof Method) && !(executable instanceof Constructor)) {
                throw new IllegalArgumentException("executable must be a Method or Constructor");
            }
        }

        public Executable executable() {
            return executable;
        }

        @Override
        public String toString() {
            return "MemberTarget[" + executable + "]";
        }
    }

    /**
     * Static descriptor resolved by a {@code MemberResolver} at install time.
     *
     * <p>{@code parameterTypeNames == null} means "any overload" and is therefore ambiguous under
     * {@link MatchPolicy#UNIQUE}. {@code returnTypeName == null} skips the static return check;
     * when present it participates in matching and in the cache key.
     */
    public static final class DescriptorTarget extends TargetSpec {
        private final TargetKind kind;
        private final String className;
        private final String memberName;
        private final List<String> parameterTypeNames;
        private final String returnTypeName;
        private final boolean initialize;

        private DescriptorTarget(Builder builder) {
            super(builder.matchPolicy);
            this.kind = builder.kind;
            this.className = builder.className;
            this.memberName = builder.memberName;
            this.parameterTypeNames = builder.parameterTypeNames == null ? null
                    : Collections.unmodifiableList(builder.parameterTypeNames);
            this.returnTypeName = builder.returnTypeName;
            this.initialize = builder.initialize;
        }

        public TargetKind kind() {
            return kind;
        }

        public String className() {
            return className;
        }

        public String memberName() {
            return memberName;
        }

        /** Immutable list, or {@code null} for any overload. */
        public List<String> parameterTypeNames() {
            return parameterTypeNames;
        }

        public String returnTypeName() {
            return returnTypeName;
        }

        /** Whether class initialization is an accepted effect of resolution. */
        public boolean initialize() {
            return initialize;
        }

        @Override
        public String toString() {
            return "DescriptorTarget[" + kind + " " + className + "#" + memberName
                    + (parameterTypeNames == null ? "(any)" : parameterTypeNames.toString())
                    + (returnTypeName == null ? "" : ":" + returnTypeName) + "]";
        }

        public static final class Builder {
            private final TargetKind kind;
            private final String className;
            private final String memberName;
            private List<String> parameterTypeNames;
            private String returnTypeName;
            private boolean initialize;
            private MatchPolicy matchPolicy = MatchPolicy.UNIQUE;

            private Builder(TargetKind kind, String className, String memberName) {
                this.kind = Objects.requireNonNull(kind, "kind");
                this.className = Objects.requireNonNull(className, "className");
                this.memberName = Objects.requireNonNull(memberName, "memberName");
                if (kind == TargetKind.CONSTRUCTOR && !"<init>".equals(memberName)) {
                    throw new IllegalArgumentException("constructor member name must be <init>");
                }
            }

            public Builder parameters(String... typeNames) {
                this.parameterTypeNames = typeNames == null ? null : Arrays.asList(typeNames.clone());
                return this;
            }

            public Builder returning(String typeName) {
                this.returnTypeName = typeName;
                return this;
            }

            public Builder initializeClass(boolean value) {
                this.initialize = value;
                return this;
            }

            public Builder match(MatchPolicy policy) {
                this.matchPolicy = Objects.requireNonNull(policy, "policy");
                return this;
            }

            public DescriptorTarget build() {
                return new DescriptorTarget(this);
            }
        }
    }
}
