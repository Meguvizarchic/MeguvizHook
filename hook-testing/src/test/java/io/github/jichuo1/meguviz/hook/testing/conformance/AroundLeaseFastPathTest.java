package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Contract checks across the exclusive around callback and its downstream continuation. */
public final class AroundLeaseFastPathTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void ownerCanUseBorrowDuringProceedButOtherThreadAndLaterUseAreRejected() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<AroundInvocation> borrowed = new AtomicReference<AroundInvocation>();
        AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
        backend.onTarget(ADD, (receiver, args) -> {
            assertEquals(CallOutcome.State.RUNNING, borrowed.get().proceedState());
            assertEquals(1, borrowed.get().getArgument(0));
            Thread other = new Thread(() -> wrongThread.set(TestSupport.capture(() -> borrowed.get().target())));
            other.start();
            other.join(5000);
            assertFalse("cross-thread check did not finish", other.isAlive());
            return 3;
        });
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("around.owner.lease", ADD)
                    .around(invocation -> {
                        borrowed.set(invocation);
                        assertEquals(3, invocation.proceed().value());
                        assertTrue(TestSupport.capture(invocation::proceed) instanceof HookContractException);
                        assertEquals(CallOutcome.State.RETURNED, invocation.proceedState());
                    }).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(1, backend.originalCallCount(ADD));
            assertTrue(wrongThread.get() instanceof HookContractException);
            assertTrue(TestSupport.capture(() -> borrowed.get().target()) instanceof HookContractException);
            assertTrue(TestSupport.capture(() -> borrowed.get().proceed()) instanceof HookContractException);
        } finally { environment.close(); }
    }

    @Test public void closeDuringProceedKeepsAdmittedCallbackAndPreventsNewOnes() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<HookRegistration> registration = new AtomicReference<HookRegistration>();
        AtomicReference<CloseReport> report = new AtomicReference<CloseReport>();
        AtomicReference<AroundInvocation> borrowed = new AtomicReference<AroundInvocation>();
        backend.onTarget(ADD, (receiver, args) -> {
            if (report.get() == null) report.set(registration.get().close());
            return 3;
        });
        try {
            registration.set(TestSupport.installOrFail(environment, TestSupport.spec("around.close.lease", ADD)
                    .around(invocation -> {
                        borrowed.set(invocation);
                        assertEquals(3, invocation.proceed().value());
                        assertEquals(1, invocation.getArgument(0));
                        invocation.setEarlyResult(9);
                    }).build()));
            assertEquals(9, backend.call(ADD, null, 1, 2));
            assertTrue(report.get().newCallbacksRejected());
            assertEquals(1, report.get().inFlightAtClose());
            assertTrue(TestSupport.capture(() -> borrowed.get().getArgument(0)) instanceof HookContractException);
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(2, backend.originalCallCount(ADD));
        } finally { environment.close(); }
    }

    @Test public void callbackFailureAfterProceedKeepsIdentityWithoutReplayingDownstream() {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        HookEnvironment environment = HookEnvironment.create(backend);
        RuntimeException failure = new IllegalStateException("callback failed after continuation");
        AtomicReference<AroundInvocation> borrowed = new AtomicReference<AroundInvocation>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("around.failure.lease", ADD)
                    .around(invocation -> {
                        borrowed.set(invocation);
                        invocation.proceed();
                        throw failure;
                    }).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(1, backend.originalCallCount(ADD));
            assertTrue(TestSupport.capture(() -> borrowed.get().access()) instanceof HookContractException);
        } finally { environment.close(); }
    }
}
