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
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Publication failures must not orphan an installed callback or permit a silent retry. */
public class InstallPublicationFailureTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void publicationBeforeOrAfterMapWriteClosesAndQuarantines() throws Throwable {
        for (boolean writeFirst : new boolean[]{false, true}) {
            for (boolean useError : new boolean[]{false, true}) {
                Throwable failure = useError ? new AssertionError("publish") : new IllegalStateException("publish");
                ControlledBackend backend = new ControlledBackend(Cleanup.NORMAL);
                HookEnvironment environment = HookEnvironment.create(backend);
                AtomicInteger callbacks = new AtomicInteger();
                HookSpec spec = TestSupport.spec("install.publish", ADD).before(invocation -> callbacks.incrementAndGet()).build();
                try {
                    injectMap(environment, failure, writeFirst);
                    assertSame(failure, TestSupport.capture(() -> environment.install(spec)));
                    assertEquals(1, backend.installs);
                    assertEquals(1, backend.uninstalls);
                    assertFalse(backend.delegate.isHooked(ADD));
                    assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
                    assertEquals(0, callbacks.get());
                    assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
                    assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.replace(spec).status());
                    assertEquals(1, backend.installs);
                } finally {
                    environment.close();
                }
                assertEquals("close remains one-shot", 1, backend.uninstalls);
            }
        }
    }

    @Test
    public void unknownCleanupLeavesRetiredChannelAndIdQuarantined() throws Throwable {
        ControlledBackend backend = new ControlledBackend(Cleanup.UNKNOWN);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("install.publish.unknown", ADD).before(invocation -> invocation.setEarlyResult(99)).build();
        RuntimeException failure = new IllegalStateException("publish");
        try {
            injectMap(environment, failure, false);
            assertSame(failure, TestSupport.capture(() -> environment.install(spec)));
            assertTrue(backend.delegate.isHooked(ADD));
            assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
            RegistrationReceipt retry = environment.install(spec);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, retry.status());
            assertTrue(retry.message(), retry.message().contains("UNKNOWN"));
            assertEquals(1, backend.uninstalls);
        } finally {
            environment.close();
        }
        assertEquals(1, backend.uninstalls);
    }

    @Test
    public void fatalCleanupOverridesNonfatalPublicationFailure() throws Exception {
        ControlledBackend backend = new ControlledBackend(Cleanup.FATAL);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("install.publish.fatal", ADD).before(invocation -> { }).build();
        RuntimeException failure = new IllegalStateException("publish");
        try {
            injectMap(environment, failure, true);
            Throwable thrown = TestSupport.capture(() -> environment.install(spec));
            assertSame(backend.fatal, thrown);
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(failure, thrown.getSuppressed()[0]);
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(1, backend.uninstalls);
        } finally {
            environment.close();
        }
        assertEquals(1, backend.uninstalls);
    }

    @Test
    public void partialInstallPublicationFailureAlsoClosesSuccessfulSibling() throws Throwable {
        ControlledBackend backend = new ControlledBackend(Cleanup.NORMAL);
        backend.delegate.withFailingNextInstall();
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = new HookSpec.Builder(HookId.of("install.publish.partial"), HookOwner.of("conformance"),
                TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                        .match(MatchPolicy.ALL).build()).before(invocation -> callbacks.incrementAndGet()).build();
        try {
            RuntimeException failure = new IllegalStateException("partial publish");
            injectMap(environment, failure, true);
            assertSame(failure, TestSupport.capture(() -> environment.install(spec)));
            assertEquals(2, backend.installs);
            assertEquals(1, backend.uninstalls);
            assertFalse(backend.delegate.isHooked(backend.lastInstalled));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
        } finally {
            environment.close();
        }
    }

    @Test
    public void successfulPublicationClearsGuardAndPreservesIdempotency() {
        ControlledBackend backend = new ControlledBackend(Cleanup.NORMAL);
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("install.publish.success", ADD).before(invocation -> { }).build();
        try {
            assertTrue(environment.install(spec).isInstalled());
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, environment.install(spec).status());
            assertEquals(1, backend.installs);
        } finally {
            environment.close();
        }
    }

    private enum Cleanup { NORMAL, UNKNOWN, FATAL }

    private static final class ControlledBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final Cleanup cleanup;
        final Error fatal = new SimulatedVmError();
        int installs;
        int uninstalls;
        Executable lastInstalled;

        ControlledBackend(Cleanup cleanup) {
            this.cleanup = cleanup;
            delegate.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            installs++;
            BackendHandle handle = delegate.install(plan, channel);
            lastInstalled = plan.target();
            return () -> {
                uninstalls++;
                if (cleanup == Cleanup.FATAL) throw fatal;
                if (cleanup == Cleanup.UNKNOWN) return new BackendCloseReport(CloseReport.EntryRestoration.UNKNOWN,
                        CloseReport.PhysicalRelease.UNKNOWN, false, "entry unknown");
                return handle.uninstall();
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static void injectMap(HookEnvironment environment, Throwable failure, boolean writeFirst) throws Exception {
        Field field = HookEnvironment.class.getDeclaredField("registrations");
        field.setAccessible(true);
        Map<Object, Object> old = (Map<Object, Object>) field.get(environment);
        FaultMap map = new FaultMap(failure, writeFirst);
        map.putAll(old);
        field.set(environment, map);
    }

    private static final class FaultMap extends LinkedHashMap<Object, Object> {
        private static final long serialVersionUID = 1L;
        private final Throwable failure;
        private final boolean writeFirst;
        private boolean armed = true;
        FaultMap(Throwable failure, boolean writeFirst) { this.failure = failure; this.writeFirst = writeFirst; }
        @Override public Object put(Object key, Object value) {
            if (!armed) return super.put(key, value);
            armed = false;
            if (writeFirst) super.put(key, value);
            if (failure instanceof Error) throw (Error) failure;
            throw (RuntimeException) failure;
        }
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
