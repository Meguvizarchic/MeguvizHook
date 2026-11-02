package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Unchecked failures can follow a backend commit and must never become safe-to-retry receipts. */
public class InterruptedInstallRuntimeTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable OVERLOADED_INT = TestSupport.method("overloaded", int.class);
    private static final Executable OVERLOADED_STRING = TestSupport.method("overloaded", String.class);

    @Test
    public void firstInstallCommitThenRuntimeExceptionQuarantinesUnknownEntry() throws Throwable {
        InterruptedBackend backend = new InterruptedBackend(1, false);
        backend.delegate.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = TestSupport.spec("unknown.runtime.first", ADD)
                .before(invocation -> callbacks.incrementAndGet()).build();
        try {
            assertSame(backend.failure, TestSupport.capture(() -> environment.install(spec)));
            assertTrue("entry committed before throw remains unknown", backend.delegate.isHooked(ADD));
            assertEquals(0, backend.uninstallCalls);
            assertEquals(3, backend.delegate.call(ADD, null, 1, 2));
            assertEquals("unknown entry must reject SDK callbacks", 0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals("quarantined id must not be replayed", 1, backend.delegate.installCount());
        } finally {
            environment.close();
        }
        assertTrue("unknown handle was never falsely treated as uninstalled", backend.delegate.isHooked(ADD));
        assertEquals(0, backend.uninstallCalls);
    }

    @Test
    public void secondAllCommitThenRuntimeExceptionRetiresKnownAndQuarantinesUnknown() throws Throwable {
        InterruptedBackend backend = new InterruptedBackend(2, false);
        backend.delegate.onTarget(OVERLOADED_INT, (receiver, args) -> args[0]);
        backend.delegate.onTarget(OVERLOADED_STRING, (receiver, args) -> args[0]);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = allSpec("unknown.runtime.all", callbacks);
        try {
            assertSame(backend.failure, TestSupport.capture(() -> environment.install(spec)));
            assertEquals(2, backend.attempted.size());
            Executable known = backend.attempted.get(0);
            Executable unknown = backend.attempted.get(1);
            assertEquals("known returned handle is closed", 1, backend.uninstallCalls);
            assertFalse(backend.delegate.isHooked(known));
            assertTrue("unreturned handle must not be called or claimed removed", backend.delegate.isHooked(unknown));
            assertOriginal(backend.delegate, known);
            assertOriginal(backend.delegate, unknown);
            assertEquals("neither entry may run the retired callback", 0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, environment.install(spec).status());
            assertEquals(2, backend.delegate.installCount());
        } finally {
            environment.close();
        }
        assertEquals("closing environment must not invent an unknown handle", 1, backend.uninstallCalls);
        assertTrue(backend.delegate.isHooked(backend.attempted.get(1)));
    }

    @Test
    public void checkedUncommittedFailureStillReturnsPartialSuccess() throws Throwable {
        InterruptedBackend backend = new InterruptedBackend(2, true);
        backend.delegate.onTarget(OVERLOADED_INT, (receiver, args) -> args[0]);
        backend.delegate.onTarget(OVERLOADED_STRING, (receiver, args) -> args[0]);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            RegistrationReceipt receipt = environment.install(allSpec("checked.partial", callbacks));
            assertEquals(RegistrationReceipt.Status.PARTIALLY_INSTALLED, receipt.status());
            assertNotNull(receipt.registration());
            assertEquals(2, receipt.targets().size());
            assertEquals(TargetInstallReceipt.Status.INSTALLED, receipt.targets().get(0).status());
            assertEquals(TargetInstallReceipt.Status.FAILED, receipt.targets().get(1).status());
            assertEquals(1, backend.delegate.installCount());
            assertOriginal(backend.delegate, backend.attempted.get(0));
            assertEquals(1, callbacks.get());
            receipt.registration().close();
            assertEquals(1, backend.uninstallCalls);
        } finally {
            environment.close();
        }
    }

    private static HookSpec allSpec(String id, AtomicInteger callbacks) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .match(MatchPolicy.ALL).build();
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("conformance"), target)
                .before(invocation -> callbacks.incrementAndGet()).build();
    }

    private static void assertOriginal(FakeBackend backend, Executable target) throws Throwable {
        Object input = target.getParameterTypes()[0] == int.class ? Integer.valueOf(7) : "original";
        assertEquals(input, backend.call(target, null, input));
        assertEquals(1, backend.originalCallCount(target));
    }

    private static final class InterruptedBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final RuntimeException failure = new IllegalStateException("backend committed then failed");
        final List<Executable> attempted = new ArrayList<Executable>();
        final int failAt;
        final boolean checkedFailure;
        int uninstallCalls;

        InterruptedBackend(int failAt, boolean checkedFailure) {
            this.failAt = failAt;
            this.checkedFailure = checkedFailure;
        }

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override
        public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            attempted.add(plan.target());
            if (attempted.size() == failAt && checkedFailure) {
                throw new BackendInstallException("no entry installed by this attempt");
            }
            BackendHandle handle = delegate.install(plan, channel);
            if (attempted.size() == failAt) {
                throw failure;
            }
            return () -> {
                uninstallCalls++;
                return handle.uninstall();
            };
        }
    }
}
