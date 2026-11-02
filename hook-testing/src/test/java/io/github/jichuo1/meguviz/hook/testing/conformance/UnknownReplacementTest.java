package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.SuccessResultDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Unknown replacement results never masquerade as a retained old registration. */
public class UnknownReplacementTest {
    private static final Executable TARGET = TestSupport.method("add", int.class, int.class);

    @Test
    public void committedThenRuntimeErrorOrNullRejectsBothChannelsAndQuarantinesId() throws Throwable {
        verifyUnknown(Mode.RUNTIME);
        verifyUnknown(Mode.ERROR);
        verifyUnknown(Mode.NULL);
        verifyUnknown(Mode.FATAL);
    }

    private static void verifyUnknown(Mode mode) throws Throwable {
        UnknownBackend backend = new UnknownBackend(mode);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger oldCallbacks = new AtomicInteger();
        AtomicInteger newCallbacks = new AtomicInteger();
        HookSpec oldSpec = spec("replace.unknown", 99, oldCallbacks);
        HookSpec newSpec = spec("replace.unknown", 100, newCallbacks);
        try {
            HookRegistration old = TestSupport.installOrFail(environment, oldSpec);
            assertEquals(99, backend.delegate.call(TARGET, null, 1, 2));
            Throwable actual = TestSupport.capture(() -> environment.replace(newSpec));
            if (mode == Mode.NULL) {
                assertTrue(actual instanceof IllegalStateException);
                assertTrue(actual.getMessage(), actual.getMessage().contains("outcome is unknown"));
            } else {
                assertSame(backend.failure, actual);
            }
            assertEquals(RegistrationState.RETIRED_RESIDENT, old.state());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.oldChannel.dispatch(invocation()).kind());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.newChannel.dispatch(invocation()).kind());
            assertEquals(3, backend.delegate.call(TARGET, null, 1, 2));
            assertEquals(1, oldCallbacks.get());
            assertEquals(0, newCallbacks.get());
            CloseReport report = old.close();
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
            assertEquals(CloseReport.PhysicalRelease.UNKNOWN, report.physicalRelease());
            assertTrue(report.newCallbacksRejected());
            assertEquals(0, backend.unhooks);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(oldSpec).status());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.replace(newSpec).status());
            assertEquals(1, backend.replacements);
        } finally {
            environment.close();
        }
        assertEquals("unknown handles must not be unhooked by environment.close", 0, backend.unhooks);
    }

    @Test
    public void committedUnknownSuccessReplacementRetiresBothGenerations() throws Throwable {
        for (Mode mode : new Mode[]{Mode.RUNTIME, Mode.ERROR, Mode.NULL, Mode.FATAL}) {
            verifyUnknownSuccess(mode);
        }
    }

    private static void verifyUnknownSuccess(Mode mode) throws Throwable {
        UnknownBackend backend = new UnknownBackend(mode);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec oldSpec = TestSupport.spec("replace.unknown.success", TARGET).replaceResultOnSuccess(99).build();
        HookSpec newSpec = TestSupport.spec("replace.unknown.success", TARGET).replaceResultOnSuccess(100).build();
        SuccessResultDispatchChannel oldChannel = null;
        boolean entered = false;
        try {
            HookRegistration old = TestSupport.installOrFail(environment, oldSpec);
            assertTrue(backend.oldChannel instanceof SuccessResultDispatchChannel);
            oldChannel = (SuccessResultDispatchChannel) backend.oldChannel;
            assertTrue(oldChannel.requiresInvocation());
            assertEquals(99, backend.delegate.call(TARGET, null, 1, 2));
            assertTrue(oldChannel.tryEnter());
            entered = true;

            Throwable actual = TestSupport.capture(() -> environment.replace(newSpec));
            if (mode == Mode.NULL) {
                assertTrue(actual instanceof IllegalStateException);
                assertTrue(actual.getMessage(), actual.getMessage().contains("outcome is unknown"));
            } else {
                assertSame(backend.failure, actual);
            }
            assertTrue(backend.newChannel instanceof SuccessResultDispatchChannel);
            SuccessResultDispatchChannel newChannel = (SuccessResultDispatchChannel) backend.newChannel;
            assertTrue(newChannel.requiresInvocation());
            assertEquals(RegistrationState.RETIRED_RESIDENT, old.state());
            assertFalse(oldChannel.tryEnter());
            assertFalse(newChannel.tryEnter());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, oldChannel.dispatch(invocation()).kind());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, newChannel.dispatch(invocation()).kind());
            assertEquals("committed new entry must reach original without a success replacement",
                    3, backend.delegate.call(TARGET, null, 1, 2));
            assertEquals(2, backend.delegate.originalCallCount(TARGET));

            CloseReport report = old.close();
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
            assertEquals(CloseReport.PhysicalRelease.UNKNOWN, report.physicalRelease());
            assertTrue(report.newCallbacksRejected());
            assertEquals("the previously admitted old lease remains visible", 1, report.inFlightAtClose());
            assertEquals(0, backend.unhooks);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(oldSpec).status());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.replace(newSpec).status());
            assertEquals(1, backend.replacements);
        } finally {
            if (entered) oldChannel.exit();
            environment.close();
        }
        assertEquals("unknown handles must not be unhooked by environment.close", 0, backend.unhooks);
    }

    @Test
    public void checkedPrecommitFailureKeepsOldGenerationAndAllowsNormalClose() throws Throwable {
        UnknownBackend backend = new UnknownBackend(Mode.CHECKED);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger oldCallbacks = new AtomicInteger();
        try {
            HookRegistration old = TestSupport.installOrFail(environment, spec("replace.checked", 99, oldCallbacks));
            RegistrationReceipt result = environment.replace(spec("replace.checked", 100, new AtomicInteger()));
            assertEquals(RegistrationReceipt.Status.FAILED, result.status());
            assertEquals(RegistrationState.INSTALLED, old.state());
            assertEquals(99, backend.delegate.call(TARGET, null, 1, 2));
            assertEquals(1, oldCallbacks.get());
            assertEquals(CloseReport.EntryRestoration.RESTORED, old.close().entryRestoration());
            assertEquals(1, backend.unhooks);
        } finally {
            environment.close();
        }
    }

    @Test
    public void concurrentOldCloseCannotUnhookAfterUnknownReplacement() throws Exception {
        UnknownBackend backend = new UnknownBackend(Mode.RUNTIME);
        backend.entered = new CountDownLatch(1);
        backend.release = new CountDownLatch(1);
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            HookRegistration old = TestSupport.installOrFail(environment,
                    spec("replace.unknown.race", 99, new AtomicInteger()));
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            AtomicReference<CloseReport> closeReport = new AtomicReference<CloseReport>();
            Thread replacing = new Thread(() -> failure.set(TestSupport.capture(() ->
                    environment.replace(spec("replace.unknown.race", 100, new AtomicInteger())))));
            replacing.start();
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            CountDownLatch started = new CountDownLatch(1);
            Thread closing = new Thread(() -> {
                started.countDown();
                closeReport.set(old.close());
            });
            closing.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            backend.release.countDown();
            replacing.join(5000);
            closing.join(5000);
            assertFalse(replacing.isAlive());
            assertFalse(closing.isAlive());
            assertSame(backend.failure, failure.get());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, closeReport.get().entryRestoration());
            assertEquals(0, backend.unhooks);
        } finally {
            backend.release.countDown();
            environment.close();
        }
    }

    private static HookSpec spec(String id, int result, AtomicInteger callbacks) {
        return TestSupport.spec(id, TARGET).before(invocation -> {
            callbacks.incrementAndGet();
            invocation.setEarlyResult(result);
        }).build();
    }

    private static BackendInvocation invocation() {
        return new BackendInvocation() {
            @Override public Executable target() { return TARGET; }
            @Override public Object thisObject() { return null; }
            @Override public Object[] arguments() { return new Object[]{1, 2}; }
            @Override public Object proceed(Object[] args) { throw new AssertionError("passthrough must not call proceed"); }
        };
    }

    private enum Mode { CHECKED, RUNTIME, ERROR, NULL, FATAL }

    private static final class UnknownBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final Mode mode;
        final Throwable failure;
        int unhooks;
        int replacements;
        DispatchChannel oldChannel;
        DispatchChannel newChannel;
        CountDownLatch entered;
        CountDownLatch release;

        UnknownBackend(Mode mode) {
            this.mode = mode;
            this.failure = mode == Mode.FATAL ? new SimulatedVmError()
                    : mode == Mode.ERROR ? new AssertionError("replace committed")
                    : new IllegalStateException("replace outcome unknown");
            delegate.onTarget(TARGET, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            oldChannel = channel;
            BackendHandle original = delegate.install(plan, channel);
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() { unhooks++; return original.uninstall(); }
                @Override public BackendHandle replace(BoundPlan replacement, DispatchChannel channel)
                        throws BackendInstallException {
                    replacements++;
                    if (mode == Mode.CHECKED) throw new BackendInstallException("definitely not committed");
                    newChannel = channel;
                    original.replace(replacement, channel); // Commit, then make the result unknowable to the core.
                    if (entered != null) {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timeout");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                    if (mode == Mode.NULL) return null;
                    if (failure instanceof Error) throw (Error) failure;
                    throw (RuntimeException) failure;
                }
            };
        }
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
