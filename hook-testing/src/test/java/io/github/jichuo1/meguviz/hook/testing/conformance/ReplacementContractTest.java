package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** JVM fake-cell tests for single-target atomic replacement; not real framework qualification. */
public class ReplacementContractTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable NO_ARGS = TestSupport.method("noArguments");

    private FakeBackend backend;
    private HookEnvironment environment;

    @Before
    public void setUp() {
        backend = FakeBackend.create();
        backend.onTarget(ADD, (thisObject, args) -> (Integer) args[0] + (Integer) args[1]);
        environment = HookEnvironment.create(backend);
    }

    @After
    public void tearDown() {
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    public void successTransfersGenerationAndHandleWithoutReinstall() throws Throwable {
        HookRegistration old = TestSupport.installOrFail(environment, earlySpec("replace.success", 99));
        RegistrationReceipt replaced = environment.replace(earlySpec("replace.success", 100));

        assertTrue(replaced.toString(), replaced.isInstalled());
        assertTrue(replaced.generation() > old.generation());
        assertNotSame(old, replaced.registration());
        assertEquals(1, backend.installCount());
        assertEquals(1, backend.replaceCount());
        assertEquals(100, backend.call(ADD, null, 1, 2));
        assertEquals(0, backend.originalCallCount(ADD));
        assertEquals(RegistrationState.CLOSED, old.state());

        CloseReport oldClose = old.close();
        assertEquals(RegistrationState.CLOSED, oldClose.state());
        assertEquals(CloseReport.EntryRestoration.NOT_RESTORED, oldClose.entryRestoration());
        assertEquals(CloseReport.PhysicalRelease.NOT_CLAIMED, oldClose.physicalRelease());
        assertTrue(oldClose.message().contains("superseded by generation " + replaced.generation()));
        assertTrue("superseded close must be idempotent", oldClose == old.close());
        assertTrue("old close must not uninstall the replacement", backend.isHooked(ADD));
        replaced.registration().close();
        assertFalse(backend.isHooked(ADD));
    }

    @Test
    public void backendFailureKeepsOldGenerationAndCallback() throws Throwable {
        HookRegistration old = TestSupport.installOrFail(environment, earlySpec("replace.failure", 99));
        backend.withFailingNextReplace();
        RegistrationReceipt failed = environment.replace(earlySpec("replace.failure", 100));

        assertEquals(RegistrationReceipt.Status.FAILED, failed.status());
        assertNull(failed.registration());
        assertEquals(RegistrationState.INSTALLED, old.state());
        assertEquals(1, environment.activeRegistrationCount());
        assertEquals(99, backend.call(ADD, null, 1, 2));
        assertEquals(1, backend.installCount());
        assertEquals(1, backend.replaceCount());
    }

    @Test
    public void unsupportedAndUnknownCapabilitiesNeverReachBackend() {
        TestSupport.installOrFail(environment, earlySpec("replace.capability", 99));
        backend.withCapability(Capability.ATOMIC_REPLACE, CapabilityResult.unsupported("fake", "off"));
        assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                environment.replace(earlySpec("replace.capability", 100)).status());
        backend.withCapability(Capability.ATOMIC_REPLACE, CapabilityResult.unknown("fake", "unprobed"));
        assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                environment.replace(earlySpec("replace.capability", 100)).status());
        assertEquals(0, backend.replaceCount());
    }

    @Test
    public void missingOwnerPriorityTargetAndAllConflictsPreserveOldRegistration() throws Throwable {
        HookRegistration old = TestSupport.installOrFail(environment, earlySpec("replace.conflict", 99));
        assertEquals(RegistrationReceipt.Status.CONFLICT,
                environment.replace(earlySpec("replace.missing", 100)).status());
        assertEquals(RegistrationReceipt.Status.CONFLICT, environment.replace(
                new HookSpec.Builder(HookId.of("replace.conflict"), HookOwner.of("other"), TargetSpec.ofMember(ADD))
                        .before(invocation -> invocation.setEarlyResult(100)).build()).status());
        assertEquals(RegistrationReceipt.Status.CONFLICT,
                environment.replace(TestSupport.spec("replace.conflict", ADD).priority(70)
                        .before(invocation -> invocation.setEarlyResult(100)).build()).status());
        assertEquals(RegistrationReceipt.Status.CONFLICT,
                environment.replace(TestSupport.spec("replace.conflict", NO_ARGS)
                        .before(invocation -> invocation.setEarlyResult(100)).build()).status());
        TargetSpec all = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .match(MatchPolicy.ALL).build();
        HookSpec allSpec = new HookSpec.Builder(HookId.of("replace.conflict"),
                HookOwner.of("conformance"), all)
                .before(invocation -> invocation.setEarlyResult(100)).build();
        assertEquals(RegistrationReceipt.Status.CONFLICT, environment.replace(allSpec).status());
        assertEquals(0, backend.replaceCount());
        assertEquals(RegistrationState.INSTALLED, old.state());
        assertEquals(99, backend.call(ADD, null, 1, 2));
    }

    @Test
    public void identicalSemanticsAreIdempotentWithoutBackendCall() {
        BeforeCallback callback = invocation -> invocation.setEarlyResult(99);
        TestSupport.installOrFail(environment, TestSupport.spec("replace.idempotent", ADD).before(callback).build());
        RegistrationReceipt repeated = environment.replace(
                TestSupport.spec("replace.idempotent", ADD).before(callback).build());
        assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, repeated.status());
        assertEquals(0, backend.replaceCount());
    }

    @Test
    public void staleEpochKeepsOldRegistrationWithoutBackendCall() throws Throwable {
        HookRegistration old = TestSupport.installOrFail(environment, earlySpec("replace.stale", 99));
        backend.bumpCodeEpoch();
        RegistrationReceipt stale = environment.replace(earlySpec("replace.stale", 100));
        assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, stale.status());
        assertEquals(RegistrationState.INSTALLED, old.state());
        assertEquals(99, backend.call(ADD, null, 1, 2));
        assertEquals(0, backend.replaceCount());
    }

    @Test
    public void replacementDoesNotInterruptInflightOldPlan() throws Throwable {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        HookRegistration old = TestSupport.installOrFail(environment, TestSupport.spec("replace.inflight", ADD)
                .access(io.github.jichuo1.meguviz.hook.api.ParameterAccess.MUTABLE_ARGS)
                .before(invocation -> {
                    entered.countDown();
                    await(finish);
                    ((MutableArgsInvocation) invocation).setArgument(0, 10);
                }).build());
        AtomicReference<Object> oldResult = new AtomicReference<Object>();
        AtomicReference<Throwable> oldFailure = new AtomicReference<Throwable>();
        Thread call = new Thread(() -> {
            try {
                oldResult.set(backend.call(ADD, null, 1, 2));
            } catch (Throwable failure) {
                oldFailure.set(failure);
            }
        });
        call.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        RegistrationReceipt replaced = environment.replace(TestSupport.spec("replace.inflight", ADD)
                .access(io.github.jichuo1.meguviz.hook.api.ParameterAccess.MUTABLE_ARGS)
                .before(invocation -> ((MutableArgsInvocation) invocation).setArgument(0, 20)).build());
        assertTrue(replaced.toString(), replaced.isInstalled());
        assertTrue("replacement must not wait for the old callback", call.isAlive());
        CloseReport superseded = old.close();
        assertEquals(1, superseded.inFlightAtClose());
        assertEquals(CloseReport.EntryRestoration.NOT_RESTORED, superseded.entryRestoration());
        assertTrue("superseded close must not wait for in-flight callbacks", call.isAlive());
        finish.countDown();
        call.join(5000L);
        assertFalse(call.isAlive());
        assertNull(oldFailure.get());
        assertEquals(12, oldResult.get());
        assertEquals(22, backend.call(ADD, null, 1, 2));
        assertEquals(2, backend.originalCallCount(ADD));
        assertEquals(RegistrationState.CLOSED, old.state());
    }

    @Test
    public void closeAndReplaceAreSerializedWithoutUnhookingNewGeneration() throws Exception {
        FakeBackend delegate = FakeBackend.create();
        delegate.onTarget(ADD, (thisObject, args) -> 1);
        CountDownLatch replaceEntered = new CountDownLatch(1);
        CountDownLatch allowReplace = new CountDownLatch(1);
        HookBackend gated = new HookBackend() {
            @Override
            public String id() {
                return delegate.id();
            }

            @Override
            public BackendCapabilities capabilities() {
                return delegate.capabilities();
            }

            @Override
            public BackendContext context() {
                return delegate.context();
            }

            @Override
            public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                BackendHandle handle = delegate.install(plan, channel);
                return new BackendHandle() {
                    @Override
                    public BackendCloseReport uninstall() {
                        return handle.uninstall();
                    }

                    @Override
                    public BackendHandle replace(BoundPlan newPlan, DispatchChannel newChannel)
                            throws BackendInstallException {
                        replaceEntered.countDown();
                        try {
                            if (!allowReplace.await(5, TimeUnit.SECONDS)) {
                                throw new BackendInstallException("timed out waiting to replace");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new BackendInstallException("interrupted", interrupted);
                        }
                        return handle.replace(newPlan, newChannel);
                    }
                };
            }
        };
        HookEnvironment gatedEnvironment = HookEnvironment.create(gated);
        try {
            HookRegistration old = TestSupport.installOrFail(gatedEnvironment, earlySpec("replace.race", 99));
            AtomicReference<RegistrationReceipt> replaced = new AtomicReference<RegistrationReceipt>();
            Thread replacing = new Thread(() -> replaced.set(gatedEnvironment.replace(earlySpec("replace.race", 100))));
            replacing.start();
            assertTrue(replaceEntered.await(5, TimeUnit.SECONDS));
            CountDownLatch closeStarted = new CountDownLatch(1);
            Thread closing = new Thread(() -> {
                closeStarted.countDown();
                old.close();
            });
            closing.start();
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(100L);
            assertTrue("close must wait for replacement's registration monitor", closing.isAlive());

            allowReplace.countDown();
            replacing.join(5000L);
            closing.join(5000L);
            assertFalse(replacing.isAlive());
            assertFalse(closing.isAlive());
            assertTrue(replaced.get().toString(), replaced.get().isInstalled());
            assertTrue("old close must not uninstall the new generation", delegate.isHooked(ADD));
            replaced.get().registration().close();
            assertFalse(delegate.isHooked(ADD));
        } finally {
            allowReplace.countDown();
            gatedEnvironment.close();
        }
    }

    private static HookSpec earlySpec(String id, int value) {
        return TestSupport.spec(id, ADD).before(invocation -> invocation.setEarlyResult(value)).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
