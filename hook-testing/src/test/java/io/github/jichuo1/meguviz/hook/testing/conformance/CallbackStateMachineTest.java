package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.AroundCallback;
import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.core.BoundedDiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.ContractOracle;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CONTRACT-001 §3 state machine against the fake backend, checked against the independently written
 * {@link ContractOracle}. These tests prove the SDK's own semantics only, never a real framework.
 */
public class CallbackStateMachineTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    private FakeBackend backend;
    private BoundedDiagnosticsSink diagnostics;
    private HookEnvironment environment;

    @Before
    public void setUp() {
        backend = FakeBackend.create();
        diagnostics = new BoundedDiagnosticsSink(8);
        environment = HookEnvironment.create(backend, new io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver(), diagnostics);
    }

    @Test
    public void beforeThrowsMatchesOracle() {
        RuntimeException callbackFailure = new RuntimeException("before");
        AtomicBoolean afterRan = new AtomicBoolean();
        TestSupport.installOrFail(environment, TestSupport.spec("before.throws", ADD)
                .before(invocation -> {
                    throw callbackFailure;
                })
                .after((invocation, result) -> afterRan.set(true))
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        ContractOracle.Expectation expected = ContractOracle.beforeThrows();
        assertSame(callbackFailure, observed);
        assertEquals(expected.originalCalls(), backend.originalCallCount(ADD));
        assertEquals(expected.afterCallbacksRan(), afterRan.get());
        assertTrue(diagnostics.events().isEmpty());
    }

    @Test
    public void earlyReturnMatchesOracleAndAfterObservesOutcome() {
        AtomicReference<Object> afterValue = new AtomicReference<Object>();
        TestSupport.installOrFail(environment, TestSupport.spec("early.return", ADD)
                .before(invocation -> invocation.setEarlyResult(99))
                .after((invocation, result) -> afterValue.set(result.getResult()))
                .build());

        try {
            assertEquals(99, backend.call(ADD, null, 1, 2));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        ContractOracle.Expectation expected = ContractOracle.earlyReturn();
        assertEquals(expected.originalCalls(), backend.originalCallCount(ADD));
        assertEquals(99, afterValue.get());
    }

    @Test
    public void originalResultFlowsToAfterAndCanBeReplaced() {
        backend.onTarget(ADD, (thisObject, args) -> (Integer) args[0] + (Integer) args[1]);
        TestSupport.installOrFail(environment, TestSupport.spec("original.returns", ADD)
                .after((invocation, result) -> {
                    assertEquals(3, result.getResult());
                    result.setResult(30);
                })
                .build());

        try {
            assertEquals(30, backend.call(ADD, null, 1, 2));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals(ContractOracle.originalReturns().originalCalls(), backend.originalCallCount(ADD));
    }

    @Test
    public void originalThrowableIdentityIsPreserved() {
        IllegalStateException originalFailure = new IllegalStateException("original");
        backend.onTarget(ADD, (thisObject, args) -> {
            throw originalFailure;
        });
        AtomicReference<Throwable> seenByAfter = new AtomicReference<Throwable>();
        TestSupport.installOrFail(environment, TestSupport.spec("original.throws", ADD)
                .after((invocation, result) -> {
                    assertTrue(result.hasThrown());
                    seenByAfter.set(result.getThrowable());
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertSame(originalFailure, observed);
        assertSame(originalFailure, seenByAfter.get());
        assertEquals(ContractOracle.originalThrows().originalCalls(), backend.originalCallCount(ADD));
    }

    @Test
    public void afterFailureAfterSuccessPropagatesWithoutReplay() {
        RuntimeException afterFailure = new RuntimeException("after");
        backend.onTarget(ADD, (thisObject, args) -> 7);
        TestSupport.installOrFail(environment, TestSupport.spec("after.throws", ADD)
                .after((invocation, result) -> {
                    throw afterFailure;
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        ContractOracle.Expectation expected = ContractOracle.afterThrowsAfterSuccess();
        assertSame(afterFailure, observed);
        assertEquals(expected.originalCalls(), backend.originalCallCount(ADD));
        assertTrue(diagnostics.events().isEmpty());
    }

    @Test
    public void originalThrowableWinsWhenBothFail() {
        IllegalStateException originalFailure = new IllegalStateException("original");
        RuntimeException afterFailure = new RuntimeException("after");
        backend.onTarget(ADD, (thisObject, args) -> {
            throw originalFailure;
        });
        TestSupport.installOrFail(environment, TestSupport.spec("both.throw", ADD)
                .after((invocation, result) -> {
                    throw afterFailure;
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        ContractOracle.Expectation expected = ContractOracle.bothThrow();
        assertSame("original throwable must be kept", originalFailure, observed);
        assertEquals(expected.originalCalls(), backend.originalCallCount(ADD));
        assertEquals(expected.diagnosticRecorded(), !diagnostics.events().isEmpty());
        assertSame(afterFailure, diagnostics.events().get(0).failure());
    }

    @Test
    public void beforeRunsInOrderAndAfterInReverseOrder() {
        backend.onTarget(ADD, (thisObject, args) -> 1);
        List<String> order = new ArrayList<String>();
        TestSupport.installOrFail(environment, TestSupport.spec("ordering", ADD)
                .before(invocation -> order.add("before-1"))
                .before(invocation -> order.add("before-2"))
                .after((invocation, result) -> order.add("after-1"))
                .after((invocation, result) -> order.add("after-2"))
                .build());
        try {
            backend.call(ADD, null, 1, 2);
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals(java.util.Arrays.asList("before-1", "before-2", "after-2", "after-1"), order);
    }

    @Test
    public void aroundProceedsOnceAndReturnsDownstreamValue() {
        backend.onTarget(ADD, (thisObject, args) -> (Integer) args[0] + (Integer) args[1]);
        TestSupport.installOrFail(environment, TestSupport.spec("around.proceeds", ADD)
                .around(invocation -> {
                    CallOutcome outcome = invocation.proceed();
                    assertEquals(CallOutcome.State.RETURNED, outcome.state());
                    invocation.setEarlyResult((Integer) outcome.value() * 10);
                })
                .build());
        try {
            assertEquals(30, backend.call(ADD, null, 1, 2));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals(ContractOracle.aroundProceeds().originalCalls(), backend.originalCallCount(ADD));
    }

    @Test
    public void aroundCanSkipOriginalWithExplicitOutcome() {
        TestSupport.installOrFail(environment, TestSupport.spec("around.early", ADD)
                .around(invocation -> invocation.setEarlyResult(5))
                .build());
        try {
            assertEquals(5, backend.call(ADD, null, 1, 2));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals(ContractOracle.aroundEarlyReturn().originalCalls(), backend.originalCallCount(ADD));
    }

    @Test
    public void aroundFailureAfterProceedDoesNotReplayOriginal() {
        RuntimeException aroundFailure = new RuntimeException("around");
        backend.onTarget(ADD, (thisObject, args) -> 11);
        TestSupport.installOrFail(environment, TestSupport.spec("around.throws", ADD)
                .around(invocation -> {
                    invocation.proceed();
                    throw aroundFailure;
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        ContractOracle.Expectation expected = ContractOracle.aroundThrowsAfterProceed();
        assertSame(aroundFailure, observed);
        assertEquals(expected.originalCalls(), backend.originalCallCount(ADD));
    }

    @Test
    public void secondProceedIsContractViolationWithoutReplay() {
        backend.onTarget(ADD, (thisObject, args) -> 11);
        TestSupport.installOrFail(environment, TestSupport.spec("around.twice", ADD)
                .around(invocation -> {
                    invocation.proceed();
                    invocation.proceed();
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertTrue("expected HookContractException but got " + observed, observed instanceof HookContractException);
        assertEquals(ContractOracle.aroundSecondProceed().originalCalls(), backend.originalCallCount(ADD));
    }

    @Test
    public void aroundWithoutOutcomeNeverCallsOriginalImplicitly() {
        TestSupport.installOrFail(environment, TestSupport.spec("around.undecided", ADD)
                .around(invocation -> {
                    // Neither proceed nor explicit outcome.
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertTrue(observed instanceof HookContractException);
        assertEquals(ContractOracle.aroundWithoutOutcome().originalCalls(), backend.originalCallCount(ADD));
    }

    @Test
    public void aroundObservesDownstreamThrowableWithoutSwallowingIt() {
        IllegalStateException originalFailure = new IllegalStateException("original");
        backend.onTarget(ADD, (thisObject, args) -> {
            throw originalFailure;
        });
        AtomicReference<CallOutcome.State> observedState = new AtomicReference<CallOutcome.State>();
        TestSupport.installOrFail(environment, TestSupport.spec("around.downstream.throw", ADD)
                .around(invocation -> {
                    CallOutcome outcome = invocation.proceed();
                    observedState.set(outcome.state());
                    assertSame(originalFailure, outcome.throwable());
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertSame(originalFailure, observed);
        assertEquals(CallOutcome.State.THREW, observedState.get());
        assertEquals(1, backend.originalCallCount(ADD));
    }

    /** Fatal VM events are test-doubles only; no real VM error is triggered here. */
    public static final class FakeVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;

        public FakeVmError(String message) {
            super(message);
        }
    }

    @Test
    public void fatalBeforeFailureIsNotConvertedIntoOrdinaryOutcome() {
        FakeVmError fatal = new FakeVmError("fatal-before");
        TestSupport.installOrFail(environment, TestSupport.spec("fatal.before", ADD)
                .before(invocation -> {
                    throw fatal;
                })
                .after((invocation, result) -> {
                    throw new AssertionError("after must not run");
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertSame(fatal, observed);
        assertEquals(0, backend.originalCallCount(ADD));
        assertTrue(diagnostics.events().isEmpty());
    }

    @Test
    public void fatalAfterFailureWinsOverOriginalThrowable() {
        IllegalStateException originalFailure = new IllegalStateException("original");
        FakeVmError fatal = new FakeVmError("fatal-after");
        backend.onTarget(ADD, (thisObject, args) -> {
            throw originalFailure;
        });
        TestSupport.installOrFail(environment, TestSupport.spec("fatal.after", ADD)
                .after((invocation, result) -> {
                    throw fatal;
                })
                .build());

        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertSame("fatal VM events are never demoted to diagnostics", fatal, observed);
        assertTrue(diagnostics.events().isEmpty());
    }

    @Test
    public void callbackInterfacesStayJavaOnly() {
        // Compile-time guard for HOOK-002: the public callback surface uses only JDK + own API types.
        BeforeCallback before = new BeforeCallback() {
            @Override
            public void before(HookInvocation invocation) {
            }
        };
        AfterCallback after = new AfterCallback() {
            @Override
            public void after(HookInvocation invocation, InvocationResult result) {
            }
        };
        AroundCallback around = new AroundCallback() {
            @Override
            public void around(AroundInvocation invocation) {
            }
        };
        assertNotNull(before);
        assertNotNull(after);
        assertNotNull(around);
        HookSpec spec = TestSupport.spec("surface", ADD).before(before).after(after).build();
        assertNull(spec.aroundCallback());
        assertFalse(spec.requiredCapabilities().iterator().hasNext());
    }
}
