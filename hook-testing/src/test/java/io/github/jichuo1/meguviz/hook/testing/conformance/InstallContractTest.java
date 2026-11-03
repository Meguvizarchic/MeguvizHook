package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.ProcessFilter;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
        assertFalse(receipt.isSatisfied());
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
        assertFalse(conflict.isSatisfied());
        assertEquals("conflicting install must not replace the existing hook", 1, backend.installCount());
    }

    @Test
    public void satisfiedIncludesIdenticalRegistrationWithoutTransferringItsHandle() {
        HookSpec spec = TestSupport.spec("satisfied.same", ADD).replaceConstant(7).build();
        try {
            RegistrationReceipt first = environment.install(spec);
            assertTrue(first.isInstalled());
            assertTrue(first.isSatisfied());
            HookRegistration owned = first.registration();
            assertTrue(owned != null);

            RegistrationReceipt repeated = environment.install(spec);
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, repeated.status());
            assertFalse(repeated.isInstalled());
            assertTrue(repeated.isSatisfied());
            assertNull(repeated.registration());
            assertEquals(first.generation(), repeated.generation());

            RegistrationReceipt replacement = environment.replace(spec);
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, replacement.status());
            assertTrue(replacement.isSatisfied());
            assertNull(replacement.registration());
            assertEquals(first.generation(), replacement.generation());
            assertEquals(1, backend.installCount());

            owned.close();
            assertTrue("receipt reports the earlier outcome, not current liveness", repeated.isSatisfied());
        } finally { environment.close(); }
    }

    @Test
    public void descriptorMatchPolicyAndInitializationArePartOfInstallSemantics() {
        BeforeCallback callback = invocation -> { };
        TargetSpec.DescriptorTarget unique = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "add")
                .parameters("int", "int").match(MatchPolicy.UNIQUE).build();
        TargetSpec.DescriptorTarget first = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "add")
                .parameters("int", "int").match(MatchPolicy.FIRST).build();
        TargetSpec.DescriptorTarget initialized = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "add")
                .parameters("int", "int").initializeClass(true).build();
        HookSpec original = descriptorSpec("duplicate.descriptor", unique, callback);
        assertTrue(environment.install(original).isInstalled());
        assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED,
                environment.install(descriptorSpec("duplicate.descriptor", unique, callback)).status());
        assertEquals(RegistrationReceipt.Status.CONFLICT,
                environment.install(descriptorSpec("duplicate.descriptor", first, callback)).status());
        assertEquals(RegistrationReceipt.Status.CONFLICT,
                environment.install(descriptorSpec("duplicate.descriptor", initialized, callback)).status());
        assertEquals(1, backend.installCount());
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
        assertEquals(TestSupport.method("overloaded", int.class), receipt.targets().get(0).target());
    }

    @Test
    public void matchAllInstallsEveryOverloadAndClosesEveryEntry() throws Throwable {
        Executable intTarget = TestSupport.method("overloaded", int.class);
        Executable stringTarget = TestSupport.method("overloaded", String.class);
        backend.onTarget(intTarget, (thisObject, args) -> ((Integer) args[0]) + 1);
        backend.onTarget(stringTarget, (thisObject, args) -> "original:" + args[0]);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = allOverloads("target.all.success", callbacks);

        RegistrationReceipt receipt = environment.install(spec);
        assertTrue(receipt.toString(), receipt.isInstalled());
        assertEquals(2, receipt.targets().size());
        assertEquals(2, backend.installCount());
        assertTrue(backend.isHooked(intTarget));
        assertTrue(backend.isHooked(stringTarget));
        assertEquals(8, backend.call(intTarget, null, 7));
        assertEquals("original:x", backend.call(stringTarget, null, "x"));
        assertEquals(2, callbacks.get());

        HookRegistration registration = receipt.registration();
        registration.close();
        assertFalse(backend.isHooked(intTarget));
        assertFalse(backend.isHooked(stringTarget));
        assertEquals(8, backend.call(intTarget, null, 7));
        assertEquals("original:x", backend.call(stringTarget, null, "x"));
        assertEquals("close must reject callbacks on every target", 2, callbacks.get());
    }

    @Test
    public void matchAllPartialFailureHasPerTargetReceiptsAndKeepsSuccessfulEntry() {
        backend.withFailingNextInstall();
        HookSpec spec = allOverloads("target.all.partial", new AtomicInteger());
        RegistrationReceipt receipt = environment.install(spec);

        assertEquals(RegistrationReceipt.Status.PARTIALLY_INSTALLED, receipt.status());
        assertFalse("partial success must not look like full success", receipt.isInstalled());
        assertFalse("partial installation does not satisfy the full request", receipt.isSatisfied());
        assertTrue(receipt.isPartiallyInstalled());
        assertEquals(2, receipt.targets().size());
        assertEquals(TargetInstallReceipt.Status.FAILED, receipt.targets().get(0).status());
        assertEquals(TargetInstallReceipt.Status.INSTALLED, receipt.targets().get(1).status());
        assertEquals(1, backend.installCount());
        assertEquals(1, environment.activeRegistrationCount());

        RegistrationReceipt retry = environment.install(spec);
        assertEquals("a partial registration cannot hide missing targets behind ALREADY_INSTALLED",
                RegistrationReceipt.Status.CONFLICT, retry.status());
        assertEquals(1, backend.installCount());
        receipt.registration().close();
        assertFalse(backend.isHooked(TestSupport.method("overloaded", String.class)));

        RegistrationReceipt retried = environment.install(spec);
        assertTrue(retried.toString(), retried.isInstalled());
        assertEquals("closing the partial generation permits a complete fresh install",
                3, backend.installCount());
        retried.registration().close();
    }

    @Test
    public void matchAllPreservesTargetOrderWhenMiddleInstallFails() throws Throwable {
        Executable first = TestSupport.method("add", int.class, int.class);
        Executable middle = TestSupport.method("overloaded", int.class);
        Executable last = TestSupport.method("concat", String.class, String.class);
        List<Executable> targets = Arrays.asList(first, middle, last);
        List<Executable> attempts = new ArrayList<Executable>();
        List<Executable> callbacks = new ArrayList<Executable>();
        HookBackend failsMiddle = new HookBackend() {
            @Override public String id() { return backend.id(); }
            @Override public io.github.jichuo1.meguviz.hook.api.BackendCapabilities capabilities() { return backend.capabilities(); }
            @Override public BackendContext context() { return backend.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                attempts.add(plan.target());
                if (plan.target().equals(middle)) throw new BackendInstallException("middle target unavailable");
                return backend.install(plan, channel);
            }
        };
        HookEnvironment scoped = HookEnvironment.create(failsMiddle, (descriptor, loader) -> targets,
                io.github.jichuo1.meguviz.hook.core.DiagnosticsSink.NONE);
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(TargetKind.METHOD,
                Fixtures.class.getName(), "unused").match(MatchPolicy.ALL).build();
        HookSpec spec = HookSpec.builder("target.all.middle.failure", "conformance", descriptor)
                .before(invocation -> callbacks.add(invocation.target())).build();
        backend.onTarget(first, (receiver, args) -> 3);
        backend.onTarget(last, (receiver, args) -> "ok");
        try {
            RegistrationReceipt receipt = scoped.install(spec);
            assertEquals(RegistrationReceipt.Status.PARTIALLY_INSTALLED, receipt.status());
            assertEquals(targets, attempts);
            assertEquals(3, receipt.targets().size());
            assertEquals(first, receipt.targets().get(0).target());
            assertEquals(TargetInstallReceipt.Status.INSTALLED, receipt.targets().get(0).status());
            assertEquals(middle, receipt.targets().get(1).target());
            assertEquals(TargetInstallReceipt.Status.FAILED, receipt.targets().get(1).status());
            assertEquals(last, receipt.targets().get(2).target());
            assertEquals(TargetInstallReceipt.Status.INSTALLED, receipt.targets().get(2).status());
            assertEquals(3, backend.call(first, null, 1, 2));
            assertEquals("ok", backend.call(last, null, "a", "b"));
            assertEquals(Arrays.asList(first, last), callbacks);
        } finally { scoped.close(); }
    }

    @Test
    public void matchAllTotalFailurePublishesNoRegistration() {
        backend.withFailingAllInstalls();
        RegistrationReceipt receipt = environment.install(allOverloads("target.all.failed", new AtomicInteger()));

        assertEquals(RegistrationReceipt.Status.FAILED, receipt.status());
        assertNull(receipt.registration());
        assertEquals(2, receipt.targets().size());
        for (TargetInstallReceipt target : receipt.targets()) {
            assertEquals(TargetInstallReceipt.Status.FAILED, target.status());
        }
        assertEquals(0, backend.installCount());
        assertEquals(0, environment.activeRegistrationCount());
    }

    @Test
    public void matchAllEpochChangeRollsBackUnpublishedEntries() {
        backend.withEpochBumpOnNextInstall();
        RegistrationReceipt receipt = environment.install(allOverloads("target.all.stale", new AtomicInteger()));

        assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, receipt.status());
        assertNull(receipt.registration());
        assertEquals(2, receipt.targets().size());
        for (TargetInstallReceipt target : receipt.targets()) {
            assertEquals(TargetInstallReceipt.Status.ROLLED_BACK, target.status());
            assertFalse(backend.isHooked(target.target()));
        }
        assertEquals(2, backend.installCount());
        assertEquals(0, environment.activeRegistrationCount());
    }

    private static HookSpec allOverloads(String id, AtomicInteger callbacks) {
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(
                        TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .match(MatchPolicy.ALL)
                .build();
        return new HookSpec.Builder(io.github.jichuo1.meguviz.hook.api.HookId.of(id),
                io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"), descriptor)
                .before(invocation -> callbacks.incrementAndGet())
                .build();
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
        assertEquals("member display strings do not encode loader identity", firstAdd.toString(), secondAdd.toString());
        assertEquals(firstLoader, firstAdd.getDeclaringClass().getClassLoader());
        assertEquals(secondLoader, secondAdd.getDeclaringClass().getClassLoader());
        BeforeCallback callback = invocation -> { };
        HookSpec firstSpec = TestSupport.spec("loader.identity", firstAdd).before(callback).build();
        HookSpec sameMember = TestSupport.spec("loader.identity", first.getMethod("add", int.class, int.class))
                .before(callback).build();
        HookSpec secondSpec = TestSupport.spec("loader.identity", secondAdd).before(callback).build();
        assertTrue(firstSpec.hasSameSemantics(sameMember));
        assertFalse(firstSpec.hasSameSemantics(secondSpec));
        assertFalse(secondSpec.hasSameSemantics(firstSpec));
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
    public void retiringGenerationRejectsReplacementUntilCloseCompletes() throws Exception {
        FakeBackend delegate = FakeBackend.create();
        CountDownLatch uninstallEntered = new CountDownLatch(1);
        CountDownLatch allowUninstall = new CountDownLatch(1);
        HookBackend gated = new HookBackend() {
            @Override
            public String id() {
                return delegate.id();
            }

            @Override
            public io.github.jichuo1.meguviz.hook.api.BackendCapabilities capabilities() {
                return delegate.capabilities();
            }

            @Override
            public BackendContext context() {
                return delegate.context();
            }

            @Override
            public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                BackendHandle handle = delegate.install(plan, channel);
                return () -> {
                    uninstallEntered.countDown();
                    try {
                        if (!allowUninstall.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out waiting to finish test uninstall");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return handle.uninstall();
                };
            }
        };
        HookEnvironment gatedEnvironment = HookEnvironment.create(gated);
        HookSpec spec = TestSupport.spec("registration.retiring", ADD).before(invocation -> {
        }).build();
        HookRegistration first = TestSupport.installOrFail(gatedEnvironment, spec);
        Thread closing = new Thread(first::close);
        closing.start();
        assertTrue(uninstallEntered.await(5, TimeUnit.SECONDS));

        RegistrationReceipt duringClose = gatedEnvironment.install(spec);
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, duringClose.status());
        assertEquals(1, delegate.installCount());

        allowUninstall.countDown();
        closing.join(5000L);
        assertFalse(closing.isAlive());
        RegistrationReceipt afterClose = gatedEnvironment.install(spec);
        assertTrue(afterClose.toString(), afterClose.isInstalled());
        assertEquals(2, delegate.installCount());
        afterClose.registration().close();
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

    private static HookSpec descriptorSpec(String id, TargetSpec target, BeforeCallback callback) {
        return new HookSpec.Builder(io.github.jichuo1.meguviz.hook.api.HookId.of(id),
                io.github.jichuo1.meguviz.hook.api.HookOwner.of("conformance"), target)
                .before(callback).build();
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
