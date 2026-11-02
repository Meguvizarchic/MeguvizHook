package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Unexpected outcomes after installation must never publish or orphan an active SDK callback. */
public class InterruptedPostInstallTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void nullHandleAfterSideEffectQuarantinesAndRejectsCallback() throws Throwable {
        ControlledBackend backend = new ControlledBackend(Mode.NULL_HANDLE, new IllegalStateException("unused"));
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = spec("post.null", callbacks);
        try {
            Throwable failure = TestSupport.capture(() -> environment.install(spec));
            assertTrue(failure instanceof NullPointerException);
            assertTrue(backend.delegate.isHooked(ADD));
            assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(1, backend.installs);
            assertEquals(0, backend.uninstalls);
        } finally {
            environment.close();
        }
    }

    @Test
    public void contextFailuresAfterInstallationCloseKnownHandleAndQuarantine() throws Throwable {
        verifyContextFailure(Mode.LOADER_FAILURE, new IllegalStateException("loader"));
        verifyContextFailure(Mode.LOADER_FAILURE, new AssertionError("loader"));
        verifyContextFailure(Mode.EPOCH_FAILURE, new IllegalStateException("epoch"));
        verifyContextFailure(Mode.EPOCH_FAILURE, new SimulatedVmError());
    }

    private static void verifyContextFailure(Mode mode, Throwable original) throws Throwable {
        ControlledBackend backend = new ControlledBackend(mode, original);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = spec("post.context", callbacks);
        try {
            assertSame(original, TestSupport.capture(() -> environment.install(spec)));
            assertEquals(1, backend.uninstalls);
            assertFalse(backend.delegate.isHooked(ADD));
            assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(1, backend.installs);
        } finally {
            environment.close();
        }
        assertEquals(1, backend.uninstalls);
    }

    @Test
    public void fatalCleanupTakesPriorityOverContextRuntimeFailure() {
        IllegalStateException contextFailure = new IllegalStateException("context");
        ControlledBackend backend = new ControlledBackend(Mode.CONTEXT_CLEANUP_FATAL, contextFailure);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = spec("post.context.fatal", new AtomicInteger());
        try {
            Throwable failure = TestSupport.capture(() -> environment.install(spec));
            assertSame(backend.cleanupError, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(contextFailure, failure.getSuppressed()[0]);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
        } finally {
            environment.close();
        }
        assertEquals(1, backend.uninstalls);
    }

    @Test
    public void interruptedRollbackKeepsRegistrationQuarantinedWithoutRetryingClose() throws Throwable {
        verifyRollbackFailure(Mode.ROLLBACK_ERROR);
        verifyRollbackFailure(Mode.ROLLBACK_FATAL);
        verifyRollbackFailure(Mode.ROLLBACK_NULL_REPORT);
    }

    private static void verifyRollbackFailure(Mode mode) throws Throwable {
        ControlledBackend backend = new ControlledBackend(mode, new IllegalStateException("unused"));
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = spec("post.rollback", callbacks);
        try {
            if (mode == Mode.ROLLBACK_NULL_REPORT) {
                RegistrationReceipt receipt = environment.install(spec);
                assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, receipt.status());
                assertEquals(1, receipt.targets().size());
                assertEquals(TargetInstallReceipt.Status.ROLLED_BACK, receipt.targets().get(0).status());
                assertTrue(receipt.message(), receipt.message().contains("entry=UNKNOWN"));
                assertTrue(receipt.targets().get(0).message(),
                        receipt.targets().get(0).message().contains("entry=UNKNOWN"));
            } else {
                Throwable failure = TestSupport.capture(() -> environment.install(spec));
                assertSame(backend.cleanupError, failure);
            }
            assertTrue(backend.delegate.isHooked(ADD));
            assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(1, backend.installs);
            assertEquals(1, backend.uninstalls);
        } finally {
            environment.close();
        }
        assertEquals("one-shot close must not be treated as an uninstall retry", 1, backend.uninstalls);
    }

    private static HookSpec spec(String id, AtomicInteger callbacks) {
        return TestSupport.spec(id, ADD).before(invocation -> callbacks.incrementAndGet()).build();
    }

    private enum Mode { NULL_HANDLE, LOADER_FAILURE, EPOCH_FAILURE, CONTEXT_CLEANUP_FATAL,
        ROLLBACK_ERROR, ROLLBACK_FATAL, ROLLBACK_NULL_REPORT }

    private static final class ControlledBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final Mode mode;
        final Throwable contextFailure;
        final Error cleanupError;
        boolean failIdentityRead;
        int installs;
        int uninstalls;
        final BackendContext context = new BackendContext() {
            @Override public String processName() { return delegate.context().processName(); }
            @Override public ClassLoader classLoader() {
                if (failIdentityRead && mode != Mode.EPOCH_FAILURE) throwContextFailure();
                return delegate.context().classLoader();
            }
            @Override public long codeEpoch() {
                if (failIdentityRead && mode == Mode.EPOCH_FAILURE) throwContextFailure();
                return delegate.context().codeEpoch();
            }
        };

        ControlledBackend(Mode mode, Throwable contextFailure) {
            this.mode = mode;
            this.contextFailure = contextFailure;
            this.cleanupError = mode == Mode.ROLLBACK_FATAL || mode == Mode.CONTEXT_CLEANUP_FATAL
                    ? new SimulatedVmError() : new AssertionError("cleanup");
            delegate.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        }

        private void throwContextFailure() {
            failIdentityRead = false;
            if (contextFailure instanceof Error) throw (Error) contextFailure;
            throw (RuntimeException) contextFailure;
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return context; }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            installs++;
            BackendHandle handle = delegate.install(plan, channel);
            if (mode == Mode.NULL_HANDLE) return null;
            if (mode == Mode.LOADER_FAILURE || mode == Mode.EPOCH_FAILURE || mode == Mode.CONTEXT_CLEANUP_FATAL) {
                failIdentityRead = true;
            } else {
                delegate.bumpCodeEpoch();
            }
            return () -> {
                uninstalls++;
                if (mode == Mode.ROLLBACK_ERROR || mode == Mode.ROLLBACK_FATAL || mode == Mode.CONTEXT_CLEANUP_FATAL) {
                    throw cleanupError;
                }
                if (mode == Mode.ROLLBACK_NULL_REPORT) return null;
                return handle.uninstall();
            };
        }
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
