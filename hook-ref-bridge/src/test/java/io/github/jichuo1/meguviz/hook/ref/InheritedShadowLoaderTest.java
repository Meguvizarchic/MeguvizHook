package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.evidence.CodeIdentity;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.CandidateBatch;
import io.github.jichuo1.meguviz.ref.spi.CandidateProvider;
import io.github.jichuo1.meguviz.ref.spi.CandidateRequest;
import io.github.jichuo1.meguviz.ref.spi.ProviderDescriptor;
import io.github.jichuo1.meguviz.ref.spi.ProviderRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** The real superclass identity must win over a child loader's same-named shadow. */
public final class InheritedShadowLoaderTest {
    private static final String PREFIX = "io.github.jichuo1.meguviz.hook.ref.fixture.";
    private static final String BASE = PREFIX + "ShadowBase";
    private static final String PARAM = PREFIX + "ShadowParam";
    private static final String DERIVED = PREFIX + "ShadowDerived";

    private static byte[] fixtureBytes(String binaryName) throws IOException {
        String path = binaryName.replace('.', '/') + ".class";
        try (InputStream input = InheritedShadowLoaderTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing test fixture: " + path);
            return input.readAllBytes();
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        FixtureLoader(ClassLoader parent, Map<String, byte[]> definitions) {
            super(parent); this.definitions = definitions;
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) type = defineClass(name, bytes, 0, bytes.length);
            if (resolve) resolveClass(type);
            return type;
        }
    }

    private static final class Fixture {
        final ClassLoader parent;
        final ClassLoader child;
        final Class<?> parentParam;
        final Class<?> childParam;
        final Method parentMethod;
        final Method childMethod;
        final Object receiver;
        final Object argument;

        Fixture() throws Exception {
            Map<String, byte[]> parentDefinitions = new HashMap<String, byte[]>();
            parentDefinitions.put(BASE, fixtureBytes(BASE));
            parentDefinitions.put(PARAM, fixtureBytes(PARAM));
            parentDefinitions.put(DERIVED, fixtureBytes(DERIVED));
            parent = new FixtureLoader(InheritedShadowLoaderTest.class.getClassLoader(), parentDefinitions);
            Map<String, byte[]> childDefinitions = new HashMap<String, byte[]>();
            childDefinitions.put(BASE, parentDefinitions.get(BASE));
            childDefinitions.put(PARAM, parentDefinitions.get(PARAM));
            child = new FixtureLoader(parent, childDefinitions);

            Class<?> parentBase = Class.forName(BASE, false, parent);
            parentParam = Class.forName(PARAM, false, parent);
            Class<?> derived = Class.forName(DERIVED, false, child);
            Class<?> childBase = Class.forName(BASE, false, child);
            childParam = Class.forName(PARAM, false, child);
            assertSame(parent, derived.getClassLoader());
            assertSame(parentBase, derived.getSuperclass());
            assertNotSame(parentBase, childBase);
            assertNotSame(parentParam, childParam);
            assertEquals(parentBase.getName(), childBase.getName());
            assertEquals(parentParam.getName(), childParam.getName());
            parentMethod = parentBase.getDeclaredMethod("inherited", parentParam);
            childMethod = childBase.getDeclaredMethod("inherited", childParam);
            receiver = derived.getDeclaredConstructor().newInstance();
            argument = parentParam.getDeclaredConstructor().newInstance();
        }
    }

    private static TargetSpec.DescriptorTarget target() {
        return TargetSpec.descriptor(TargetKind.METHOD, DERIVED, "inherited")
                .parameters(PARAM).returning(PARAM).match(MatchPolicy.UNIQUE).build();
    }

    private static MeguvizRefMemberResolver providerResolver(Fixture fixture, AtomicInteger discoveries) {
        return MeguvizRefMemberResolver.withQueries(loader -> {
            CandidateProvider provider = new CandidateProvider() {
                @Override public ProviderDescriptor descriptor() {
                    return new ProviderDescriptor("shadow", "1", 1, 0, "", Collections.emptyList());
                }
                @Override public CandidateBatch discover(CandidateRequest request) {
                    discoveries.incrementAndGet();
                    return new CandidateBatch(Collections.singletonList(MemberDescriptor.from(fixture.parentMethod)),
                            request.codeFingerprint(), "parent-declared-method", true);
                }
                @Override public void close() { }
            };
            CodeScope scope = CodeScope.open(loader);
            scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                    CodeIdentity.strong(Collections.singletonMap("fixture", "a".repeat(64)), "loader", "v1"));
            return scope;
        }, (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("inherited")
                .parameters(fixture.parentParam).returns(fixture.parentParam).hierarchy(Hierarchy.SUPERCLASSES)
                .provider("shadow").selection(Selection.ALL).resultLimit(16).build());
    }

    private static void verifyParent(List<Executable> members, Fixture fixture) {
        assertEquals(1, members.size());
        assertEquals(fixture.parentMethod, members.get(0));
        assertNotEquals(fixture.childMethod, members.get(0));
    }

    private static void install(Fixture fixture, ClassLoader loader, MeguvizRefMemberResolver resolver,
                                String id, boolean allowIncomplete) throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(loader);
        backend.onTarget(fixture.parentMethod, (receiver, args) -> args[0]);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = HookSpec.builder(id, "shadow-loader-test", target())
                .before(invocation -> callbacks.incrementAndGet()).build();
        try (HookEnvironment env = HookEnvironment.create(backend, resolver, DiagnosticsSink.NONE)) {
            HookEnvironment.InstallResolutionResult outcome = env.installWithResolutionDiagnostics(spec);
            RegistrationReceipt receipt = outcome.receipt();
            if (receipt.isInstalled()) {
                assertEquals(1, receipt.targets().size());
                assertEquals(fixture.parentMethod, receipt.targets().get(0).target());
                assertTrue(backend.isHooked(fixture.parentMethod));
                assertSame(fixture.argument, backend.call(fixture.parentMethod, fixture.receiver, fixture.argument));
                assertEquals(1, callbacks.get());
                receipt.registration().close();
                assertFalse(backend.isHooked(fixture.parentMethod));
            } else {
                assertTrue("only the child-provider case may fail closed", allowIncomplete);
                assertEquals(RegistrationReceipt.Status.FAILED, receipt.status());
                assertTrue(outcome.resolutionFailure() instanceof MeguvizRefResolutionException);
                MeguvizRefResolutionException failure = (MeguvizRefResolutionException) outcome.resolutionFailure();
                assertFalse(failure.receipt().coverage().complete());
                assertEquals(0, backend.installCount());
                assertFalse(backend.isHooked(fixture.parentMethod));
            }
            assertFalse("same-named child declaration must never be hooked", backend.isHooked(fixture.childMethod));
        }
    }

    @Test public void inheritedStructureUsesTheParentDeclaringClass() throws Throwable {
        Fixture fixture = new Fixture();
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withHierarchy(Hierarchy.SUPERCLASSES);
        verifyParent(resolver.resolve(target(), fixture.child), fixture);
        install(fixture, fixture.child, resolver, "shadow.structure", false);
    }

    @Test public void inheritedProviderEitherSelectsTheParentOrFailsClosed() throws Throwable {
        Fixture fixture = new Fixture();
        AtomicInteger discoveries = new AtomicInteger();
        MeguvizRefMemberResolver resolver = providerResolver(fixture, discoveries);
        verifyParent(resolver.resolve(target(), fixture.parent), fixture);
        install(fixture, fixture.parent, resolver, "shadow.provider.parent", false);
        install(fixture, fixture.child, resolver, "shadow.provider.child", true);
        assertEquals(3, discoveries.get());
    }

    @Test public void sameTypeNameDoesNotImplyTheSameRuntimeClass() throws Throwable {
        Fixture fixture = new Fixture();
        // A portable string descriptor cannot encode whether the caller intended P.Param or C.Param.
        assertNotSame(fixture.parentParam, fixture.childParam);
        assertEquals(fixture.parentParam.getName(), fixture.childParam.getName());
        MeguvizRefMemberResolver byName = MeguvizRefMemberResolver.withHierarchy(Hierarchy.SUPERCLASSES);
        TargetSpec.DescriptorTarget childName = TargetSpec.descriptor(TargetKind.METHOD, DERIVED, "inherited")
                .parameters(fixture.childParam.getName()).returning(fixture.childParam.getName())
                .match(MatchPolicy.UNIQUE).build();
        verifyParent(byName.resolve(childName, fixture.child), fixture);

        MeguvizRefMemberResolver byParentIdentity = MeguvizRefMemberResolver.withQueries(
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("inherited")
                        .parameters(fixture.parentParam).returns(fixture.parentParam)
                        .hierarchy(Hierarchy.SUPERCLASSES).selection(Selection.ALL).resultLimit(16).build());
        verifyParent(byParentIdentity.resolve(target(), fixture.child), fixture);
        install(fixture, fixture.child, byParentIdentity, "shadow.typed.parent", false);

        MeguvizRefMemberResolver byChildIdentity = MeguvizRefMemberResolver.withQueries(
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("inherited")
                        .parameters(fixture.childParam).returns(fixture.childParam)
                        .hierarchy(Hierarchy.SUPERCLASSES).selection(Selection.ALL).resultLimit(16).build());
        assertTrue(byChildIdentity.resolve(target(), fixture.child).isEmpty());
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(fixture.child);
        HookSpec spec = HookSpec.builder("shadow.typed.child", "shadow-loader-test", target())
                .before(invocation -> { }).build();
        try (HookEnvironment env = HookEnvironment.create(backend, byChildIdentity, DiagnosticsSink.NONE)) {
            assertEquals(RegistrationReceipt.Status.TARGET_MISSING, env.install(spec).status());
            assertEquals(0, backend.installCount());
            assertFalse(backend.isHooked(fixture.parentMethod));
            assertFalse(backend.isHooked(fixture.childMethod));
        }
    }
}
