package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.evidence.CodeIdentity;
import io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.CandidateBatch;
import io.github.jichuo1.meguviz.ref.spi.CandidateProvider;
import io.github.jichuo1.meguviz.ref.spi.CandidateRequest;
import io.github.jichuo1.meguviz.ref.spi.ProviderDescriptor;
import io.github.jichuo1.meguviz.ref.spi.ProviderRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeguvizRefMemberResolverTest {
    public static class Parent {
        public String inherited(String value) { return value; }
    }

    public static class Child extends Parent {
        public Child() { }
        public String choose(String value) { return value; }
        public String choose(Integer value) { return String.valueOf(value); }
        public String array(String[] values) { return values[0]; }
        public String custom(String value) { return value; }
    }

    private static final ClassLoader LOADER = Child.class.getClassLoader();

    private static TargetSpec.DescriptorTarget.Builder method(String name) {
        return TargetSpec.descriptor(TargetKind.METHOD, Child.class.getName(), name);
    }

    @Test public void defaultDescriptorFiltersTypesAndStableOverloads() throws Exception {
        MeguvizRefMemberResolver resolver = new MeguvizRefMemberResolver();
        List<Executable> all = resolver.resolve(method("choose").parameters((String[]) null).build(), LOADER);
        assertEquals(2, all.size());
        assertEquals(Integer.class, all.get(0).getParameterTypes()[0]);
        assertEquals(String.class, all.get(1).getParameterTypes()[0]);
        List<Executable> only = resolver.resolve(method("choose").parameters("java.lang.String")
                .returning("java.lang.String").build(), LOADER);
        assertEquals(1, only.size());
        assertEquals(String.class, only.get(0).getParameterTypes()[0]);
        assertEquals(1, resolver.resolve(method("array").parameters("java.lang.String[]").build(), LOADER).size());
        assertTrue(resolver.resolve(method("choose").parameters("missing.Type").build(), LOADER).isEmpty());
    }

    @Test public void inheritedMethodsAreExplicitAndHookTheDeclaringClass() throws Exception {
        TargetSpec.DescriptorTarget target = method("inherited").parameters("java.lang.String").build();
        assertTrue(new MeguvizRefMemberResolver().resolve(target, LOADER).isEmpty());
        List<Executable> resolved = MeguvizRefMemberResolver.withHierarchy(Hierarchy.SUPERCLASSES).resolve(target, LOADER);
        assertEquals(1, resolved.size());
        assertEquals(Parent.class, resolved.get(0).getDeclaringClass());
        TargetSpec.DescriptorTarget constructor = TargetSpec.descriptor(TargetKind.CONSTRUCTOR,
                Child.class.getName(), "<init>").parameters().returning("void").build();
        assertEquals(1, MeguvizRefMemberResolver.withHierarchy(Hierarchy.SUPERCLASSES).resolve(constructor, LOADER).size());
        assertTrue(new MeguvizRefMemberResolver().resolve(TargetSpec.descriptor(TargetKind.CONSTRUCTOR,
                Child.class.getName(), "<init>").parameters().returning("java.lang.Void").build(), LOADER).isEmpty());
    }

    @Test public void singleCandidateReturnsIndependentMutableResultAndStillFiltersDescriptor() throws Exception {
        MeguvizRefMemberResolver resolver = new MeguvizRefMemberResolver();
        TargetSpec.DescriptorTarget accepted = method("array").parameters("java.lang.String[]").build();
        List<Executable> first = resolver.resolve(accepted, LOADER);
        Executable array = Child.class.getDeclaredMethod("array", String[].class);
        assertEquals(1, first.size());
        assertEquals(array, first.get(0));
        first.clear();
        first.add(array);
        assertEquals(1, resolver.resolve(accepted, LOADER).size());

        List<Executable> filtered = resolver.resolve(method("array").parameters("java.lang.String").build(), LOADER);
        assertTrue(filtered.isEmpty());
        filtered.add(array);
        assertEquals(1, filtered.size());
    }

    @Test public void bridgeFeedsCoreMatchPolicyAndPreservesCallbacks() throws Throwable {
        Method target = Child.class.getDeclaredMethod("choose", String.class);
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(LOADER);
        backend.onTarget(target, (self, args) -> args[0]);
        try (HookEnvironment environment = HookEnvironment.create(backend, new MeguvizRefMemberResolver(), DiagnosticsSink.NONE)) {
            HookSpec ambiguous = new HookSpec.Builder(HookId.of("ref.ambiguous"), HookOwner.of("test"),
                    method("choose").match(MatchPolicy.UNIQUE).build()).before(invocation -> { }).build();
            assertEquals(RegistrationReceipt.Status.AMBIGUOUS_TARGET, environment.install(ambiguous).status());
            assertEquals(0, backend.installCount());
            HookSpec spec = new HookSpec.Builder(HookId.of("ref.choose"), HookOwner.of("test"),
                    method("choose").parameters("java.lang.String").build())
                    .before(invocation -> invocation.setEarlyResult("intercepted")).build();
            assertTrue(environment.install(spec).isInstalled());
            assertEquals("intercepted", backend.call(target, new Child(), "source"));
            assertEquals(0, backend.originalCallCount(target));
        }
    }

    @Test public void explicitStructuralQueryCanSelectAnotherMember() throws Exception {
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withQueries(CodeScope::open,
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("custom")
                        .selection(Selection.ALL).resultLimit(100).build());
        List<Executable> found = resolver.resolve(method("logical-selector").build(), LOADER);
        assertEquals(1, found.size());
        assertEquals("custom", found.get(0).getName());
    }

    @Test public void structuralQueryUsesFreshDefaultScopeWhenFactoryIsOmitted() throws Exception {
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withQueries(
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("custom")
                        .selection(Selection.ALL).resultLimit(100).build());
        List<Executable> first = resolver.resolve(method("logical-selector").build(), LOADER);
        assertEquals(Child.class.getDeclaredMethod("custom", String.class), first.get(0));
        first.clear();
        assertEquals(1, resolver.resolve(method("logical-selector").build(), LOADER).size());
    }

    @Test public void incompleteAndWrongScopeOrQueryFailClosed() throws Exception {
        MeguvizRefMemberResolver incomplete = MeguvizRefMemberResolver.withQueries(CodeScope::open,
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("choose")
                        .selection(Selection.ALL).candidateBudget(1).resultLimit(100).build());
        assertThrows(BackendInstallException.class, () -> incomplete.resolve(method("choose").build(), LOADER));
        MeguvizRefMemberResolver wrongSelection = MeguvizRefMemberResolver.withQueries(CodeScope::open,
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("choose")
                        .selection(Selection.FIRST).build());
        assertThrows(BackendInstallException.class, () -> wrongSelection.resolve(method("choose").build(), LOADER));
        MeguvizRefMemberResolver wrongLoader = MeguvizRefMemberResolver.withQueries(
                ignored -> CodeScope.open(new ClassLoader(LOADER) { }),
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("choose")
                        .selection(Selection.ALL).build());
        assertThrows(BackendInstallException.class, () -> wrongLoader.resolve(method("choose").build(), LOADER));
    }

    @Test public void fatalProviderCleanupEscapesFailedEnvironmentInstall() throws Exception {
        VirtualMachineError fatal = new VirtualMachineError("synthetic provider cleanup fatal") { };
        AtomicInteger closes = new AtomicInteger();
        CandidateProvider provider = failingProvider(null, fatal, closes);
        MeguvizRefMemberResolver resolver = resolverWithProvider(provider, false);
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(LOADER);
        HookSpec spec = HookSpec.builder("ref.cleanup.fatal", "test", method("choose").build())
                .before(invocation -> { }).build();
        try (HookEnvironment environment = HookEnvironment.create(backend, resolver, DiagnosticsSink.NONE)) {
            assertSame(fatal, assertThrows(VirtualMachineError.class, () -> environment.install(spec)));
            assertEquals(0, backend.installCount());
        }
        assertEquals(1, closes.get());
        assertEquals(1, fatal.getSuppressed().length);
        assertTrue(fatal.getSuppressed()[0] instanceof BackendInstallException);
        assertTrue(fatal.getSuppressed()[0].getMessage().contains("resolution incomplete"));
    }

    @Test public void nonfatalCleanupRemainsSuppressedOnResolutionFailure() throws Exception {
        IllegalStateException cleanup = new IllegalStateException("provider cleanup failed");
        AtomicInteger closes = new AtomicInteger();
        MeguvizRefMemberResolver resolver = resolverWithProvider(failingProvider(null, cleanup, closes), false);
        BackendInstallException primary = assertThrows(BackendInstallException.class,
                () -> resolver.resolve(method("choose").build(), LOADER));
        assertTrue(primary.getMessage().contains("resolution incomplete"));
        assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
        assertEquals(1, closes.get());
    }

    @Test public void explicitInstallDiagnosticsRetainsProviderCleanupFailure() throws Exception {
        IllegalStateException cleanup = new IllegalStateException("provider cleanup failed");
        AtomicInteger closes = new AtomicInteger();
        MeguvizRefMemberResolver resolver = resolverWithProvider(failingProvider(null, cleanup, closes), false);
        AtomicReference<BackendInstallException> thrown = new AtomicReference<BackendInstallException>();
        MemberResolver observing = (target, loader) -> {
            try {
                return resolver.resolve(target, loader);
            } catch (BackendInstallException failure) {
                thrown.set(failure);
                throw failure;
            }
        };
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(LOADER);
        HookSpec spec = HookSpec.builder("ref.cleanup.diagnostics", "test", method("choose").build())
                .before(invocation -> { }).build();
        try (HookEnvironment environment = HookEnvironment.create(backend, observing, DiagnosticsSink.NONE)) {
            HookEnvironment.InstallResolutionResult result = environment.installWithResolutionDiagnostics(spec);
            assertEquals(RegistrationReceipt.Status.FAILED, result.receipt().status());
            assertNotNull(thrown.get());
            assertSame(thrown.get(), result.resolutionFailure());
            assertArrayEquals(new Throwable[] { cleanup }, result.resolutionFailure().getSuppressed());
            assertEquals(0, backend.installCount());
        }
        assertEquals(1, closes.get());
    }

    @Test public void providerFailureReceiptIsAvailableThroughExplicitInstallDiagnostics() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        IOException providerFailure = new IOException("provider detail intentionally not retained by Ref");
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withQueries(loader -> {
            CandidateProvider provider = new CandidateProvider() {
                @Override public ProviderDescriptor descriptor() {
                    return new ProviderDescriptor("checked", "1", 1, 0, "", Collections.emptyList());
                }
                @Override public CandidateBatch discover(CandidateRequest request) throws IOException {
                    throw providerFailure;
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
            CodeScope scope = CodeScope.open(loader);
            scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                    CodeIdentity.strong(Collections.singletonMap("test", "d".repeat(64)), "loader", "v1"));
            return scope;
        }, (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("choose")
                .provider("checked").selection(Selection.ALL).resultLimit(100).build());
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(LOADER);
        HookSpec spec = HookSpec.builder("ref.provider.checked", "test", method("choose").build())
                .before(invocation -> { }).build();
        try (HookEnvironment environment = HookEnvironment.create(backend, resolver, DiagnosticsSink.NONE)) {
            HookEnvironment.InstallResolutionResult detailed = environment.installWithResolutionDiagnostics(spec);
            assertEquals(RegistrationReceipt.Status.FAILED, detailed.receipt().status());
            assertTrue(detailed.resolutionFailure() instanceof MeguvizRefResolutionException);
            MeguvizRefResolutionException failure = (MeguvizRefResolutionException) detailed.resolutionFailure();
            ResolutionReceipt receipt = failure.receipt();
            assertEquals(ResolutionStatus.PARTIAL, receipt.status());
            assertEquals("provider_failed", receipt.reason());
            assertEquals(IOException.class.getName(), receipt.causeType());
            assertFalse(receipt.coverage().complete());
            assertEquals(0, receipt.coverage().inspected());
            assertNotNull(receipt.coverage().universe());
            assertNull("Ref exposes only the cause type, not the original provider exception", failure.getCause());

            RegistrationReceipt ordinary = environment.install(spec);
            assertEquals(detailed.receipt().status(), ordinary.status());
            assertEquals(detailed.receipt().message(), ordinary.message());
            assertEquals(0, backend.installCount());
        }
        assertEquals(2, closes.get());
    }

    @Test public void budgetFailureReceiptIsPreservedButNotSerialized() throws Exception {
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withQueries(
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("choose")
                        .selection(Selection.ALL).candidateBudget(1).resultLimit(100).build());
        MeguvizRefResolutionException failure = assertThrows(MeguvizRefResolutionException.class,
                () -> resolver.resolve(method("choose").build(), LOADER));
        assertEquals("MeguvizRef resolution incomplete: PARTIAL: candidate_budget", failure.getMessage());
        assertEquals(ResolutionStatus.PARTIAL, failure.receipt().status());
        assertEquals("candidate_budget", failure.receipt().reason());
        assertEquals(1, failure.receipt().coverage().inspected());
        assertFalse(failure.receipt().coverage().complete());
        assertNull(failure.receipt().causeType());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(failure); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            MeguvizRefResolutionException copy = (MeguvizRefResolutionException) input.readObject();
            assertEquals(failure.getMessage(), copy.getMessage());
            assertNull(copy.receipt());
        }
    }

    @Test public void fatalQueryRemainsPrimaryWhenProviderCleanupIsAlsoFatal() throws Exception {
        VirtualMachineError primary = new VirtualMachineError("synthetic provider query fatal") { };
        VirtualMachineError cleanup = new VirtualMachineError("synthetic provider cleanup fatal") { };
        AtomicInteger closes = new AtomicInteger();
        MeguvizRefMemberResolver resolver = resolverWithProvider(failingProvider(primary, cleanup, closes), true);
        assertSame(primary, assertThrows(VirtualMachineError.class,
                () -> resolver.resolve(method("choose").build(), LOADER)));
        assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
        assertEquals(1, closes.get());
    }

    private static CandidateProvider failingProvider(Error queryFailure, Throwable cleanupFailure,
                                                      AtomicInteger closes) {
        return new CandidateProvider() {
            @Override public ProviderDescriptor descriptor() {
                return new ProviderDescriptor("failure", "1", 1, 0, "", Collections.emptyList());
            }
            @Override public CandidateBatch discover(CandidateRequest request) {
                if (queryFailure != null) throw queryFailure;
                throw new AssertionError("the incomplete reflection query does not use this provider");
            }
            @Override public void close() {
                closes.incrementAndGet();
                if (cleanupFailure instanceof Error) throw (Error) cleanupFailure;
                throw (RuntimeException) cleanupFailure;
            }
        };
    }

    private static MeguvizRefMemberResolver resolverWithProvider(CandidateProvider provider, boolean useProvider) {
        return MeguvizRefMemberResolver.withQueries(loader -> {
            CodeScope scope = CodeScope.open(loader);
            scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                    CodeIdentity.strong(Collections.singletonMap("test", "f".repeat(64)), "loader", "v1"));
            return scope;
        }, (target, owner) -> {
            QuerySpec.Builder query = QuerySpec.builder(owner, MemberKind.METHOD).name("choose")
                    .selection(Selection.ALL).candidateBudget(1).resultLimit(100);
            if (useProvider) query.provider("failure");
            return query.build();
        });
    }
}
