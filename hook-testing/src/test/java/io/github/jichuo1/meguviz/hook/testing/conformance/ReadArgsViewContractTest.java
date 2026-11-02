package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** READ_ARGS_VIEW is opt-in borrowed read access, not an entry snapshot or a zero-copy promise. */
public class ReadArgsViewContractTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    private static final class CapturingBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        DispatchChannel channel;
        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel value) throws BackendInstallException {
            channel = value;
            return delegate.install(plan, value);
        }
    }

    @Test public void oldBackendFallsBackToArgumentsOnceAndKeepsOriginalArgumentsForProceed() {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger materializations = new AtomicInteger();
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicReference<Object> afterValue = new AtomicReference<Object>();
        Object[] incoming = {1, 2};
        try {
            RegistrationReceipt receipt = env.install(TestSupport.spec("view.fallback", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW)
                    .before(i -> {
                        ReadArgsInvocation view = (ReadArgsInvocation) i;
                        assertEquals(2, view.argumentCount());
                        assertEquals(int.class, view.argumentType(0));
                        assertEquals(1, view.getArgument(0));
                    })
                    .after((i, result) -> afterValue.set(((ReadArgsInvocation) i).getArgument(0)))
                    .build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            BackendInvocation oldBackendInvocation = new BackendInvocation() {
                @Override public Executable target() { return ADD; }
                @Override public Object thisObject() { return null; }
                @Override public Object[] arguments() { materializations.incrementAndGet(); return incoming; }
                @Override public Object proceed(Object[] arguments) {
                    assertNull("view mode must preserve the original downstream arguments", arguments);
                    originalCalls.incrementAndGet();
                    incoming[0] = 9;
                    return 11;
                }
            };
            DispatchResult result = backend.channel.dispatch(oldBackendInvocation);
            assertEquals(DispatchResult.Kind.RETURNED, result.kind());
            assertEquals(11, result.value());
            assertEquals("default fallback copies slots before downstream mutation", 1, afterValue.get());
            assertEquals(1, materializations.get());
            assertEquals(1, originalCalls.get());
        } finally { env.close(); }
    }

    @Test public void explicitViewIsAcquiredOnceAndReusedAcrossBeforeAndAfterWithoutArray() {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger reads = new AtomicInteger();
        List<Object> slots = Collections.unmodifiableList(Arrays.<Object>asList(3, 4));
        try {
            RegistrationReceipt receipt = env.install(TestSupport.spec("view.direct", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW)
                    .before(i -> {
                        assertEquals(3, ((ReadArgsInvocation) i).getArgument(0));
                        assertThrows(HookContractException.class,
                                () -> ((MutableArgsInvocation) i).setArgument(0, 99));
                    })
                    .after((i, r) -> assertEquals(3, ((ReadArgsInvocation) i).getArgument(0)))
                    .build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            BackendInvocation direct = new BackendInvocation() {
                @Override public Executable target() { return ADD; }
                @Override public Object thisObject() { return null; }
                @Override public Object[] arguments() { throw new AssertionError("array conversion is not allowed here"); }
                @Override public List<?> readOnlyArguments() { reads.incrementAndGet(); return slots; }
                @Override public Object proceed(Object[] args) {
                    assertNull(args);
                    return 12;
                }
            };
            DispatchResult result = backend.channel.dispatch(direct);
            assertEquals(DispatchResult.Kind.RETURNED, result.kind());
            assertEquals(12, result.value());
            assertEquals(1, reads.get());
        } finally { env.close(); }
    }

    @Test public void borrowedViewCannotCrossThreadsOrOutliveItsCallback() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 3);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<ReadArgsInvocation> first = new AtomicReference<ReadArgsInvocation>();
        AtomicReference<Throwable> crossThread = new AtomicReference<Throwable>();
        AtomicReference<Throwable> expiredBefore = new AtomicReference<Throwable>();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("view.lifetime", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW)
                    .before(i -> {
                        ReadArgsInvocation view = (ReadArgsInvocation) i;
                        first.set(view);
                        Thread other = new Thread(() -> crossThread.set(TestSupport.capture(() -> view.getArgument(0))));
                        other.start();
                        try { other.join(5000L); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        assertFalse(other.isAlive());
                    })
                    .after((i, r) -> {
                        expiredBefore.set(TestSupport.capture(() -> first.get().getArgument(0)));
                        assertEquals(1, ((ReadArgsInvocation) i).getArgument(0));
                    }).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertTrue(crossThread.get() instanceof HookContractException);
            assertTrue(expiredBefore.get() instanceof HookContractException);
            assertThrows(HookContractException.class, () -> first.get().getArgument(0));
        } finally { env.close(); }
    }

    @Test public void reentrantCallsKeepIndependentBorrowedViews() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        List<String> events = new ArrayList<String>();
        backend.onTarget(ADD, (self, args) -> {
            int first = (Integer) args[0];
            if (first == 1) assertEquals(22, backend.call(ADD, null, 2, 20));
            return first + (Integer) args[1];
        });
        try {
            TestSupport.installOrFail(env, TestSupport.spec("view.reentry", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW)
                    .before(i -> events.add("before:" + ((ReadArgsInvocation) i).getArgument(0)))
                    .after((i, r) -> events.add("after:" + ((ReadArgsInvocation) i).getArgument(0)))
                    .build());
            assertEquals(11, backend.call(ADD, null, 1, 10));
            assertEquals(Arrays.asList("before:1", "before:2", "after:2", "after:1"), events);
            assertEquals(2, backend.argumentMaterializationCount());
        } finally { env.close(); }
    }
}
