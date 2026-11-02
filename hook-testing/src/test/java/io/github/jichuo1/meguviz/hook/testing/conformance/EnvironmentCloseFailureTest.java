package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
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

/** Failures while closing one registration must not silently leave later registrations active. */
public class EnvironmentCloseFailureTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable NO_ARGS = TestSupport.method("noArguments");

    @Test
    public void nonFatalErrorDoesNotSkipLaterRegistrationsAndRetainsUnknownHandle() throws Throwable {
        AssertionError failure = new AssertionError("first unhook failed");
        ControlledBackend backend = new ControlledBackend(failure, false);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        HookRegistration first = TestSupport.installOrFail(environment,
                TestSupport.spec("environment.close.first", ADD).before(invocation -> firstCalls.incrementAndGet()).build());
        HookRegistration second = TestSupport.installOrFail(environment,
                TestSupport.spec("environment.close.second", NO_ARGS).before(invocation -> secondCalls.incrementAndGet()).build());

        assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
        assertEquals(42L, backend.delegate.call(NO_ARGS, null));
        assertSame(failure, TestSupport.capture(environment::close));
        assertEquals(2, backend.uninstallAttempts);
        assertTrue(backend.delegate.isHooked(ADD));
        assertFalse(backend.delegate.isHooked(NO_ARGS));
        assertEquals(CloseReport.EntryRestoration.UNKNOWN, first.close().entryRestoration());
        assertEquals(RegistrationState.CLOSED, second.state());
        assertEquals("the unknown entry remains owned by the closed environment", 1, environment.activeRegistrationCount());
        assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
        assertEquals(42L, backend.delegate.call(NO_ARGS, null));
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
        environment.close();
        assertEquals("close remains one-shot and does not retry an unknown unhook", 2, backend.uninstallAttempts);
    }

    @Test
    public void runtimeExceptionAfterUnhookReportsUnknownAndBlocksSameIdReuse() throws Throwable {
        ControlledBackend backend = new ControlledBackend(new IllegalStateException("failed after unhook"), true);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("environment.close.unknown", ADD).before(invocation -> { }).build();
        HookRegistration registration = TestSupport.installOrFail(environment, spec);
        CloseReport report = registration.close();
        assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
        assertTrue(report.message(), report.message().contains("failed after unhook"));
        assertFalse("the wrapper removed the hook before reporting failure", backend.delegate.isHooked(ADD));
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
        environment.close();
        assertEquals("uncertain ownership is retained despite environment close", 1, environment.activeRegistrationCount());
        assertEquals(1, backend.uninstallAttempts);
    }

    @Test
    public void nonFatalErrorsAcrossRegistrationsAreAggregated() {
        AssertionError firstFailure = new AssertionError("first unhook");
        AssertionError secondFailure = new AssertionError("second unhook");
        ControlledBackend backend = new ControlledBackend(firstFailure, false, secondFailure);
        HookEnvironment environment = HookEnvironment.create(backend);
        TestSupport.installOrFail(environment,
                TestSupport.spec("environment.errors.first", ADD).before(invocation -> { }).build());
        TestSupport.installOrFail(environment,
                TestSupport.spec("environment.errors.second", NO_ARGS).before(invocation -> { }).build());
        Throwable observed = TestSupport.capture(environment::close);
        assertSame(firstFailure, observed);
        assertEquals(2, backend.uninstallAttempts);
        assertEquals(1, observed.getSuppressed().length);
        assertSame(secondFailure, observed.getSuppressed()[0]);
        assertEquals(2, environment.activeRegistrationCount());
        environment.close();
        assertEquals(2, backend.uninstallAttempts);
    }

    @Test
    public void fatalStopsImmediatelyWithoutClaimingRemainingRegistrationClosed() {
        SimulatedVmError fatal = new SimulatedVmError();
        ControlledBackend backend = new ControlledBackend(fatal, false);
        HookEnvironment environment = HookEnvironment.create(backend);
        TestSupport.installOrFail(environment,
                TestSupport.spec("environment.fatal.first", ADD).before(invocation -> { }).build());
        HookRegistration second = TestSupport.installOrFail(environment,
                TestSupport.spec("environment.fatal.second", NO_ARGS).before(invocation -> { }).build());
        try {
            assertSame(fatal, TestSupport.capture(environment::close));
            assertEquals(1, backend.uninstallAttempts);
            assertEquals(RegistrationState.INSTALLED, second.state());
            assertTrue(backend.delegate.isHooked(NO_ARGS));
            assertEquals(2, environment.activeRegistrationCount());
        } finally {
            // Explicitly retire the second fixture; environment close does not claim a fatal cleanup completed.
            second.close();
        }
    }

    private static final class ControlledBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final Throwable firstFailure;
        final boolean removeBeforeFailure;
        final Throwable secondFailure;
        int uninstallAttempts;

        ControlledBackend(Throwable firstFailure, boolean removeBeforeFailure) {
            this(firstFailure, removeBeforeFailure, null);
        }

        ControlledBackend(Throwable firstFailure, boolean removeBeforeFailure, Throwable secondFailure) {
            this.firstFailure = firstFailure;
            this.removeBeforeFailure = removeBeforeFailure;
            this.secondFailure = secondFailure;
            delegate.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
            delegate.onTarget(NO_ARGS, (receiver, args) -> 42L);
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            BackendHandle handle = delegate.install(plan, channel);
            return () -> {
                uninstallAttempts++;
                if (!ADD.equals(plan.target())) {
                    if (secondFailure instanceof Error) throw (Error) secondFailure;
                    if (secondFailure instanceof RuntimeException) throw (RuntimeException) secondFailure;
                    return handle.uninstall();
                }
                if (removeBeforeFailure) handle.uninstall();
                if (firstFailure instanceof Error) throw (Error) firstFailure;
                throw (RuntimeException) firstFailure;
            };
        }
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
