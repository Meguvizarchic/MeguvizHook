package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;
import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Same-thread external callbacks must not bypass installation ownership and lifecycle gates. */
public final class ReentrantInstallContractTest {
    private static final Executable TARGET = TestSupport.method("add", int.class, int.class);

    @Test public void sameIdInstallReentryIsRejectedBeforeASecondBackendInstall() {
        Backend backend = new Backend(Mode.SAME_ID);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        backend.outer = spec("reentrant.same", 1);
        backend.armed = true;
        try {
            RegistrationReceipt receipt = env.install(backend.outer);
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertTrue("same-ID reentry must fail explicitly", backend.reentryFailure instanceof IllegalStateException);
            assertNull(backend.inner);
            assertEquals(1, backend.installCalls);
            assertEquals(1, env.activeRegistrationCount());
            env.close();
            assertEquals(1, backend.uninstalls[1]);
            assertEquals(0, backend.uninstalls[2]);
        } finally { env.close(); }
        assertEquals(1, backend.uninstalls[1]);
    }

    @Test public void sameIdReentryDuringBackendIdDoesNotReenterBackendIdAgain() {
        Backend backend = new Backend(Mode.ID_REENTRY);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        backend.outer = spec("reentrant.backend.id", 1);
        backend.armed = true;
        try {
            RegistrationReceipt receipt = env.install(backend.outer);
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertTrue(backend.reentryFailure instanceof IllegalStateException);
            assertNull(backend.inner);
            assertEquals(1, backend.idCalls);
            assertEquals(1, backend.installCalls);
            env.close();
            assertEquals(1, backend.uninstalls[1]);
        } finally { env.close(); }
    }

    @Test public void differentIdInstallReentryRetainsBothHandles() {
        Backend backend = new Backend(Mode.DIFFERENT_ID);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        backend.outer = spec("reentrant.outer", 1);
        backend.nested = spec("reentrant.inner", 2);
        backend.armed = true;
        try {
            RegistrationReceipt outer = env.install(backend.outer);
            assertTrue(outer.toString(), outer.isInstalled());
            assertNull(backend.reentryFailure);
            assertNotNull(backend.inner);
            assertTrue(backend.inner.toString(), backend.inner.isInstalled());
            assertEquals(2, backend.installCalls);
            assertEquals(2, env.activeRegistrationCount());
            env.close();
            assertEquals(1, backend.uninstalls[1]);
            assertEquals(1, backend.uninstalls[2]);
        } finally { env.close(); }
        assertEquals(1, backend.uninstalls[1]);
        assertEquals(1, backend.uninstalls[2]);
    }

    @Test public void closeReentryFromContextCapabilitiesAndInstallIsRejected() {
        for (Mode mode : new Mode[] {Mode.CONTEXT_CLOSE, Mode.CAPABILITIES_CLOSE, Mode.INSTALL_CLOSE}) {
            Backend backend = new Backend(mode);
            HookEnvironment env = HookEnvironment.create(backend);
            backend.environment = env;
            backend.outer = spec("reentrant.close." + mode.name().toLowerCase(java.util.Locale.ROOT), 1);
            backend.armed = true;
            try {
                RegistrationReceipt receipt = env.install(backend.outer);
                assertTrue(mode + ": " + receipt, receipt.isInstalled());
                assertTrue(mode + " close must fail explicitly", backend.reentryFailure instanceof IllegalStateException);
                assertEquals(mode.toString(), 1, backend.installCalls);
                assertEquals(mode.toString(), 1, env.activeRegistrationCount());
                env.close();
                assertEquals(mode.toString(), 1, backend.uninstalls[1]);
            } finally { env.close(); }
            assertEquals(mode.toString(), 1, backend.uninstalls[1]);
        }
    }

    @Test public void uncaughtInstallCloseReentryQuarantinesOnlyTheInterruptedId() {
        Backend backend = new Backend(Mode.INSTALL_CLOSE_UNCAUGHT);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        HookSpec interrupted = spec("reentrant.install.uncaught", 1);
        backend.armed = true;
        try {
            Throwable error = TestSupport.capture(() -> env.install(interrupted));
            assertTrue(error instanceof IllegalStateException);
            assertEquals(1, backend.installCalls);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(interrupted).status());
            RegistrationReceipt other = env.install(spec("reentrant.install.other", 2));
            assertTrue(other.toString(), other.isInstalled());
            assertEquals(2, backend.installCalls);
            env.close();
            assertEquals("unknown first handle was never returned", 0, backend.uninstalls[1]);
            assertEquals("independent ID remains manageable", 1, backend.uninstalls[2]);
        } finally { env.close(); }
        assertEquals(0, backend.uninstalls[1]);
        assertEquals(1, backend.uninstalls[2]);
    }

    @Test public void adoptionCallbackCanCatchRejectedCloseAndPreserveOwnership() {
        for (boolean onBackend : new boolean[] {true, false}) {
            Backend backend = new Backend(Mode.ADOPTION);
            HookEnvironment env = HookEnvironment.create(backend);
            backend.environment = env;
            ReentrantAdoption adoption = new ReentrantAdoption(backend, onBackend, true);
            try {
                RegistrationReceipt receipt = env.adopt(spec("reentrant.adopt.caught", 1), adoption);
                assertTrue(receipt.toString(), receipt.isInstalled());
                assertTrue(adoption.reentryFailure instanceof IllegalStateException);
                assertEquals(1, adoption.replaces);
                assertEquals(0, backend.installCalls);
                assertEquals(1, env.activeRegistrationCount());
                env.close();
                assertEquals(1, backend.uninstalls[1]);
            } finally { env.close(); }
            assertEquals(1, backend.uninstalls[1]);
        }
    }

    @Test public void uncaughtCloseFromAdoptionBackendDoesNotConsumeTheId() {
        Backend backend = new Backend(Mode.ADOPTION);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        HookSpec spec = spec("reentrant.adopt.backend", 1);
        ReentrantAdoption rejected = new ReentrantAdoption(backend, true, false);
        try {
            assertTrue(TestSupport.capture(() -> env.adopt(spec, rejected)) instanceof IllegalStateException);
            assertEquals(0, rejected.replaces);
            assertEquals(0, env.activeRegistrationCount());
            RegistrationReceipt retry = env.install(spec);
            assertTrue(retry.toString(), retry.isInstalled());
            assertEquals(1, backend.installCalls);
            env.close();
            assertEquals(1, backend.uninstalls[1]);
        } finally { env.close(); }
    }

    @Test public void uncaughtCloseFromAdoptionReplaceQuarantinesUnknownOutcome() {
        Backend backend = new Backend(Mode.ADOPTION);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        HookSpec spec = spec("reentrant.adopt.replace", 1);
        ReentrantAdoption adoption = new ReentrantAdoption(backend, false, false);
        try {
            assertTrue(TestSupport.capture(() -> env.adopt(spec, adoption)) instanceof IllegalStateException);
            assertEquals(1, adoption.replaces);
            assertNotNull(adoption.channel);
            assertEquals(DispatchResult.Kind.PASSTHROUGH, adoption.channel.dispatch(null).kind());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(spec).status());
            assertEquals(0, backend.installCalls);
            assertEquals(0, backend.uninstalls[1]);
        } finally { env.close(); }
        assertEquals(0, backend.uninstalls[1]);
    }

    @Test public void fatalFailureReleasesIdGuardWithoutLosingUnknownOutcome() {
        for (Mode mode : new Mode[] {Mode.ID_FATAL, Mode.INSTALL_FATAL}) {
            Backend backend = new Backend(mode);
            HookEnvironment env = HookEnvironment.create(backend);
            backend.environment = env;
            HookSpec original = spec("reentrant.fatal", 1);
            backend.armed = true;
            try {
                assertSame(mode.toString(), backend.fatal, TestSupport.capture(() -> env.install(original)));
                if (mode == Mode.ID_FATAL) {
                    RegistrationReceipt retry = env.install(original);
                    assertTrue(retry.toString(), retry.isInstalled());
                    assertEquals(1, backend.installCalls);
                } else {
                    assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(original).status());
                    RegistrationReceipt other = env.install(spec("reentrant.fatal.other", 2));
                    assertTrue(other.toString(), other.isInstalled());
                    assertEquals(2, backend.installCalls);
                }
            } finally { env.close(); }
        }
    }

    @Test public void multiTargetSecondReentrantCloseCleansFirstAndQuarantinesUnknownSecond() {
        Backend backend = new Backend(Mode.MULTI_SECOND_CLOSE);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .match(MatchPolicy.ALL).build();
        HookSpec spec = HookSpec.builder("reentrant.multi", "conformance", target).before(invocation -> { }).build();
        backend.armed = true;
        try {
            assertTrue(TestSupport.capture(() -> env.install(spec)) instanceof IllegalStateException);
            assertEquals(2, backend.installCalls);
            assertEquals("first returned handle is cleaned", 1, backend.uninstalls[1]);
            assertEquals("second backend operation did not return a handle", 0, backend.uninstalls[2]);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(spec).status());
            RegistrationReceipt other = env.install(spec("reentrant.multi.other", 1));
            assertTrue(other.toString(), other.isInstalled());
            env.close();
            assertEquals(1, backend.uninstalls[1]);
            assertEquals(0, backend.uninstalls[2]);
            assertEquals(1, backend.uninstalls[3]);
        } finally { env.close(); }
    }

    @Test public void oldRegistrationCloseCannotReenterItsReplacement() {
        Backend backend = new Backend(Mode.REPLACE_CLOSE);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        HookSpec first = spec("reentrant.replace", 1);
        HookSpec second = spec("reentrant.replace", 2);
        try {
            RegistrationReceipt original = env.install(first);
            assertTrue(original.toString(), original.isInstalled());
            backend.oldRegistration = original.registration();
            backend.armed = true;
            RegistrationReceipt replacement = env.replace(second);
            assertTrue(replacement.toString(), replacement.isInstalled());
            assertTrue("old close during replace must fail explicitly", backend.reentryFailure instanceof IllegalStateException);
            assertEquals(1, backend.replaceCalls);
            assertEquals("old handle ownership transferred", 0, backend.uninstalls[1]);
            assertEquals(0, backend.uninstalls[2]);
            assertEquals(1, env.activeRegistrationCount());
            replacement.registration().close();
            assertEquals(0, backend.uninstalls[1]);
            assertEquals(1, backend.uninstalls[2]);
        } finally { env.close(); }
        assertEquals(0, backend.uninstalls[1]);
        assertEquals(1, backend.uninstalls[2]);
    }

    @Test public void uncaughtOldCloseReentryQuarantinesBothReplacementGenerations() {
        Backend backend = new Backend(Mode.REPLACE_CLOSE_UNCAUGHT);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        HookSpec first = spec("reentrant.replace.unknown", 1);
        HookSpec second = spec("reentrant.replace.unknown", 2);
        try {
            RegistrationReceipt original = env.install(first);
            assertTrue(original.toString(), original.isInstalled());
            backend.oldRegistration = original.registration();
            backend.armed = true;
            Throwable failure = TestSupport.capture(() -> env.replace(second));
            assertTrue(failure instanceof IllegalStateException);
            assertEquals(1, backend.replaceCalls);
            assertEquals(RegistrationState.RETIRED_RESIDENT, backend.oldRegistration.state());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, backend.oldRegistration.close().entryRestoration());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(first).status());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.replace(second).status());
            assertEquals("unknown old handle must not be unhooked", 0, backend.uninstalls[1]);
            assertEquals("replacement did not return a handle", 0, backend.uninstalls[2]);
        } finally { env.close(); }
        assertEquals(0, backend.uninstalls[1]);
        assertEquals(0, backend.uninstalls[2]);
    }

    @Test public void uninstallCanCatchRejectedReentrantCloseAndReturnItsReport() {
        Backend backend = new Backend(Mode.UNINSTALL_CLOSE_CAUGHT);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        try {
            RegistrationReceipt receipt = env.install(spec("reentrant.uninstall.caught", 1));
            assertTrue(receipt.toString(), receipt.isInstalled());
            backend.oldRegistration = receipt.registration();
            backend.armed = true;
            CloseReport report = backend.oldRegistration.close();
            assertTrue(backend.reentryFailure instanceof IllegalStateException);
            assertEquals(CloseReport.EntryRestoration.RESTORED, report.entryRestoration());
            assertEquals(RegistrationState.CLOSED, report.state());
            assertEquals(1, backend.uninstalls[1]);
            assertSame(report, backend.oldRegistration.close());
            assertEquals(1, backend.uninstalls[1]);
        } finally { env.close(); }
        assertEquals(1, backend.uninstalls[1]);
    }

    @Test public void uncaughtUninstallReentrantCloseIsUnknownAndNeverRetried() {
        Backend backend = new Backend(Mode.UNINSTALL_CLOSE_UNCAUGHT);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        try {
            RegistrationReceipt receipt = env.install(spec("reentrant.uninstall.unknown", 1));
            assertTrue(receipt.toString(), receipt.isInstalled());
            backend.oldRegistration = receipt.registration();
            backend.armed = true;
            CloseReport report = backend.oldRegistration.close();
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
            assertEquals(CloseReport.PhysicalRelease.UNKNOWN, report.physicalRelease());
            assertEquals(RegistrationState.RETIRED_RESIDENT, report.state());
            assertEquals(1, backend.uninstalls[1]);
            assertSame(report, backend.oldRegistration.close());
            assertEquals(1, backend.uninstalls[1]);
        } finally { env.close(); }
        assertEquals(1, backend.uninstalls[1]);
    }

    @Test public void fatalUninstallStillLeavesRetiringWithoutCompletedReport() {
        Backend backend = new Backend(Mode.UNINSTALL_FATAL);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        try {
            RegistrationReceipt receipt = env.install(spec("reentrant.uninstall.fatal", 1));
            assertTrue(receipt.toString(), receipt.isInstalled());
            backend.oldRegistration = receipt.registration();
            backend.armed = true;
            assertSame(backend.fatal, TestSupport.capture(() -> backend.oldRegistration.close()));
            assertEquals(RegistrationState.RETIRING, backend.oldRegistration.state());
            assertNull("a fatal interruption does not fabricate a completed report", backend.oldRegistration.close());
            assertEquals(1, backend.uninstalls[1]);
        } finally { env.close(); }
        assertEquals(1, backend.uninstalls[1]);
    }

    @Test public void anotherThreadCloseWaitsForInstallThenUninstalls() throws Throwable {
        Backend backend = new Backend(Mode.BLOCK_INSTALL);
        HookEnvironment env = HookEnvironment.create(backend);
        backend.environment = env;
        HookSpec spec = spec("reentrant.cross.thread", 1);
        CountDownLatch closeStarted = new CountDownLatch(1), closeDone = new CountDownLatch(1);
        AtomicReference<RegistrationReceipt> receipt = new AtomicReference<RegistrationReceipt>();
        AtomicReference<Throwable> installFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<Throwable>();
        Thread installer = new Thread(() -> installFailure.set(TestSupport.capture(() -> receipt.set(env.install(spec)))),
                "reentrant-contract-installer");
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            try { closeFailure.set(TestSupport.capture(env::close)); }
            finally { closeDone.countDown(); }
        }, "reentrant-contract-closer");
        try {
            installer.start();
            assertTrue(backend.installEntered.await(5, TimeUnit.SECONDS));
            closer.start();
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertFalse("another thread must not finish close while install owns the monitor",
                    closeDone.await(100, TimeUnit.MILLISECONDS));
            backend.installRelease.countDown();
            installer.join(5000);
            closer.join(5000);
            assertFalse(installer.isAlive());
            assertFalse(closer.isAlive());
            assertNull(installFailure.get());
            assertNull(closeFailure.get());
            assertNotNull(receipt.get());
            assertTrue(receipt.get().toString(), receipt.get().isInstalled());
            assertEquals(1, backend.installCalls);
            assertEquals(1, backend.uninstalls[1]);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(spec).status());
        } finally {
            backend.installRelease.countDown();
            installer.join(5000);
            if (closer.getState() != Thread.State.NEW) closer.join(5000);
            env.close();
        }
        assertEquals(1, backend.uninstalls[1]);
    }

    private static HookSpec spec(String id, int value) {
        return TestSupport.spec(id, TARGET).before(invocation -> invocation.setEarlyResult(value)).build();
    }

    private enum Mode {
        SAME_ID, ID_REENTRY, DIFFERENT_ID, CONTEXT_CLOSE, CAPABILITIES_CLOSE, INSTALL_CLOSE,
        INSTALL_CLOSE_UNCAUGHT, REPLACE_CLOSE, REPLACE_CLOSE_UNCAUGHT, BLOCK_INSTALL,
        ADOPTION, ID_FATAL, INSTALL_FATAL, MULTI_SECOND_CLOSE,
        UNINSTALL_CLOSE_CAUGHT, UNINSTALL_CLOSE_UNCAUGHT, UNINSTALL_FATAL
    }

    private static final class Backend implements HookBackend, BackendContext {
        final Mode mode;
        final int[] uninstalls = new int[4];
        final CountDownLatch installEntered = new CountDownLatch(1);
        final CountDownLatch installRelease = new CountDownLatch(1);
        final OutOfMemoryError fatal = new OutOfMemoryError("synthetic fatal");
        final BackendCapabilities capabilities = new BackendCapabilities.Builder("reentrant-test", ChainSemantics.FRAMEWORK_CHAIN)
                .declare(Capability.ATOMIC_REPLACE, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, "reentrant-test", null, null, "fixture"))
                .build();
        HookEnvironment environment;
        HookSpec outer;
        HookSpec nested;
        HookRegistration oldRegistration;
        RegistrationReceipt inner;
        Throwable reentryFailure;
        boolean armed;
        int idCalls;
        int installCalls;
        int replaceCalls;

        Backend(Mode mode) { this.mode = mode; }

        private void closeEnvironmentIf(Mode when) {
            if (armed && mode == when) {
                armed = false;
                reentryFailure = TestSupport.capture(() -> environment.close());
            }
        }

        @Override public String id() {
            idCalls++;
            if (armed && mode == Mode.ID_FATAL) {
                armed = false;
                throw fatal;
            }
            if (armed && mode == Mode.ID_REENTRY) {
                armed = false;
                reentryFailure = TestSupport.capture(() -> inner = environment.install(outer));
            }
            return "reentrant-test";
        }
        @Override public BackendCapabilities capabilities() { closeEnvironmentIf(Mode.CAPABILITIES_CLOSE); return capabilities; }
        @Override public BackendContext context() { return this; }
        @Override public String processName() { closeEnvironmentIf(Mode.CONTEXT_CLOSE); return "test"; }
        @Override public ClassLoader classLoader() { return ReentrantInstallContractTest.class.getClassLoader(); }
        @Override public long codeEpoch() { return 1; }

        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) {
            int handle = ++installCalls;
            closeEnvironmentIf(Mode.INSTALL_CLOSE);
            if (armed && mode == Mode.INSTALL_CLOSE_UNCAUGHT) {
                armed = false;
                environment.close();
            }
            if (armed && mode == Mode.INSTALL_FATAL) {
                armed = false;
                throw fatal;
            }
            if (armed && mode == Mode.MULTI_SECOND_CLOSE && handle == 2) {
                armed = false;
                environment.close();
            }
            if (mode == Mode.BLOCK_INSTALL) {
                installEntered.countDown();
                try {
                    if (!installRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("install timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            if (armed && (mode == Mode.SAME_ID || mode == Mode.DIFFERENT_ID)) {
                armed = false;
                reentryFailure = TestSupport.capture(() -> inner = environment.install(mode == Mode.SAME_ID ? outer : nested));
            }
            return handle(handle);
        }

        private BackendHandle handle(int id) {
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() {
                    uninstalls[id]++;
                    if (armed && mode == Mode.UNINSTALL_CLOSE_CAUGHT) {
                        armed = false;
                        reentryFailure = TestSupport.capture(() -> oldRegistration.close());
                    }
                    if (armed && mode == Mode.UNINSTALL_CLOSE_UNCAUGHT) {
                        armed = false;
                        oldRegistration.close();
                    }
                    if (armed && mode == Mode.UNINSTALL_FATAL) {
                        armed = false;
                        throw fatal;
                    }
                    return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                            CloseReport.PhysicalRelease.NOT_CLAIMED, false, "fixture");
                }

                @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel)
                        throws BackendInstallException {
                    replaceCalls++;
                    if (armed && mode == Mode.REPLACE_CLOSE) {
                        armed = false;
                        reentryFailure = TestSupport.capture(() -> oldRegistration.close());
                    }
                    if (armed && mode == Mode.REPLACE_CLOSE_UNCAUGHT) {
                        armed = false;
                        oldRegistration.close();
                    }
                    return handle(2);
                }
            };
        }
    }

    private static final class ReentrantAdoption implements BackendAdoption {
        final Backend backend;
        final boolean onBackend;
        final boolean catchFailure;
        Throwable reentryFailure;
        DispatchChannel channel;
        int replaces;

        ReentrantAdoption(Backend backend, boolean onBackend, boolean catchFailure) {
            this.backend = backend;
            this.onBackend = onBackend;
            this.catchFailure = catchFailure;
        }

        private void reenterClose() {
            if (catchFailure) reentryFailure = TestSupport.capture(() -> backend.environment.close());
            else backend.environment.close();
        }

        @Override public HookBackend backend() {
            if (onBackend) reenterClose();
            return backend;
        }

        @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) {
            replaces++;
            this.channel = channel;
            if (!onBackend) reenterClose();
            return backend.handle(1);
        }
    }
}
