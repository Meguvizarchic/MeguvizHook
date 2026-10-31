package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.EntrySnapshotInvocation;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;

import java.lang.reflect.Executable;
import java.util.Objects;

/**
 * One frame per invocation, including every reentrant call. No mutable array is shared between
 * nested calls and no thread-local frame stack is reused, so recursion and synchronous reentry can
 * never overwrite each other's proceed state, arguments or snapshot.
 *
 * <p>Implements every invocation interface but enforces the registered shape on each accessor:
 * a callback can never widen its own contract by casting.
 */
final class InvocationFrame implements AroundInvocation, EntrySnapshotInvocation, MutableArgsInvocation {
    private final BoundPlan plan;
    private final BackendInvocation backend;
    private final Thread owner;
    private boolean valid = true;

    private Object[] args;
    private Object[] entrySnapshot;

    private CallOutcome.State proceedState = CallOutcome.State.NOT_CALLED;
    private CallOutcome proceedOutcome = CallOutcome.notCalled();

    private boolean earlyOutcomeSet;
    private Object earlyResult;
    private Throwable earlyThrowable;

    private final ResultSlot resultSlot = new ResultSlot();

    InvocationFrame(BoundPlan plan, BackendInvocation backend) {
        this.plan = plan;
        this.backend = backend;
        this.owner = Thread.currentThread();
        ParameterAccess access = plan.access();
        if (access != ParameterAccess.NO_ARGS) {
            // Materialized once per frame at entry; NO_ARGS never reaches this branch.
            this.args = backend.arguments();
            if (access == ParameterAccess.ENTRY_SNAPSHOT) {
                // Shallow slot copy: the array is copied, the argument objects are shared.
                this.entrySnapshot = args.clone();
            }
        }
    }

    void invalidate() {
        valid = false;
    }

    BackendInvocation backend() {
        return backend;
    }

    private void check() {
        if (!valid) {
            throw new HookContractException("invocation is no longer valid: the callback already returned");
        }
        if (Thread.currentThread() != owner) {
            throw new HookContractException("borrowed invocation used from " + Thread.currentThread().getName()
                    + " instead of " + owner.getName());
        }
    }

    private void requireReadable() {
        check();
        if (plan.access() == ParameterAccess.NO_ARGS) {
            throw new HookContractException("registration " + plan.id() + " declared NO_ARGS; argument access requires another shape");
        }
    }

    private void requireMutable() {
        check();
        ParameterAccess access = plan.access();
        if (access != ParameterAccess.MUTABLE_ARGS && access != ParameterAccess.AROUND) {
            throw new HookContractException("registration " + plan.id() + " declared " + access + "; argument writes require MUTABLE_ARGS or AROUND");
        }
    }

    Object[] argsForProceed() {
        // null means "keep incoming arguments" and avoids materialization for NO_ARGS.
        return args;
    }

    CallOutcome earlyOutcome() {
        return earlyThrowable != null ? CallOutcome.threw(earlyThrowable) : CallOutcome.returned(earlyResult);
    }

    CallOutcome proceedOutcome() {
        return proceedOutcome;
    }

    ResultSlot resultSlot() {
        return resultSlot;
    }

    @Override
    public HookId id() {
        check();
        return plan.id();
    }

    @Override
    public long generation() {
        check();
        return plan.generation();
    }

    @Override
    public Executable target() {
        check();
        return plan.target();
    }

    @Override
    public Object thisObject() {
        check();
        return backend.thisObject();
    }

    @Override
    public ParameterAccess access() {
        return plan.access();
    }

    @Override
    public Thread ownerThread() {
        return owner;
    }

    @Override
    public int argumentCount() {
        requireReadable();
        return plan.target().getParameterTypes().length;
    }

    @Override
    public Object getArgument(int index) {
        requireReadable();
        return args[index];
    }

    @Override
    public Class<?> argumentType(int index) {
        requireReadable();
        return plan.target().getParameterTypes()[index];
    }

    @Override
    public Object getEntryArgument(int index) {
        requireReadable();
        if (plan.access() != ParameterAccess.ENTRY_SNAPSHOT) {
            throw new HookContractException("entry snapshot requires ParameterAccess.ENTRY_SNAPSHOT");
        }
        return entrySnapshot[index];
    }

    @Override
    public Object[] entrySnapshot() {
        requireReadable();
        if (plan.access() != ParameterAccess.ENTRY_SNAPSHOT) {
            throw new HookContractException("entry snapshot requires ParameterAccess.ENTRY_SNAPSHOT");
        }
        return entrySnapshot.clone();
    }

    @Override
    public void setArgument(int index, Object value) {
        requireMutable();
        TypeChecks.checkArgumentValue(plan.target(), index, value);
        args[index] = value;
    }

    @Override
    public void setEarlyResult(Object value) {
        check();
        TypeChecks.checkResultValue(plan.target(), value);
        earlyOutcomeSet = true;
        earlyResult = value;
        earlyThrowable = null;
    }

    @Override
    public void setEarlyThrowable(Throwable throwable) {
        check();
        earlyOutcomeSet = true;
        earlyThrowable = Objects.requireNonNull(throwable, "throwable");
    }

    @Override
    public boolean hasEarlyOutcome() {
        check();
        return earlyOutcomeSet;
    }

    @Override
    public CallOutcome proceed() {
        check();
        if (plan.access() != ParameterAccess.AROUND) {
            throw new HookContractException("proceed() requires ParameterAccess.AROUND");
        }
        if (proceedState != CallOutcome.State.NOT_CALLED) {
            throw new HookContractException("duplicate proceed(): continuation is at most once per invocation");
        }
        proceedState = CallOutcome.State.RUNNING;
        try {
            Object value = backend.proceed(argsForProceed());
            proceedOutcome = CallOutcome.returned(value);
            proceedState = CallOutcome.State.RETURNED;
        } catch (Throwable throwable) {
            proceedOutcome = CallOutcome.threw(throwable);
            proceedState = CallOutcome.State.THREW;
            if (TypeChecks.isFatal(throwable)) {
                throw TypeChecks.fatal(throwable);
            }
        }
        return proceedOutcome;
    }

    @Override
    public CallOutcome.State proceedState() {
        check();
        return proceedState;
    }

    /** After-stage result slot. Initial state is the downstream or early outcome. */
    final class ResultSlot implements InvocationResult {
        private CallOutcome outcome = CallOutcome.notCalled();

        void init(CallOutcome initial) {
            this.outcome = initial;
        }

        CallOutcome outcome() {
            return outcome;
        }

        void restoreOriginal(Throwable original) {
            this.outcome = CallOutcome.threw(original);
        }

        @Override
        public boolean hasReturned() {
            check();
            return outcome.state() == CallOutcome.State.RETURNED;
        }

        @Override
        public boolean hasThrown() {
            check();
            return outcome.state() == CallOutcome.State.THREW;
        }

        @Override
        public Object getResult() {
            check();
            return outcome.state() == CallOutcome.State.RETURNED ? outcome.value() : null;
        }

        @Override
        public Throwable getThrowable() {
            check();
            return outcome.state() == CallOutcome.State.THREW ? outcome.throwable() : null;
        }

        @Override
        public void setResult(Object value) {
            check();
            TypeChecks.checkResultValue(plan.target(), value);
            outcome = CallOutcome.returned(value);
        }

        @Override
        public void setThrowable(Throwable throwable) {
            check();
            outcome = CallOutcome.threw(Objects.requireNonNull(throwable, "throwable"));
        }
    }
}
