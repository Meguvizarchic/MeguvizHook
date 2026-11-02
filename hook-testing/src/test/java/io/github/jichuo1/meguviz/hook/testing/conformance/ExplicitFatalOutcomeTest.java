package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.BoundedDiagnosticsSink;
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

/** Fatal outcomes supplied by callbacks must bypass ordinary result processing. */
@SuppressWarnings("removal")
public class ExplicitFatalOutcomeTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void beforeExplicitFatalBypassesOriginalAndAfter() {
        for (Error fatal : fatalEvents()) {
            FakeBackend backend = FakeBackend.create();
            BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
            HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
            AtomicBoolean afterRan = new AtomicBoolean();
            try {
                TestSupport.installOrFail(environment, TestSupport.spec("fatal.explicit.before", ADD)
                        .before(invocation -> invocation.setEarlyThrowable(fatal))
                        .after((invocation, result) -> afterRan.set(true)).build());
                assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                assertEquals(0, backend.originalCallCount(ADD));
                assertFalse(afterRan.get());
                assertTrue(diagnostics.events().isEmpty());
            } finally {
                environment.close();
            }
        }
    }

    @Test
    public void aroundExplicitFatalBypassesOriginal() {
        for (Error fatal : fatalEvents()) {
            FakeBackend backend = FakeBackend.create();
            BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
            HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
            AtomicBoolean continued = new AtomicBoolean();
            try {
                TestSupport.installOrFail(environment, TestSupport.spec("fatal.explicit.around", ADD)
                        .around(invocation -> {
                            invocation.setEarlyThrowable(fatal);
                            continued.set(true);
                        }).build());
                assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                assertEquals(0, backend.originalCallCount(ADD));
                assertFalse(continued.get());
                assertTrue(diagnostics.events().isEmpty());
            } finally {
                environment.close();
            }
        }
    }

    @Test
    public void afterExplicitResultFatalBypassesRemainingAfter() {
        for (Error fatal : fatalEvents()) {
            checkAfterFatal(fatal, false);
        }
    }

    @Test
    public void afterExplicitEarlyFatalBypassesRemainingAfter() {
        for (Error fatal : fatalEvents()) {
            checkAfterFatal(fatal, true);
        }
    }

    private static void checkAfterFatal(Error fatal, boolean useInvocation) {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
        HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
        AtomicBoolean laterAfterRan = new AtomicBoolean();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("fatal.explicit.after", ADD)
                    .after((invocation, result) -> laterAfterRan.set(true))
                    .after((invocation, result) -> {
                        if (useInvocation) {
                            invocation.setEarlyThrowable(fatal);
                        } else {
                            result.setThrowable(fatal);
                        }
                    }).build());
            assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(1, backend.originalCallCount(ADD));
            assertFalse(laterAfterRan.get());
            assertTrue(diagnostics.events().isEmpty());
        } finally {
            environment.close();
        }
    }

    private static Error[] fatalEvents() {
        return new Error[]{new SimulatedVmError(), new ThreadDeath()};
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
