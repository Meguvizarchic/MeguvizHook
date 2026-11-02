package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** An explicit early outcome must prevent a later continuation from running. */
public class AroundEarlyProceedTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void earlyResultThenUncaughtProceedFailsWithoutRunningOriginal() {
        FakeBackend backend = backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("around.early.result.uncaught", ADD)
                    .around(invocation -> {
                        invocation.setEarlyResult(5);
                        invocation.proceed();
                    }).build());
            Throwable failure = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
            assertTrue("expected contract failure, got " + failure, failure instanceof HookContractException);
            assertEquals(0, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void earlyThrowableThenUncaughtProceedFailsWithoutRunningOriginal() {
        FakeBackend backend = backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        RuntimeException early = new RuntimeException("early");
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("around.early.throw.uncaught", ADD)
                    .around(invocation -> {
                        invocation.setEarlyThrowable(early);
                        invocation.proceed();
                    }).build());
            Throwable failure = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
            assertTrue("expected contract failure, got " + failure, failure instanceof HookContractException);
            assertEquals(0, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void caughtProceedFailurePreservesEarlyResult() throws Throwable {
        FakeBackend backend = backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<Throwable> rejected = new AtomicReference<Throwable>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("around.early.result.caught", ADD)
                    .around(invocation -> {
                        invocation.setEarlyResult(5);
                        rejected.set(TestSupport.capture(invocation::proceed));
                    }).build());
            assertEquals(5, backend.call(ADD, null, 1, 2));
            assertTrue(rejected.get() instanceof HookContractException);
            assertEquals(0, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void caughtProceedFailurePreservesEarlyThrowableIdentity() {
        FakeBackend backend = backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        RuntimeException early = new RuntimeException("early");
        AtomicReference<Throwable> rejected = new AtomicReference<Throwable>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("around.early.throw.caught", ADD)
                    .around(invocation -> {
                        invocation.setEarlyThrowable(early);
                        rejected.set(TestSupport.capture(invocation::proceed));
                    }).build());
            assertSame(early, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertTrue(rejected.get() instanceof HookContractException);
            assertEquals(0, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void resultMayStillBeOverriddenAfterProceed() throws Throwable {
        FakeBackend backend = backend();
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("around.after.proceed", ADD)
                    .around(invocation -> {
                        CallOutcome outcome = invocation.proceed();
                        assertEquals(3, outcome.value());
                        invocation.setEarlyResult(9);
                    }).build());
            assertEquals(9, backend.call(ADD, null, 1, 2));
            assertEquals(1, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void downstreamFailureCanBePreservedOrExplicitlyReplacedWithoutReplay() throws Throwable {
        RuntimeException originalFailure = new RuntimeException("original");
        for (boolean replace : new boolean[]{false, true}) {
            FakeBackend backend = backend();
            backend.onTarget(ADD, (receiver, args) -> { throw originalFailure; });
            HookEnvironment environment = HookEnvironment.create(backend);
            try {
                TestSupport.installOrFail(environment, TestSupport.spec("around.after.throw", ADD)
                        .around(invocation -> {
                            CallOutcome outcome = invocation.proceed();
                            assertEquals(CallOutcome.State.THREW, outcome.state());
                            assertSame(originalFailure, outcome.throwable());
                            if (replace) invocation.setEarlyResult(9);
                        }).build());
                if (replace) {
                    assertEquals(9, backend.call(ADD, null, 1, 2));
                } else {
                    assertSame(originalFailure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                }
                assertEquals("downstream must not be replayed", 1, backend.originalCallCount(ADD));
            } finally {
                environment.close();
            }
        }
    }

    private static FakeBackend backend() {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        return backend;
    }
}
