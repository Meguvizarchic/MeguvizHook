package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.BoundedDiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A callback cannot erase a fatal event already observed by the SDK. */
@SuppressWarnings("removal")
public class CaughtFatalPropagationTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void caughtBeforeFatalSurvivesReturnResultOrOrdinaryThrow() {
        for (Error fatal : fatalEvents()) {
            for (Recovery recovery : Recovery.values()) {
                FakeBackend backend = FakeBackend.create();
                backend.onTarget(ADD, (receiver, args) -> 3);
                BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
                HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
                AtomicReference<Error> caught = new AtomicReference<Error>();
                AtomicBoolean afterRan = new AtomicBoolean();
                try {
                    TestSupport.installOrFail(environment, TestSupport.spec("caught.fatal.before", ADD)
                            .before(invocation -> {
                                try {
                                    invocation.setEarlyThrowable(fatal);
                                } catch (Error error) {
                                    caught.set(error);
                                    recover(recovery, () -> invocation.setEarlyResult(42));
                                }
                            })
                            .after((invocation, result) -> afterRan.set(true)).build());
                    assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                    assertSame(fatal, caught.get());
                    assertEquals(0, backend.originalCallCount(ADD));
                    assertFalse(afterRan.get());
                    assertTrue(diagnostics.events().isEmpty());
                } finally {
                    environment.close();
                }
            }
        }
    }

    @Test
    public void caughtAroundProceedFatalSurvivesReturnResultOrOrdinaryThrow() {
        for (Error fatal : fatalEvents()) {
            for (Recovery recovery : Recovery.values()) {
                FakeBackend backend = FakeBackend.create();
                backend.onTarget(ADD, (receiver, args) -> { throw fatal; });
                BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
                HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
                AtomicReference<Error> caught = new AtomicReference<Error>();
                try {
                    TestSupport.installOrFail(environment, TestSupport.spec("caught.fatal.proceed", ADD)
                            .around(invocation -> {
                                try {
                                    invocation.proceed();
                                } catch (Error error) {
                                    caught.set(error);
                                    recover(recovery, () -> invocation.setEarlyResult(42));
                                }
                            }).build());
                    assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                    assertSame(fatal, caught.get());
                    assertEquals(1, backend.originalCallCount(ADD));
                    assertTrue(diagnostics.events().isEmpty());
                } finally {
                    environment.close();
                }
            }
        }
    }

    @Test
    public void caughtAroundSetterFatalSurvivesReturnResultOrOrdinaryThrow() {
        for (Error fatal : fatalEvents()) {
            for (Recovery recovery : Recovery.values()) {
                FakeBackend backend = FakeBackend.create();
                backend.onTarget(ADD, (receiver, args) -> 3);
                BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
                HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
                AtomicReference<Error> caught = new AtomicReference<Error>();
                try {
                    TestSupport.installOrFail(environment, TestSupport.spec("caught.fatal.around", ADD)
                            .around(invocation -> {
                                try {
                                    invocation.setEarlyThrowable(fatal);
                                } catch (Error error) {
                                    caught.set(error);
                                    recover(recovery, () -> invocation.setEarlyResult(42));
                                }
                            }).build());
                    assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                    assertSame(fatal, caught.get());
                    assertEquals(0, backend.originalCallCount(ADD));
                    assertTrue(diagnostics.events().isEmpty());
                } finally {
                    environment.close();
                }
            }
        }
    }

    @Test
    public void caughtAfterResultFatalSurvivesReturnResultOrOrdinaryThrow() {
        for (Error fatal : fatalEvents()) {
            for (Recovery recovery : Recovery.values()) {
                FakeBackend backend = FakeBackend.create();
                backend.onTarget(ADD, (receiver, args) -> 3);
                BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
                HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
                AtomicReference<Error> caught = new AtomicReference<Error>();
                AtomicBoolean laterAfterRan = new AtomicBoolean();
                try {
                    TestSupport.installOrFail(environment, TestSupport.spec("caught.fatal.after", ADD)
                            .after((invocation, result) -> laterAfterRan.set(true))
                            .after((invocation, result) -> {
                                try {
                                    result.setThrowable(fatal);
                                } catch (Error error) {
                                    caught.set(error);
                                    recover(recovery, () -> result.setResult(42));
                                }
                            }).build());
                    assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                    assertSame(fatal, caught.get());
                    assertEquals(1, backend.originalCallCount(ADD));
                    assertFalse(laterAfterRan.get());
                    assertTrue(diagnostics.events().isEmpty());
                } finally {
                    environment.close();
                }
            }
        }
    }

    private enum Recovery { RETURN, SET_RESULT, THROW_ORDINARY }

    private static void recover(Recovery recovery, Runnable setResult) {
        if (recovery == Recovery.SET_RESULT) {
            setResult.run();
        } else if (recovery == Recovery.THROW_ORDINARY) {
            throw new IllegalStateException("callback attempted to replace a fatal event");
        }
    }

    private static Error[] fatalEvents() {
        return new Error[]{new SimulatedVmError(), new ThreadDeath()};
    }

    private static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
