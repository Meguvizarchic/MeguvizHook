package io.github.jichuo1.meguviz.hook.api.identity;

import java.util.Objects;

/**
 * Code scope: which package and which actual class loader a plan is bound to. Two loaders with the
 * same {@code toString()} are different scopes; identity comparison uses the loader reference.
 */
public final class CodeScopeIdentity {
    private final String packageName;
    private final ClassLoader loader;

    public CodeScopeIdentity(String packageName, ClassLoader loader) {
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public String packageName() {
        return packageName;
    }

    public ClassLoader loader() {
        return loader;
    }

    public boolean isSameLoader(CodeScopeIdentity other) {
        return other != null && loader == other.loader;
    }

    @Override
    public String toString() {
        return "CodeScopeIdentity[" + packageName + " loader@" + Integer.toHexString(System.identityHashCode(loader)) + "]";
    }
}
