package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.descriptor.TypeDescriptor;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.api.result.ResolvedMember;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Optional MeguvizRef 1.0 resolver for {@link io.github.jichuo1.meguviz.hook.core.HookEnvironment}.
 * It runs at installation time and is not on the hook callback path. Each resolution opens and
 * closes a fresh scope so its cache cannot survive an SDK code epoch or retain a previous loader.
 * Raw members deliberately escape the scope into the SDK registration; closing either object
 * cannot revoke a raw reflective object held by a caller.
 *
 * <p>The default query preserves exact descriptor matching and excludes bridge/synthetic methods.
 * {@link #withHierarchy(Hierarchy)} explicitly opts into inherited methods. Advanced integrations
 * may supply a query factory and a fresh scope factory to use MeguvizRef structural queries or
 * registered providers. Query selection must be {@link Selection#ALL}; incomplete, cancelled,
 * stale and budget-limited results fail closed instead of masquerading as a missing/unique match.
 * A provider's completeness remains subject to MeguvizRef's visible-universe contract, not a
 * guarantee of global DEX coverage. The SDK still applies its own FIRST/UNIQUE/ALL match policy.
 */
public final class MeguvizRefMemberResolver implements MemberResolver {
    /** Creates a new scope owned and closed by the bridge for every call. */
    @FunctionalInterface
    public interface ScopeFactory {
        CodeScope open(ClassLoader loader) throws BackendInstallException;
    }

    /** Creates an explicit ALL-selection query for this target and runtime owner. */
    @FunctionalInterface
    public interface QueryFactory {
        QuerySpec create(TargetSpec.DescriptorTarget target, Class<?> runtimeOwner)
                throws BackendInstallException;
    }

    private static final int MAX_RESULTS = 4096;
    private final ScopeFactory scopes;
    private final QueryFactory queries;
    private final boolean exactDescriptor;

    /** Default declared-only descriptor matching. */
    public MeguvizRefMemberResolver() {
        this(Hierarchy.DECLARED);
    }

    private MeguvizRefMemberResolver(Hierarchy hierarchy) {
        this(CodeScope::open, hierarchy);
    }

    private MeguvizRefMemberResolver(ScopeFactory scopes, Hierarchy hierarchy) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.queries = (target, owner) -> defaultQuery(target, owner, hierarchy);
        this.exactDescriptor = true;
    }

    /**
     * Opt into a defined method hierarchy; constructors remain declared-only. An inherited method
     * is hooked on its declaring class and may therefore affect other subclasses as well.
     */
    public static MeguvizRefMemberResolver withHierarchy(Hierarchy hierarchy) {
        return new MeguvizRefMemberResolver(hierarchy);
    }

    /**
     * Use a caller-configured fresh scope for each resolution while retaining the default
     * descriptor filters and the selected method hierarchy. Ownership of each scope transfers
     * to the resolver and it is closed after that resolution. Constructors remain declared-only.
     */
    public static MeguvizRefMemberResolver withScope(ScopeFactory scopes, Hierarchy hierarchy) {
        return new MeguvizRefMemberResolver(scopes, hierarchy);
    }

    /**
     * Use an explicit query with a fresh default scope for each resolution.
     * For provider registration or custom scope configuration, use the two-factory overload.
     */
    public static MeguvizRefMemberResolver withQueries(QueryFactory queries) {
        return new MeguvizRefMemberResolver(CodeScope::open, queries);
    }

    /**
     * Explicit advanced query path. Query constraints supersede descriptor name/parameter filters,
     * but the query must still identify the descriptor's runtime owner and member kind. The scope
     * factory must return a fresh open scope bound to the supplied loader; ownership transfers to
     * this call. Provider registries, if used, must therefore be supplied as fresh snapshots.
     */
    public static MeguvizRefMemberResolver withQueries(ScopeFactory scopes, QueryFactory queries) {
        return new MeguvizRefMemberResolver(scopes, queries);
    }

    private MeguvizRefMemberResolver(ScopeFactory scopes, QueryFactory queries) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.exactDescriptor = false;
    }

    @Override
    public List<Executable> resolve(TargetSpec.DescriptorTarget target, ClassLoader loader)
            throws BackendInstallException {
        return resolveInternal(target, loader, null, null);
    }

    @FunctionalInterface interface Checkpoint {
        void check() throws BackendInstallException;
        default void checkResult(ResolutionResult result) throws BackendInstallException { check(); }
    }

    /** Internal entry for a caller-owned, generation-pinned session. */
    List<Executable> resolveInScope(TargetSpec.DescriptorTarget target, ClassLoader loader, CodeScope shared,
                                    Checkpoint checkpoint)
            throws BackendInstallException {
        return resolveInternal(target, loader, Objects.requireNonNull(shared, "shared"),
                Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    private List<Executable> resolveInternal(TargetSpec.DescriptorTarget target, ClassLoader loader, CodeScope shared,
                                             Checkpoint checkpoint)
            throws BackendInstallException {
        Objects.requireNonNull(target, "target");
        if (target.kind() != TargetKind.METHOD && target.kind() != TargetKind.CONSTRUCTOR) {
            throw new BackendInstallException("MeguvizRef bridge supports methods and constructors only");
        }
        final Class<?> owner;
        try {
            if (checkpoint != null) checkpoint.check();
            owner = Class.forName(target.className(), target.initialize(), loader);
        } catch (ClassNotFoundException missing) {
            return Collections.emptyList();
        } catch (LinkageError | SecurityException failure) {
            throw new BackendInstallException("cannot load target for MeguvizRef", failure);
        }
        if (checkpoint != null) checkpoint.check();
        final QuerySpec query;
        try {
            query = queries.create(target, owner);
        } catch (RuntimeException failure) {
            throw new BackendInstallException("MeguvizRef query creation failed", failure);
        }
        if (query == null) throw new BackendInstallException("MeguvizRef query factory returned null");
        MemberKind expected = target.kind() == TargetKind.METHOD ? MemberKind.METHOD : MemberKind.CONSTRUCTOR;
        if (!owner.getName().equals(query.owner()) || (query.runtimeOwner() != null && query.runtimeOwner() != owner)
                || query.kind() != expected || query.selection() != Selection.ALL
                || query.resultLimit() > MAX_RESULTS) {
            throw new BackendInstallException("MeguvizRef query must target the owner/kind with bounded ALL selection");
        }
        if (checkpoint != null) checkpoint.check();
        try {
            if (shared != null) return collect(shared, loader, owner, target, query, expected, checkpoint);
            CodeScope scope = Objects.requireNonNull(scopes.open(loader), "scope");
            Throwable primary = null;
            try {
                return collect(scope, loader, owner, target, query, expected, null);
            } catch (BackendInstallException | IllegalAccessException | RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } catch (Throwable failure) {
                primary = failure;
                throw rethrow(failure);
            } finally {
                closeOwnedScope(scope, primary);
            }
        } catch (IllegalAccessException | LinkageError failure) {
            throw new BackendInstallException("cannot export MeguvizRef result", failure);
        } catch (RuntimeException failure) {
            throw new BackendInstallException("MeguvizRef query or scope failed", failure);
        }
    }

    // Preserve the ordinary try-with-resources suppression order while ensuring a fatal cleanup
    // event is not hidden behind a nonfatal resolution failure caught by HookEnvironment.
    private static void closeOwnedScope(CodeScope scope, Throwable primary) {
        try {
            scope.close();
        } catch (Throwable cleanup) {
            if (primary == null) throw rethrow(cleanup);
            if (isFatal(cleanup) && !isFatal(primary)) {
                if (cleanup != primary) cleanup.addSuppressed(primary);
                throw (Error) cleanup;
            }
            if (primary != cleanup) primary.addSuppressed(cleanup);
        }
    }

    // Kotlin and other JVM callers can throw checked exceptions not declared by a Java callback.
    // Preserve the original object while still running the same ownership cleanup.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private List<Executable> collect(CodeScope scope, ClassLoader loader, Class<?> owner,
            TargetSpec.DescriptorTarget target, QuerySpec query, MemberKind expected, Checkpoint checkpoint)
            throws BackendInstallException, IllegalAccessException {
        if (!scope.usesLoader(loader)) throw new BackendInstallException("MeguvizRef scope loader mismatch or closed");
        ResolutionResult result = scope.resolve(query);
        if (checkpoint != null) checkpoint.checkResult(result);
        ResolutionStatus status = result.status();
        if (status == ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE && result.receipt().coverage().complete()) {
            return Collections.emptyList();
        }
        if (status != ResolutionStatus.ALL_MATCHES || !result.receipt().coverage().complete()) {
            throw incomplete(result);
        }
        if (result.members().size() > MAX_RESULTS) throw new BackendInstallException("MeguvizRef result limit exceeded");
        if (result.members().size() == 1) {
            List<Executable> matches = new ArrayList<Executable>();
            ResolvedMember token = result.members().get(0);
            if (!mayMatch(token, target)) return matches;
            Executable executable = checkedExecutable(scope, owner, target, expected, token);
            if (executable != null) matches.add(executable);
            return matches;
        }
        LinkedHashSet<Executable> unique = new LinkedHashSet<Executable>();
        for (ResolvedMember token : result.members()) {
            if (!mayMatch(token, target)) continue;
            Executable executable = checkedExecutable(scope, owner, target, expected, token);
            if (executable != null) unique.add(executable);
        }
        return sortedBySignature(new ArrayList<Executable>(unique));
    }

    /**
     * Cheap pre-export filter for the default descriptor path, using the JVM descriptor that Ref
     * already holds for each token. It only rejects candidates whose parameter or return type
     * cannot satisfy {@link #matches}; every accepted candidate is still exported and checked
     * exactly. Skipping definite mismatches avoids exporting unrelated overloads, so under
     * {@code TRY_SUPPRESS_JAVA_CHECKS} an inaccessible unrelated overload no longer fails the
     * resolution. Custom queries keep their own constraints and are never pre-filtered here.
     */
    private boolean mayMatch(ResolvedMember token, TargetSpec.DescriptorTarget target) {
        if (!exactDescriptor) return true;
        MemberDescriptor descriptor = token.descriptor();
        List<String> parameters = target.parameterTypeNames();
        if (parameters != null) {
            List<TypeDescriptor> actual = descriptor.parameters();
            if (actual.size() != parameters.size()) return false;
            for (int i = 0; i < parameters.size(); i++) {
                if (!JvmTypeNames.mayMatch(actual.get(i).value(), parameters.get(i))) return false;
            }
        }
        String returns = target.returnTypeName();
        return returns == null || JvmTypeNames.mayMatch(descriptor.type().value(), returns);
    }

    /** Signature order with one key per candidate instead of two per comparison; order unchanged. */
    private static List<Executable> sortedBySignature(List<Executable> matches) {
        int count = matches.size();
        if (count < 2) return matches;
        String[] keys = new String[count];
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            keys[i] = signature(matches.get(i));
            order[i] = i;
        }
        // Stable object sort, matching the previous List.sort with a key comparator.
        Arrays.sort(order, (left, right) -> keys[left].compareTo(keys[right]));
        List<Executable> sorted = new ArrayList<Executable>(count);
        for (Integer index : order) sorted.add(matches.get(index));
        return sorted;
    }

    static MeguvizRefResolutionException incomplete(ResolutionResult result) {
        return new MeguvizRefResolutionException("MeguvizRef resolution incomplete: "
                + result.status() + ": " + result.receipt().reason(), result.receipt());
    }

    private Executable checkedExecutable(CodeScope scope, Class<?> owner, TargetSpec.DescriptorTarget target,
                                         MemberKind expected, ResolvedMember token)
            throws IllegalAccessException, BackendInstallException {
        Member raw = scope.exportRaw(token);
        if (!(raw instanceof Executable) || (expected == MemberKind.METHOD && !(raw instanceof Method))
                || (expected == MemberKind.CONSTRUCTOR && !(raw instanceof Constructor<?>))) {
            throw new BackendInstallException("MeguvizRef returned an unexpected member kind");
        }
        Executable executable = (Executable) raw;
        if (!executable.getDeclaringClass().isAssignableFrom(owner)
                || (expected == MemberKind.CONSTRUCTOR && executable.getDeclaringClass() != owner)) {
            throw new BackendInstallException("MeguvizRef returned a member outside the requested owner hierarchy");
        }
        return !exactDescriptor || matches(executable, target) ? executable : null;
    }

    private static QuerySpec defaultQuery(TargetSpec.DescriptorTarget target, Class<?> owner, Hierarchy hierarchy) {
        boolean method = target.kind() == TargetKind.METHOD;
        QuerySpec.Builder query = QuerySpec.builder(owner, method ? MemberKind.METHOD : MemberKind.CONSTRUCTOR)
                .name(target.memberName()).selection(Selection.ALL).resultLimit(MAX_RESULTS)
                .hierarchy(method ? hierarchy : Hierarchy.DECLARED);
        if (method) query.bridge(false).synthetic(false);
        if (target.parameterTypeNames() != null) query.parameterCount(target.parameterTypeNames().size());
        return query.build();
    }

    private static boolean matches(Executable member, TargetSpec.DescriptorTarget target) {
        if (!member.getName().equals(target.kind() == TargetKind.CONSTRUCTOR
                ? member.getDeclaringClass().getName() : target.memberName())) return false;
        List<String> expected = target.parameterTypeNames();
        Class<?>[] parameters = member.getParameterTypes();
        if (expected != null) {
            if (expected.size() != parameters.length) return false;
            for (int i = 0; i < parameters.length; i++) {
                if (!typeMatches(parameters[i], expected.get(i))) return false;
            }
        }
        Class<?> returns = member instanceof Method ? ((Method) member).getReturnType() : void.class;
        return typeMatches(returns, target.returnTypeName());
    }

    private static boolean typeMatches(Class<?> type, String expected) {
        return expected == null || type.getName().equals(expected) || expected.equals(type.getCanonicalName());
    }

    private static String signature(Executable executable) {
        StringBuilder key = new StringBuilder(executable.getName()).append('(');
        for (Class<?> parameter : executable.getParameterTypes()) key.append(parameter.getName()).append(';');
        key.append(')');
        if (executable instanceof Method) key.append(((Method) executable).getReturnType().getName());
        return key.append('@').append(executable.getDeclaringClass().getName()).toString();
    }
}
