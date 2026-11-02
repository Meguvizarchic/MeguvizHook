package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A diagnostics implementation may not hide the original business failure. */
public class DiagnosticsFailureTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void ordinarySinkFailureDoesNotReplaceOriginalOrSkipRemainingAfter() {
        FakeBackend backend = FakeBackend.create();
        IllegalStateException original = new IllegalStateException("original");
        RuntimeException after = new RuntimeException("after");
        backend.onTarget(ADD, (receiver, args) -> { throw original; });
        DiagnosticsSink sink = event -> { throw new IllegalStateException("sink"); };
        HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), sink);
        AtomicBoolean remainingAfterRan = new AtomicBoolean();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("diagnostics.failure", ADD)
                    .after((invocation, result) -> remainingAfterRan.set(true))
                    .after((invocation, result) -> { throw after; }).build());
            assertSame(original, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(1, backend.originalCallCount(ADD));
            assertTrue(remainingAfterRan.get());
        } finally {
            environment.close();
        }
    }

    @Test
    public void fatalSinkFailureRethrowsSameInstanceImmediately() {
        FakeBackend backend = FakeBackend.create();
        IllegalStateException original = new IllegalStateException("original");
        RuntimeException after = new RuntimeException("after");
        SimulatedVmError fatal = new SimulatedVmError();
        backend.onTarget(ADD, (receiver, args) -> { throw original; });
        DiagnosticsSink sink = event -> { throw fatal; };
        HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), sink);
        AtomicBoolean remainingAfterRan = new AtomicBoolean();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("diagnostics.fatal", ADD)
                    .after((invocation, result) -> remainingAfterRan.set(true))
                    .after((invocation, result) -> { throw after; }).build());
            assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(1, backend.originalCallCount(ADD));
            assertFalse(remainingAfterRan.get());
        } finally {
            environment.close();
        }
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
