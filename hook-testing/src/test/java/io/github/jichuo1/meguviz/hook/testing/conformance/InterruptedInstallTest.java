package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
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

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** An Error cannot orphan an already installed entry or make an unknown installation retryable. */
public class InterruptedInstallTest {
    private static final Executable FIRST = TestSupport.method("overloaded", int.class);

    @Test
    public void secondInstallErrorClosesBoundHandleAndQuarantinesUnknownAttempt() throws Throwable {
        verifySecondFailure(Cleanup.NORMAL);
    }

    @Test
    public void secondInstallErrorRetainsEntriesWithUnconfirmedRestoration() throws Throwable {
        verifySecondFailure(Cleanup.RUNTIME);
        verifySecondFailure(Cleanup.UNKNOWN);
        verifySecondFailure(Cleanup.ERROR);
    }

    private static void verifySecondFailure(Cleanup mode) throws Throwable {
        InterruptedBackend backend = new InterruptedBackend(mode, false);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = allOverloads("interrupted.all", callbacks);
        try {
            assertSame(backend.failure, TestSupport.capture(() -> environment.install(spec)));
            assertEquals(2, backend.attempts);
            assertEquals(1, backend.delegate.installCount());
            assertEquals(1, backend.uninstallAttempts);
            assertEquals(mode != Cleanup.NORMAL, backend.delegate.isHooked(FIRST));
            assertEquals(8, backend.delegate.call(FIRST, null, 7));
            assertEquals("retired entry must not dispatch the callback", 0, callbacks.get());
            RegistrationReceipt retry = environment.install(spec);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, retry.status());
            assertTrue(retry.message(), retry.message().contains("unknown"));
            assertEquals(2, backend.attempts);
            assertEquals("the original Error carries cleanup details", 1, backend.failure.getSuppressed().length);
            if (mode == Cleanup.ERROR) {
                assertSame(backend.cleanupFailure, backend.failure.getSuppressed()[0]);
            } else {
                assertTrue(backend.failure.getSuppressed()[0].getMessage().contains("cleanup"));
            }
        } finally {
            environment.close();
        }
        assertEquals("close is idempotent and does not secretly retry uninstall", 1, backend.uninstallAttempts);
    }

    @Test
    public void firstInstallErrorAfterUnreturnedHandleStillRejectsCallbacksAndRetries() throws Throwable {
        InterruptedBackend backend = new InterruptedBackend(Cleanup.NORMAL, true);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = allOverloads("interrupted.first", callbacks);
        try {
            assertSame(backend.failure, TestSupport.capture(() -> environment.install(spec)));
            assertTrue("the backend side effect cannot be uninstalled without its handle", backend.delegate.isHooked(FIRST));
            assertEquals(8, backend.delegate.call(FIRST, null, 7));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(1, backend.attempts);
            assertEquals(0, backend.uninstallAttempts);
        } finally {
            environment.close();
        }
    }

    @Test
    public void fatalCleanupHasPriorityAndKeepsOriginalInstallErrorSuppressed() {
        InterruptedBackend backend = new InterruptedBackend(Cleanup.FATAL, false);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = allOverloads("interrupted.fatal", new AtomicInteger());
        try {
            Throwable failure = TestSupport.capture(() -> environment.install(spec));
            assertSame(backend.cleanupFailure, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(backend.failure, failure.getSuppressed()[0]);
            assertTrue(backend.delegate.isHooked(FIRST));
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
        } finally {
            environment.close();
        }
    }

    @Test
    public void originalFatalTakesPriorityOverAnotherFatalDuringCleanup() {
        InterruptedBackend backend = new InterruptedBackend(Cleanup.FATAL, false, new SimulatedVmError());
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = allOverloads("interrupted.fatal.first", new AtomicInteger());
        try {
            Throwable failure = TestSupport.capture(() -> environment.install(spec));
            assertSame(backend.failure, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(backend.cleanupFailure, failure.getSuppressed()[0]);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
        } finally {
            environment.close();
        }
    }

    private static HookSpec allOverloads(String id, AtomicInteger callbacks) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("conformance"),
                TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                        .match(MatchPolicy.ALL).build())
                .before(invocation -> callbacks.incrementAndGet()).build();
    }

    private enum Cleanup { NORMAL, RUNTIME, UNKNOWN, ERROR, FATAL }

    private static final class InterruptedBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final Error failure;
        final Error cleanupFailure;
        final Cleanup cleanup;
        final boolean errorOnFirstAfterInstall;
        int attempts;
        int uninstallAttempts;

        InterruptedBackend(Cleanup cleanup, boolean errorOnFirstAfterInstall) {
            this(cleanup, errorOnFirstAfterInstall, new AssertionError("framework install failed"));
        }

        InterruptedBackend(Cleanup cleanup, boolean errorOnFirstAfterInstall, Error failure) {
            this.failure = failure;
            this.cleanup = cleanup;
            this.errorOnFirstAfterInstall = errorOnFirstAfterInstall;
            this.cleanupFailure = cleanup == Cleanup.FATAL ? new SimulatedVmError() : new AssertionError("unhook failed");
            delegate.onTarget(FIRST, (receiver, args) -> (Integer) args[0] + 1);
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            attempts++;
            if (attempts == 1 && errorOnFirstAfterInstall) {
                delegate.install(plan, channel);
                throw failure;
            }
            if (attempts == 2) {
                throw failure;
            }
            BackendHandle handle = delegate.install(plan, channel);
            return () -> {
                uninstallAttempts++;
                switch (cleanup) {
                    case RUNTIME:
                        throw new IllegalStateException("unhook failed");
                    case UNKNOWN:
                        return new BackendCloseReport(CloseReport.EntryRestoration.UNKNOWN,
                                CloseReport.PhysicalRelease.UNKNOWN, false, "entry unknown");
                    case ERROR:
                    case FATAL:
                        throw cleanupFailure;
                    default:
                        return handle.uninstall();
                }
            };
        }
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
