package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Rejecting a null early throwable must leave the outcome undecided. */
public class InvalidEarlyThrowableTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void beforeCanCatchNullAndOriginalStillRuns() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicBoolean rejected = new AtomicBoolean();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("early.null.before", ADD)
                    .before(invocation -> {
                        try {
                            invocation.setEarlyThrowable(null);
                        } catch (NullPointerException expected) {
                            rejected.set(true);
                        }
                    }).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(1, backend.originalCallCount(ADD));
            assertTrue(rejected.get());
        } finally {
            environment.close();
        }
    }

    @Test
    public void aroundCaughtNullStillRequiresAnExplicitOutcome() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicBoolean rejected = new AtomicBoolean();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("early.null.around", ADD)
                    .around(invocation -> {
                        try {
                            invocation.setEarlyThrowable(null);
                        } catch (NullPointerException expected) {
                            rejected.set(true);
                        }
                    }).build());
            Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
            assertTrue(observed instanceof HookContractException);
            assertEquals(0, backend.originalCallCount(ADD));
            assertTrue(rejected.get());
        } finally {
            environment.close();
        }
    }
}
