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
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Simulated publication faults occur after the backend has already committed a replacement. */
public class ReplacementPublicationFailureTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void publicationFailureBeforeOrAfterPutQuarantinesBothGenerations() throws Throwable {
        for (boolean writeFirst : new boolean[]{false, true}) {
            for (boolean useError : new boolean[]{false, true}) {
                Throwable failure = useError ? new AssertionError("publication") : new IllegalStateException("publication");
                CapturingBackend backend = new CapturingBackend();
                HookEnvironment environment = HookEnvironment.create(backend);
                AtomicInteger callbacks = new AtomicInteger();
                HookSpec oldSpec = spec("publication.failure", callbacks, 10);
                HookSpec newSpec = spec("publication.failure", callbacks, 20);
                try {
                    HookRegistration old = TestSupport.installOrFail(environment, oldSpec);
                    FaultMap map = injectFaultMap(environment, failure, writeFirst, null, null);
                    map.armed = true;
                    assertSame(failure, TestSupport.capture(() -> environment.replace(newSpec)));
                    assertQuarantined(environment, backend, old, newSpec, callbacks);
                } finally {
                    environment.close();
                }
                assertEquals("quarantine must not unhook either possibly invalid handle", 0, backend.uninstalls.get());
                assertTrue(backend.delegate.isHooked(ADD));
            }
        }
    }

    @Test
    public void directOldCloseWaitsForPublicationFailureAndReportsUnknown() throws Exception {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec oldSpec = spec("publication.race", callbacks, 10);
        HookSpec newSpec = spec("publication.race", callbacks, 20);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        RuntimeException failure = new IllegalStateException("publication after put");
        AtomicReference<Throwable> replacementFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<Throwable>();
        AtomicReference<CloseReport> closeReport = new AtomicReference<CloseReport>();
        Thread replacing = null;
        Thread closing = null;
        try {
            HookRegistration old = TestSupport.installOrFail(environment, oldSpec);
            FaultMap map = injectFaultMap(environment, failure, true, entered, release);
            map.armed = true;
            replacing = new Thread(() -> replacementFailure.set(TestSupport.capture(() -> environment.replace(newSpec))));
            replacing.start();
            assertTrue("publication was reached after backend replacement", entered.await(5, TimeUnit.SECONDS));
            closing = new Thread(() -> {
                closeStarted.countDown();
                try {
                    closeReport.set(old.close());
                } catch (Throwable error) {
                    closeFailure.set(error);
                } finally {
                    closeFinished.countDown();
                }
            });
            closing.start();
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertFalse("old close must wait until publication is resolved", closeFinished.await(200, TimeUnit.MILLISECONDS));
            release.countDown();
            replacing.join(5000L);
            closing.join(5000L);
            assertFalse(replacing.isAlive());
            assertFalse(closing.isAlive());
            assertSame(failure, replacementFailure.get());
            assertEquals(null, closeFailure.get());
            assertNotNull(closeReport.get());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, closeReport.get().entryRestoration());
            assertQuarantined(environment, backend, old, newSpec, callbacks);
        } finally {
            release.countDown();
            if (replacing != null) replacing.join(5000L);
            if (closing != null) closing.join(5000L);
            environment.close();
        }
        assertEquals(0, backend.uninstalls.get());
    }

    private static void assertQuarantined(HookEnvironment environment, CapturingBackend backend,
                                          HookRegistration old, HookSpec retry, AtomicInteger callbacks) {
        assertEquals(RegistrationState.RETIRED_RESIDENT, old.state());
        CloseReport report = old.close();
        assertNotNull(report);
        assertTrue(report.newCallbacksRejected());
        assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
        assertNotNull(backend.oldChannel.get());
        assertNotNull(backend.newChannel.get());
        assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.oldChannel.get().dispatch(invocation()).kind());
        assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.newChannel.get().dispatch(invocation()).kind());
        assertEquals(0, callbacks.get());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(retry).status());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.replace(retry).status());
        assertEquals(1, backend.delegate.installCount());
        assertEquals(1, backend.delegate.replaceCount());
        assertEquals(0, backend.uninstalls.get());
    }

    private static HookSpec spec(String id, AtomicInteger callbacks, int value) {
        return TestSupport.spec(id, ADD).before(invocation -> {
            callbacks.incrementAndGet();
            invocation.setEarlyResult(value);
        }).build();
    }

    private static BackendInvocation invocation() {
        return new BackendInvocation() {
            @Override public Executable target() { return ADD; }
            @Override public Object thisObject() { return null; }
            @Override public Object[] arguments() { return new Object[]{1, 2}; }
            @Override public Object proceed(Object[] arguments) { return 3; }
        };
    }

    /** Reflection changes only a private instance map, avoiding a public production test API. */
    @SuppressWarnings("unchecked")
    private static FaultMap injectFaultMap(HookEnvironment environment, Throwable failure, boolean writeFirst,
                                           CountDownLatch entered, CountDownLatch release) throws Exception {
        Field field = HookEnvironment.class.getDeclaredField("registrations");
        field.setAccessible(true);
        Map<Object, Object> existing = (Map<Object, Object>) field.get(environment);
        FaultMap replacement = new FaultMap(failure, writeFirst, entered, release);
        replacement.putAll(existing);
        field.set(environment, replacement);
        return replacement;
    }

    private static final class FaultMap extends LinkedHashMap<Object, Object> {
        private static final long serialVersionUID = 1L;
        final Throwable failure;
        final boolean writeFirst;
        final transient CountDownLatch entered;
        final transient CountDownLatch release;
        volatile boolean armed;

        FaultMap(Throwable failure, boolean writeFirst, CountDownLatch entered, CountDownLatch release) {
            this.failure = failure;
            this.writeFirst = writeFirst;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public Object put(Object key, Object value) {
            if (!armed) return super.put(key, value);
            armed = false;
            if (writeFirst) super.put(key, value);
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("publication gate timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("publication gate interrupted", interrupted);
                }
            }
            if (failure instanceof Error) throw (Error) failure;
            throw (RuntimeException) failure;
        }
    }

    private static final class CapturingBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final AtomicInteger uninstalls = new AtomicInteger();
        final AtomicReference<DispatchChannel> oldChannel = new AtomicReference<DispatchChannel>();
        final AtomicReference<DispatchChannel> newChannel = new AtomicReference<DispatchChannel>();

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            oldChannel.set(channel);
            return wrap(delegate.install(plan, channel));
        }

        private BackendHandle wrap(BackendHandle handle) {
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() {
                    uninstalls.incrementAndGet();
                    return handle.uninstall();
                }

                @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                    newChannel.set(channel);
                    return wrap(handle.replace(plan, channel));
                }
            };
        }
    }
}
