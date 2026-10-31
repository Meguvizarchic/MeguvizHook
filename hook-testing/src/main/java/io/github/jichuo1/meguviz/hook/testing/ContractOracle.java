package io.github.jichuo1.meguviz.hook.testing;

/**
 * Independently written expectation table for CONTRACT-001 §3. It is derived from the contract
 * document, not from the dispatcher implementation, so a wrong implementation can fail against it.
 * Both the fake-based tests and any future real-backend fixture must satisfy the same table.
 */
public final class ContractOracle {
    /** Which throwable/value the caller must observe. */
    public enum ExpectedOutcome {
        RETURNED,
        ORIGINAL_THROWABLE,
        CALLBACK_THROWABLE,
        CONTRACT_THROWABLE
    }

    public static final class Expectation {
        private final int originalCalls;
        private final ExpectedOutcome outcome;
        private final boolean afterCallbacksRan;
        private final boolean diagnosticRecorded;

        private Expectation(int originalCalls, ExpectedOutcome outcome, boolean afterCallbacksRan, boolean diagnosticRecorded) {
            this.originalCalls = originalCalls;
            this.outcome = outcome;
            this.afterCallbacksRan = afterCallbacksRan;
            this.diagnosticRecorded = diagnosticRecorded;
        }

        public int originalCalls() {
            return originalCalls;
        }

        public ExpectedOutcome outcome() {
            return outcome;
        }

        public boolean afterCallbacksRan() {
            return afterCallbacksRan;
        }

        public boolean diagnosticRecorded() {
            return diagnosticRecorded;
        }
    }

    /** before fails before the original started: zero downstream executions, no after, callback throwable. */
    public static Expectation beforeThrows() {
        return new Expectation(0, ExpectedOutcome.CALLBACK_THROWABLE, false, false);
    }

    /** Explicit early return: zero downstream executions, after still observes the outcome. */
    public static Expectation earlyReturn() {
        return new Expectation(0, ExpectedOutcome.RETURNED, true, false);
    }

    public static Expectation originalReturns() {
        return new Expectation(1, ExpectedOutcome.RETURNED, true, false);
    }

    /** Original throwable identity is preserved unless explicitly replaced. */
    public static Expectation originalThrows() {
        return new Expectation(1, ExpectedOutcome.ORIGINAL_THROWABLE, true, false);
    }

    /** after fails after a successful original: after throwable propagates, original ran exactly once. */
    public static Expectation afterThrowsAfterSuccess() {
        return new Expectation(1, ExpectedOutcome.CALLBACK_THROWABLE, true, false);
    }

    /** Both fail: original throwable wins and the after failure is recorded, never a replay. */
    public static Expectation bothThrow() {
        return new Expectation(1, ExpectedOutcome.ORIGINAL_THROWABLE, true, true);
    }

    public static Expectation aroundProceeds() {
        return new Expectation(1, ExpectedOutcome.RETURNED, false, false);
    }

    /** around never proceeds but sets an explicit result: the original stays uncalled. */
    public static Expectation aroundEarlyReturn() {
        return new Expectation(0, ExpectedOutcome.RETURNED, false, false);
    }

    /** around fails after proceed: callback throwable, proceed count stays at one. */
    public static Expectation aroundThrowsAfterProceed() {
        return new Expectation(1, ExpectedOutcome.CALLBACK_THROWABLE, false, false);
    }

    /** Second proceed is a contract violation and must not execute the original again. */
    public static Expectation aroundSecondProceed() {
        return new Expectation(1, ExpectedOutcome.CONTRACT_THROWABLE, false, false);
    }

    /** Around returned without deciding: no implicit original call, contract violation reported. */
    public static Expectation aroundWithoutOutcome() {
        return new Expectation(0, ExpectedOutcome.CONTRACT_THROWABLE, false, false);
    }

    private ContractOracle() {
    }
}
