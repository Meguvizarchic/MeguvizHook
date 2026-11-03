package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Contract and error precedence for independent callback borrows of the same invocation. */
public final class BorrowValidationFastPathTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void expiredBorrowKeepsThreadFailurePrecedenceDuringLaterCallbackAndReentry() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<HookInvocation> previous = new AtomicReference<HookInvocation>();
        AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("borrow.validation.precedence", ADD)
                    .access(ParameterAccess.READ_ARGS)
                    .before(invocation -> {
                        int depth = (Integer) ((ReadArgsInvocation) invocation).getArgument(0);
                        if (depth == 1) previous.set(invocation);
                        else assertExpired(previous.get());
                    })
                    .before(invocation -> {
                        int depth = (Integer) ((ReadArgsInvocation) invocation).getArgument(0);
                        assertExpired(previous.get());
                        if (depth == 1) {
                            Thread other = new Thread(() -> wrongThread.set(TestSupport.capture(() -> previous.get().id())));
                            other.start();
                            other.join(5000);
                            assertFalse("cross-thread check did not finish", other.isAlive());
                            assertEquals(2, backend.call(ADD, null, 0, 2));
                            assertEquals(1, ((ReadArgsInvocation) invocation).getArgument(0));
                        }
                    }).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(2, backend.originalCallCount(ADD));
            assertTrue(wrongThread.get() instanceof HookContractException);
            assertTrue(wrongThread.get().getMessage().startsWith("borrowed invocation used from "));
            assertExpired(previous.get());
            AtomicReference<Throwable> expiredCrossThread = new AtomicReference<Throwable>();
            Thread afterReturn = new Thread(() -> expiredCrossThread.set(TestSupport.capture(() -> previous.get().id())));
            afterReturn.start();
            afterReturn.join(5000);
            assertFalse("expired cross-thread check did not finish", afterReturn.isAlive());
            assertTrue(expiredCrossThread.get() instanceof HookContractException);
            assertEquals("invocation is no longer valid: the callback already returned", expiredCrossThread.get().getMessage());
        } finally { environment.close(); }
    }

    @Test public void borrowedResultExpiresAndCrossThreadChecksRemainEnforced() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<InvocationResult> earlier = new AtomicReference<InvocationResult>();
        AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("borrow.result.precedence", ADD)
                    .after((invocation, result) -> {
                        assertTrue(TestSupport.capture(() -> earlier.get().getResult()) instanceof HookContractException);
                        Thread other = new Thread(() -> wrongThread.set(TestSupport.capture(() -> earlier.get().hasReturned())));
                        other.start();
                        other.join(5000);
                        assertFalse("cross-thread check did not finish", other.isAlive());
                        assertEquals(3, result.getResult());
                        result.setResult(7);
                    })
                    .after((invocation, result) -> {
                        earlier.set(result);
                        assertTrue(result.hasReturned());
                        assertEquals(3, result.getResult());
                    }).build());
            assertEquals(7, backend.call(ADD, null, 1, 2));
            assertTrue(wrongThread.get() instanceof HookContractException);
            assertTrue(wrongThread.get().getMessage().startsWith("borrowed invocation used from "));
            assertTrue(TestSupport.capture(() -> earlier.get().getResult()) instanceof HookContractException);
        } finally { environment.close(); }
    }

    private static void assertExpired(HookInvocation invocation) {
        Throwable failure = TestSupport.capture(invocation::id);
        assertTrue(failure instanceof HookContractException);
        assertEquals("invocation is no longer valid: the callback already returned", failure.getMessage());
    }
}
