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
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.FusedConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Bounded JVM interleavings for the registration gate, not a framework or JMM proof. */
public class AtomicGateLifecycleContractTest {
    public static final class Targets {
        public static String target() { return "original"; }
        public static String overloaded() { return "original"; }
        public static String overloaded(int ignored) { return "original"; }
    }

    private static HookSpec single(String id, String value) throws Exception {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("gate-test"),
                TargetSpec.ofMember(Targets.class.getMethod("target")))
                .replaceConstant(value).build();
    }

    private static final class CapturingBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final List<FusedConstantDispatchChannel> channels = new ArrayList<FusedConstantDispatchChannel>();
        final AtomicReference<FusedConstantDispatchChannel> replacement = new AtomicReference<FusedConstantDispatchChannel>();
        final CountDownLatch uninstallEntered = new CountDownLatch(1);
        final CountDownLatch allowUninstall = new CountDownLatch(1);
        final RuntimeException unknown = new IllegalStateException("replacement outcome unknown");
        boolean holdUninstall;
        boolean failReplacement;

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            channels.add((FusedConstantDispatchChannel) channel);
            BackendHandle original = delegate.install(plan, channel);
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() {
                    if (holdUninstall) {
                        uninstallEntered.countDown();
                        try {
                            if (!allowUninstall.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("uninstall was not released");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    }
                    return original.uninstall();
                }

                @Override public BackendHandle replace(BoundPlan next, DispatchChannel nextChannel)
                        throws BackendInstallException {
                    replacement.set((FusedConstantDispatchChannel) nextChannel);
                    if (failReplacement) throw unknown;
                    return original.replace(next, nextChannel);
                }
            };
        }
    }

    @Test public void constantChannelRetainsItsOwningRegistration() throws Exception {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        HookRegistration registration = TestSupport.installOrFail(env, single("gate.owner", "fixed"));
        try {
            FusedConstantDispatchChannel channel = backend.channels.get(0);
            Field owner = channel.getClass().getDeclaredField("this$0");
            owner.setAccessible(true);
            assertSame(registration, owner.get(channel));
            assertEquals("fixed", channel.constantValue());
        } finally {
            env.close();
        }
    }

    @Test public void closeSamplesAfterUninstallWhileRetiredCallsAreRejected() throws Exception {
        CapturingBackend backend = new CapturingBackend();
        backend.holdUninstall = true;
        HookEnvironment env = HookEnvironment.create(backend);
        HookRegistration registration = TestSupport.installOrFail(env, single("gate.uninstall", "fixed"));
        FusedConstantDispatchChannel channel = backend.channels.get(0);
        assertTrue(channel.tryEnter());
        assertTrue(channel.tryEnter());
        AtomicReference<CloseReport> report = new AtomicReference<CloseReport>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread closer = new Thread(() -> {
            try { report.set(registration.close()); }
            catch (Throwable error) { failure.set(error); }
        }, "gate-close");
        closer.setDaemon(true);
        boolean oneHeld = true;
        try {
            closer.start();
            assertTrue(backend.uninstallEntered.await(2, TimeUnit.SECONDS));
            assertEquals(RegistrationState.RETIRING, registration.state());
            for (int i = 0; i < 1000; i++) {
                assertFalse(channel.tryEnter());
                assertSame(FusedConstantDispatchChannel.NOT_ENTERED, channel.dispatchValueOrNotEntered());
            }
            channel.exit();
            oneHeld = false;
            backend.allowUninstall.countDown();
            closer.join(2000L);
            assertFalse("close did not finish", closer.isAlive());
            assertNull(failure.get());
            assertEquals(1, report.get().inFlightAtClose());
            assertTrue(report.get().newCallbacksRejected());
            assertSame(report.get(), registration.close());
        } finally {
            backend.allowUninstall.countDown();
            if (oneHeld) channel.exit();
            channel.exit();
            closer.join(2000L);
            if (!closer.isAlive()) env.close();
        }
    }

    @Test public void multipleTargetsShareTheRegistrationCount() throws Exception {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        TargetSpec targets = TargetSpec.descriptor(TargetKind.METHOD, Targets.class.getName(), "overloaded")
                .match(MatchPolicy.ALL).build();
        HookSpec spec = new HookSpec.Builder(HookId.of("gate.all"), HookOwner.of("gate-test"), targets)
                .replaceConstant("fixed").build();
        HookRegistration registration = TestSupport.installOrFail(env, spec);
        assertEquals(2, backend.channels.size());
        FusedConstantDispatchChannel first = backend.channels.get(0);
        FusedConstantDispatchChannel second = backend.channels.get(1);
        assertTrue(first.tryEnter());
        assertTrue(second.tryEnter());
        try {
            CloseReport report = registration.close();
            assertEquals(2, report.inFlightAtClose());
            assertFalse(first.tryEnter());
            assertFalse(second.tryEnter());
        } finally {
            first.exit();
            second.exit();
            env.close();
        }
    }

    @Test public void unknownReplacementRetiresBothGatesAndPreservesOldLease() throws Exception {
        CapturingBackend backend = new CapturingBackend();
        backend.failReplacement = true;
        HookEnvironment env = HookEnvironment.create(backend);
        HookSpec initial = single("gate.unknown", "first");
        HookRegistration oldRegistration = TestSupport.installOrFail(env, initial);
        FusedConstantDispatchChannel old = backend.channels.get(0);
        assertTrue(old.tryEnter());
        try {
            try {
                env.replace(single("gate.unknown", "second"));
                fail("unknown replacement must propagate");
            } catch (RuntimeException failure) {
                assertSame(backend.unknown, failure);
            }
            FusedConstantDispatchChannel next = backend.replacement.get();
            assertNotNull(next);
            assertSame(FusedConstantDispatchChannel.NOT_ENTERED, old.dispatchValueOrNotEntered());
            assertSame(FusedConstantDispatchChannel.NOT_ENTERED, next.dispatchValueOrNotEntered());
            CloseReport report = oldRegistration.close();
            assertEquals(RegistrationState.RETIRED_RESIDENT, report.state());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
            assertEquals(1, report.inFlightAtClose());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(initial).status());
        } finally {
            old.exit();
            env.close();
        }
    }

    /**
     * Admission is decided by registration state, never by the count. A wrapped shared word cannot
     * arise from real leases; the report then saturates instead of exposing a negative count.
     */
    @Test public void countWrapKeepsStateAndNonNegativeReport() throws Exception {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        HookRegistration registration = TestSupport.installOrFail(env, single("gate.wrap", "fixed"));
        FusedConstantDispatchChannel channel = backend.channels.get(0);
        Field field = registration.getClass().getDeclaredField("inFlight");
        field.setAccessible(true);
        // The shared word of the contention-adaptive counter is its inherited AtomicInteger value.
        AtomicInteger gate = (AtomicInteger) field.get(registration);
        // Synthetic seed; no real leases correspond to the injected count.
        gate.set(Integer.MAX_VALUE);
        boolean entered = false;
        try {
            assertTrue(channel.tryEnter());
            entered = true;
            assertEquals("the lease itself stays on the shared word", Integer.MIN_VALUE, gate.get());
            assertEquals(RegistrationState.INSTALLED, registration.state());
            CloseReport report = registration.close();
            assertEquals(0, report.inFlightAtClose());
            assertEquals(RegistrationState.CLOSED, report.state());
            assertFalse(channel.tryEnter());
            assertEquals(Integer.MIN_VALUE, gate.get());
            channel.exit();
            entered = false;
            assertEquals(RegistrationState.CLOSED, registration.state());
        } finally {
            if (entered) channel.exit();
            gate.set(0);
            env.close();
        }
    }
}
