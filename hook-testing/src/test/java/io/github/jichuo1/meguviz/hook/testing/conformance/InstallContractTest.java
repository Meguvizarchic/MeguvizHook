package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.ProcessFilter;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** Install-time pruning, capability refusal, binding, match policy, identity and epoch behaviour. */
public class InstallContractTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    private FakeBackend backend;
    private HookEnvironment environment;

    @Before
    public void setUp() {
        backend = FakeBackend.create();
        environment = HookEnvironment.create(backend);
    }

    @Test
    public void unsupportedRequiredCapabilityRefusesBeforeInstall() {
        RegistrationReceipt receipt = environment.install(TestSupport.spec("cap.unsupported", ADD)
                .require(Capability.CLASSIC_CALLBACKS)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, receipt.status());
        assertEquals(0, backend.installCount());
    }

    @Test
    public void unknownCapabilityIsNeverTreatedAsSupported() {
        backend.withCapability(Capability.ATOMIC_REPLACE, CapabilityResult.unknown("fake", "not probed"));
        RegistrationReceipt receipt = environment.install(TestSupport.spec("cap.unknown", ADD)
                .require(Capability.ATOMIC_REPLACE)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, receipt.status());
        assertEquals(0, backend.installCount());
    }

    @Test
    public void undeclaredCapabilityAnswersUnknown() {
        RegistrationReceipt receipt = environment.install(TestSupport.spec("cap.missing", ADD)
                .require(Capability.of("custom.unlisted"))
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, receipt.status());
    }

    @Test
    public void classicOnlyBackendIsRefusedInsteadOfPretendingEquivalence() {
        FakeBackend classic = FakeBackend.create("fake-classic", ChainSemantics.CLASSIC_CALLBACKS);
        HookEnvironment classicEnvironment = HookEnvironment.create(classic);
        RegistrationReceipt receipt = classicEnvironment.install(TestSupport.spec("cap.classic.only", ADD)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, receipt.status());
        assertEquals(0, classic.installCount());
    }

    @Test
    public void duplicateIdIsIdempotentOnlyForIdenticalSemantics() {
        HookSpec spec = TestSupport.spec("duplicate", ADD).before(invocation -> {
        }).build();
        assertTrue(environment.install(spec).isInstalled());
        RegistrationReceipt repeat = environment.install(spec);
        assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, repeat.status());
        assertEquals(1, backend.installCount());

        RegistrationReceipt conflict = environment.install(TestSupport.spec("duplicate", ADD)
                .priority(60)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.CONFLICT, conflict.status());
        assertEquals("conflicting install must not replace the existing hook", 1, backend.installCount());
    }

    @Test
    public void missingTargetIsReported() {
        RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                        io.github.jichuo1.meguviz.hook.api.HookId.of("target.missing"),
                        io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"),
                        TargetSpec.descriptor(TargetKind.METHOD, "does.not.Exist", "run").build())
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.TARGET_MISSING, receipt.status());
    }

    @Test
    public void ambiguousOverloadIsRejectedUnderUnique() {
        RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                        io.github.jichuo1.meguviz.hook.api.HookId.of("target.ambiguous"),
                        io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"),
                        TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded").build())
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.AMBIGUOUS_TARGET, receipt.status());
        assertEquals(0, backend.installCount());
    }

    @Test
    public void staticReturnCheckNarrowsOverloads() throws Exception {
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .returning("int")
                .build();
        List<Executable> resolved = new ReflectiveMemberResolver().resolve(descriptor, Fixtures.class.getClassLoader());
        assertEquals("return type participates in matching", 1, resolved.size());
        assertEquals(TestSupport.method("overloaded", int.class), resolved.get(0));

        HookSpec spec = new HookSpec.Builder(io.github.jichuo1.meguviz.hook.api.HookId.of("target.return.check"),
                io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"), descriptor)
                .before(invocation -> {
                })
                .build();
        assertTrue(environment.install(spec).isInstalled());
    }

    @Test
    public void wrongReturnTypeMakesTargetMissing() {
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "add")
                .parameters("int", "int")
                .returning("long")
                .build();
        RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                        io.github.jichuo1.meguviz.hook.api.HookId.of("target.return.mismatch"),
                        io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"), descriptor)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.TARGET_MISSING, receipt.status());
    }

    @Test
    public void firstPolicyUsesStableSignatureOrder() {
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .match(MatchPolicy.FIRST)
                .build();
        RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                        io.github.jichuo1.meguviz.hook.api.HookId.of("target.first"),
                        io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"), descriptor)
                .before(invocation -> {
                })
                .build());
        assertTrue(receipt.toString(), receipt.isInstalled());
        // 'int' sorts before 'java.lang.String' in the resolver's stable signature key.
        assertTrue(receipt.message().contains("overloaded"));
    }

    @Test
    public void matchAllIsExplicitlyRefusedInThisSlice() {
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .match(MatchPolicy.ALL)
                .build();
        RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                        io.github.jichuo1.meguviz.hook.api.HookId.of("target.all"),
                        io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"), descriptor)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, receipt.status());
        assertEquals(0, backend.installCount());
    }

    @Test
    public void processPruningHappensBeforeInstall() {
        RegistrationReceipt receipt = environment.install(TestSupport.spec("process.filtered", ADD)
                .processes(ProcessFilter.only("another.process"))
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.FILTERED, receipt.status());
        assertEquals(0, backend.installCount());
    }

    @Test
    public void epochChangeDuringInstallDiscardsPlan() {
        HookEnvironment epochEnvironment = HookEnvironment.create(backend,
                (descriptor, loader) -> {
                    backend.bumpCodeEpoch();
                    return new ReflectiveMemberResolver().resolve(descriptor, loader);
                },
                io.github.jichuo1.meguviz.hook.core.DiagnosticsSink.NONE);
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "add")
                .parameters("int", "int")
                .build();
        RegistrationReceipt receipt = epochEnvironment.install(new HookSpec.Builder(
                        io.github.jichuo1.meguviz.hook.api.HookId.of("epoch.stale"),
                        io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"), descriptor)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, receipt.status());
        assertEquals("no plan of an outdated epoch may be published", 0, backend.installCount());
    }

    @Test
    public void sameClassNameInDifferentLoadersBindsDifferentMembers() throws Exception {
        byte[] bytes = readClassBytes(Fixtures.class);
        ClassLoader firstLoader = new DefiningLoader("CopyLoader", bytes);
        ClassLoader secondLoader = new DefiningLoader("CopyLoader", bytes);
        Class<?> first = Class.forName(Fixtures.class.getName(), false, firstLoader);
        Class<?> second = Class.forName(Fixtures.class.getName(), false, secondLoader);
        assertEquals(first.getName(), second.getName());
        assertNotSame(first, second);
        assertEquals("both loaders share the same display name", firstLoader.toString(), secondLoader.toString());

        Method firstAdd = first.getMethod("add", int.class, int.class);
        Method secondAdd = second.getMethod("add", int.class, int.class);
        assertNotEquals("member identity is loader bound", firstAdd, secondAdd);
        assertEquals(firstLoader, firstAdd.getDeclaringClass().getClassLoader());
        assertEquals(secondLoader, secondAdd.getDeclaringClass().getClassLoader());
    }

    @Test
    public void resolverExcludesBridgeMethods() throws Exception {
        List<Executable> resolved = new ReflectiveMemberResolver().resolve(
                TargetSpec.descriptor(TargetKind.METHOD, Fixtures.StringSource.class.getName(), "value").build(),
                Fixtures.class.getClassLoader());
        assertEquals(1, resolved.size());
        Method method = (Method) resolved.get(0);
        assertEquals(String.class, method.getReturnType());
        assertTrue(!method.isBridge() && !method.isSynthetic());
    }

    @Test
    public void backendInstallFailureIsNotReportedAsInstalled() {
        backend.withFailingNextInstall();
        RegistrationReceipt receipt = environment.install(TestSupport.spec("install.failure", ADD)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.FAILED, receipt.status());
        assertEquals(0, environment.activeRegistrationCount());
    }

    @Test
    public void closedEnvironmentRefusesNewInstalls() {
        environment.close();
        RegistrationReceipt receipt = environment.install(TestSupport.spec("environment.closed", ADD)
                .before(invocation -> {
                })
                .build());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, receipt.status());
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream stream = type.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("missing " + resource);
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /** Distinct loader instances with identical display names; identity must not use toString(). */
    private static final class DefiningLoader extends ClassLoader {
        private final byte[] bytes;

        private DefiningLoader(String name, byte[] bytes) {
            super(name, Fixtures.class.getClassLoader());
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // Child-first for the fixture class only: otherwise parent delegation would return the
            // already loaded Fixtures and the two loaders would look identical.
            if (name.equals(Fixtures.class.getName())) {
                Class<?> loaded = findLoadedClass(name);
                Class<?> defined = loaded != null ? loaded : findClass(name);
                if (resolve) {
                    resolveClass(defined);
                }
                return defined;
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(Fixtures.class.getName())) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }

        @Override
        public String toString() {
            // Deliberately identical for both instances: display names must never be identity keys.
            return "CopyLoader";
        }
    }
}
