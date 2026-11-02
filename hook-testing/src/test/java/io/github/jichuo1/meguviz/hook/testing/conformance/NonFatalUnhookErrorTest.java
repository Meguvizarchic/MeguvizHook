package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Later known handles are attempted after a non-fatal unhook Error. */
public class NonFatalUnhookErrorTest {
    private static final Executable FIRST = TestSupport.method("overloaded", int.class);
    private static final Executable SECOND = TestSupport.method("overloaded", String.class);
    private static final Executable THIRD = TestSupport.method("add", int.class, int.class);

    @Test
    public void errorOnFirstHandleStillClosesSecondAndRecordsUnknownRestoration() throws Throwable {
        AssertionError first = new AssertionError("first unhook");
        UnhookBackend backend = new UnhookBackend(first, null);
        HookEnvironment environment = environment(backend, FIRST, SECOND);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = spec("close.error", callbacks);
        try {
            HookRegistration registration = TestSupport.installOrFail(environment, spec);
            assertSame(first, TestSupport.capture(registration::close));
            assertEquals(2, backend.unhookAttempts);
            assertTrue(backend.delegate.isHooked(FIRST));
            assertFalse(backend.delegate.isHooked(SECOND));
            CloseReport repeated = registration.close();
            assertEquals(RegistrationState.RETIRED_RESIDENT, repeated.state());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, repeated.entryRestoration());
            assertTrue(repeated.newCallbacksRejected());
            assertTrue(repeated.message(), repeated.message().contains("first unhook"));
            assertEquals(8, backend.delegate.call(FIRST, null, 7));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(2, backend.unhookAttempts);
        } finally {
            environment.close();
        }
    }

    @Test
    public void multipleFailuresAreRetainedOnFirstErrorAfterAllHandlesAreTried() {
        AssertionError first = new AssertionError("first unhook");
        IllegalStateException second = new IllegalStateException("second unhook");
        AssertionError third = new AssertionError("third unhook");
        UnhookBackend backend = new UnhookBackend(first, second, third);
        HookEnvironment environment = environment(backend, FIRST, SECOND, THIRD);
        try {
            HookRegistration registration = TestSupport.installOrFail(environment, spec("close.errors", new AtomicInteger()));
            Throwable observed = TestSupport.capture(registration::close);
            assertSame(first, observed);
            assertEquals(3, backend.unhookAttempts);
            assertEquals(2, observed.getSuppressed().length);
            assertSame(second, observed.getSuppressed()[0]);
            assertSame(third, observed.getSuppressed()[1]);
            CloseReport report = registration.close();
            assertTrue(report.newCallbacksRejected());
            assertTrue(report.message(), report.message().contains("first unhook")
                    && report.message().contains("second unhook") && report.message().contains("third unhook"));
        } finally {
            environment.close();
        }
    }

    @Test
    public void fatalUnhookStopsImmediatelyAndDoesNotReportCompletion() {
        SimulatedVmError fatal = new SimulatedVmError();
        UnhookBackend backend = new UnhookBackend(fatal, null);
        HookEnvironment environment = environment(backend, FIRST, SECOND);
        HookSpec spec = spec("close.fatal", new AtomicInteger());
        try {
            HookRegistration registration = TestSupport.installOrFail(environment, spec);
            assertSame(fatal, TestSupport.capture(registration::close));
            assertEquals(1, backend.unhookAttempts);
            assertEquals(RegistrationState.RETIRING, registration.state());
            assertTrue(backend.delegate.isHooked(SECOND));
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
        } finally {
            environment.close();
        }
    }

    @Test
    public void incompleteBackendCloseReportIsUnknownAndDoesNotSkipRemainingHandles() throws Throwable {
        for (MissingReport missing : MissingReport.values()) {
            UnhookBackend backend = new UnhookBackend(missing, null);
            HookEnvironment environment = environment(backend, FIRST, SECOND);
            AtomicInteger callbacks = new AtomicInteger();
            HookSpec spec = spec("close.missing." + missing.name(), callbacks);
            try {
                HookRegistration registration = TestSupport.installOrFail(environment, spec);
                CloseReport report = registration.close();
                assertEquals(2, backend.unhookAttempts);
                assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
                assertEquals(CloseReport.PhysicalRelease.UNKNOWN, report.physicalRelease());
                assertEquals(RegistrationState.RETIRED_RESIDENT, report.state());
                assertTrue(report.newCallbacksRejected());
                assertTrue(report.message(), report.message().contains("incomplete report"));
                assertTrue(backend.delegate.isHooked(FIRST));
                assertFalse(backend.delegate.isHooked(SECOND));
                assertEquals(8, backend.delegate.call(FIRST, null, 7));
                assertEquals(0, callbacks.get());
                assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            } finally {
                environment.close();
            }
        }
    }

    private static HookEnvironment environment(UnhookBackend backend, Executable... targets) {
        return HookEnvironment.create(backend, (descriptor, loader) -> Arrays.asList(targets), DiagnosticsSink.NONE);
    }

    private static HookSpec spec(String id, AtomicInteger callbacks) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("conformance"),
                TargetSpec.descriptor(TargetKind.METHOD, "unused.test.Target", "target")
                        .match(MatchPolicy.ALL).build())
                .before(invocation -> callbacks.incrementAndGet()).build();
    }

    private enum MissingReport { REPORT, RESTORATION, PHYSICAL }

    private static final class UnhookBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final Object[] failures;
        int installations;
        int unhookAttempts;

        UnhookBackend(Object... failures) {
            this.failures = failures;
            delegate.onTarget(FIRST, (receiver, args) -> (Integer) args[0] + 1);
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            BackendHandle handle = delegate.install(plan, channel);
            Object failure = failures[installations++];
            return () -> {
                unhookAttempts++;
                if (failure instanceof Error) throw (Error) failure;
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                if (failure == MissingReport.REPORT) return null;
                if (failure == MissingReport.RESTORATION) {
                    return new BackendCloseReport(null, CloseReport.PhysicalRelease.NOT_CLAIMED, false, "missing restoration");
                }
                if (failure == MissingReport.PHYSICAL) {
                    return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED, null, false, "missing physical result");
                }
                return handle.uninstall();
            };
        }
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
