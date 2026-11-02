package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** An unconfirmed entry must not be silently replaced by a new registration in the same environment. */
public class RetirementRecoveryTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void rollbackFailureOrUnknownRetainsOwnershipAndRejectsReuse() throws Throwable {
        verifyUnconfirmedRollback(CloseMode.THROW, CloseReport.EntryRestoration.UNKNOWN);
        verifyUnconfirmedRollback(CloseMode.UNKNOWN, CloseReport.EntryRestoration.UNKNOWN);
    }

    private static void verifyUnconfirmedRollback(CloseMode mode, CloseReport.EntryRestoration expected)
            throws Throwable {
        ControlledBackend backend = new ControlledBackend(mode, true);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("retirement.rollback", ADD)
                .before(invocation -> invocation.setEarlyResult(99)).build();
        try {
            RegistrationReceipt stale = environment.install(spec);
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, stale.status());
            assertEquals(TargetInstallReceipt.Status.ROLLED_BACK, stale.targets().get(0).status());
            assertTrue(stale.message(), stale.message().contains(expected.toString()));
            assertTrue(backend.delegate.isHooked(ADD));
            assertEquals("a retired entry passes through without its callback", 3,
                    backend.delegate.call(ADD, null, 1, 2));
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(1, backend.delegate.installCount());
            assertEquals(1, backend.uninstallCalls);
        } finally {
            environment.close();
        }
        assertEquals("closing the environment does not silently retry uninstall", 1, backend.uninstallCalls);
    }

    @Test
    public void restoredEntryMayBeReinstalledEvenIfBackendRemainsResident() {
        ControlledBackend backend = new ControlledBackend(CloseMode.RESTORED_RESIDENT, true);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("retirement.resident", ADD).before(invocation -> { }).build();
        try {
            RegistrationReceipt stale = environment.install(spec);
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, stale.status());
            assertTrue(stale.message(), stale.message().contains("entry=RESTORED"));
            assertFalse(backend.delegate.isHooked(ADD));
            assertTrue(environment.install(spec).isInstalled());
            assertEquals(2, backend.delegate.installCount());
        } finally {
            environment.close();
        }
    }

    @Test
    public void explicitCloseFailureAlsoPreventsReinstall() {
        ControlledBackend backend = new ControlledBackend(CloseMode.THROW, false);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("retirement.close", ADD).before(invocation -> { }).build();
        try {
            HookRegistration registration = TestSupport.installOrFail(environment, spec);
            CloseReport report = registration.close();
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertTrue(backend.delegate.isHooked(ADD));
            assertEquals(1, backend.delegate.installCount());
            assertEquals(1, backend.uninstallCalls);
        } finally {
            environment.close();
        }
    }

    @Test
    public void staleAttemptWithoutInstalledEntryDoesNotQuarantineId() {
        FakeBackend delegate = FakeBackend.create();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        HookBackend backend = new HookBackend() {
            @Override public String id() { return delegate.id(); }
            @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
            @Override public BackendContext context() { return delegate.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                if (failOnce.getAndSet(false)) {
                    delegate.bumpCodeEpoch();
                    throw new BackendInstallException("failed before installing an entry");
                }
                return delegate.install(plan, channel);
            }
        };
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("retirement.noentry", ADD).before(invocation -> { }).build();
        try {
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, environment.install(spec).status());
            assertEquals(0, environment.activeRegistrationCount());
            assertTrue(environment.install(spec).isInstalled());
            assertEquals(1, delegate.installCount());
        } finally {
            environment.close();
        }
    }

    private enum CloseMode { THROW, UNKNOWN, RESTORED_RESIDENT }

    private static final class ControlledBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final CloseMode closeMode;
        boolean changeEpochOnInstall;
        int uninstallCalls;

        ControlledBackend(CloseMode closeMode, boolean changeEpochOnInstall) {
            this.closeMode = closeMode;
            this.changeEpochOnInstall = changeEpochOnInstall;
            delegate.onTarget(ADD, (thisObject, args) -> (Integer) args[0] + (Integer) args[1]);
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            BackendHandle handle = delegate.install(plan, channel);
            if (changeEpochOnInstall) {
                changeEpochOnInstall = false;
                delegate.bumpCodeEpoch();
            }
            return () -> {
                uninstallCalls++;
                if (closeMode == CloseMode.THROW) {
                    throw new IllegalStateException("uninstall failed before removing entry");
                }
                if (closeMode == CloseMode.UNKNOWN) {
                    return new BackendCloseReport(CloseReport.EntryRestoration.UNKNOWN,
                            CloseReport.PhysicalRelease.UNKNOWN, false, "entry restoration unconfirmed");
                }
                handle.uninstall();
                return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                        CloseReport.PhysicalRelease.NOT_CLAIMED, true, "entry restored; backend stays resident");
            };
        }
    }
}
