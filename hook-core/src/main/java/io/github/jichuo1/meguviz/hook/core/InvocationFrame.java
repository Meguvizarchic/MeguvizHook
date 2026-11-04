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
import io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Objects;

/**
 * One frame per invocation, including every reentrant call. No mutable array is shared between
 * nested calls and no thread-local frame stack is reused, so recursion and synchronous reentry can
 * never overwrite each other's proceed state, arguments or snapshot.
 *
 * <p>The frame owns the shared state for the whole invocation. Each callback receives a distinct
 * borrowed view; invalidating that view never invalidates a later callback's view. Both the frame
 * and the view enforce the registered shape, so a callback cannot widen it by casting.
 *
 * <p>Backend access goes through one (accessor, chain) pair: the ordinary transport pairs
 * {@link InvocationAccessor} with the backend's {@link BackendInvocation}; the flyweight transport
 * pairs a backend's stateless {@link ChainAccessor} with the framework's own chain object, so no
 * per-call adapter exists. Lifetime, thread, shape and fatal rules do not depend on the transport.
 */
final class InvocationFrame implements AroundInvocation, EntrySnapshotInvocation, MutableArgsInvocation {
    private final BoundPlan plan;
    private final ChainAccessor<Object> accessor;
    private final Object chain;
    private final Thread owner;
    private boolean valid = true;

    private Object[] args;
    // The shapes are exclusive, so one field keeps the frame at its former size: the ENTRY_SNAPSHOT
    // copy (Object[]), or for READ_ARGS_VIEW on the ordinary transport the backend's view (List).
    // The flyweight READ_ARGS_VIEW reads slots through the accessor and leaves it null.
    private Object shapeState;

    private CallOutcome.State proceedState = CallOutcome.State.NOT_CALLED;
    private CallOutcome proceedOutcome = CallOutcome.notCalled();
    private Throwable observedFatal;

    private boolean earlyOutcomeSet;
    private Object earlyResult;
    private Throwable earlyThrowable;

    // Before-only and around registrations never need an after-stage result slot.
    private ResultSlot resultSlot;

    /** Ordinary transport through the backend's per-call adapter. */
    InvocationFrame(BoundPlan plan, BackendInvocation backend) {
        this(plan, InvocationAccessor.INSTANCE, backend, backend);
    }

    /** Flyweight transport: the frame reads the framework chain through the backend's accessor. */
    <C> InvocationFrame(BoundPlan plan, ChainAccessor<C> accessor, C chain) {
        this(plan, accessor, chain, null);
    }

    @SuppressWarnings("unchecked")
    private InvocationFrame(BoundPlan plan, ChainAccessor<?> accessor, Object chain, BackendInvocation viewSource) {
        this.plan = plan;
        this.accessor = (ChainAccessor<Object>) accessor;
        this.chain = chain;
        this.owner = Thread.currentThread();
        ParameterAccess access = plan.access();
        if (access == ParameterAccess.READ_ARGS_VIEW) {
            // Only the explicitly read-only shape borrows the adapter's argument view. The flyweight
            // accessor needs no view object: it reads single slots on demand.
            if (viewSource != null) {
                this.shapeState = Objects.requireNonNull(viewSource.readOnlyArguments(), "readOnlyArguments");
            }
        } else if (access == ParameterAccess.READ_ARGS) {
            // The shape exists to read slots, so it materializes at entry: deferring would only add
            // a branch, and keeps the frame easier to scalar-replace for JITs that can.
            this.args = this.accessor.arguments(chain);
        } else if (access == ParameterAccess.ENTRY_SNAPSHOT) {
            // The snapshot is defined at entry, so this shape stays eager. Shallow slot copy: the
            // array is copied, the argument objects are shared.
            Object[] incoming = this.accessor.arguments(chain);
            this.args = incoming;
            this.shapeState = incoming.clone();
        }
        // MUTABLE_ARGS and AROUND obtain the backend array on first slot access (see slots());
        // NO_ARGS never does.
    }

    /** The backend argument array, obtained at most once per frame on the first slot access. */
    private Object[] slots() {
        Object[] current = args;
        return current != null ? current : materializeSlots();
    }

    private Object[] materializeSlots() {
        Object[] materialized;
        try {
            materialized = accessor.arguments(chain);
        } catch (Error failure) {
            // Runs inside a callback: a fatal VM event must stay fatal even if that callback
            // catches it. Ordinary failures surface to the callback that touched the slots.
            rejectFatal(failure);
            throw failure;
        }
        args = Objects.requireNonNull(materialized, "arguments");
        return materialized;
    }

    void invalidate() {
        valid = false;
        // Nothing reads the view or the snapshot once the frame is invalid; drop the references.
        shapeState = null;
    }

    /** Runs downstream once with the slots callbacks used, or the incoming ones (see argsForProceed). */
    Object proceedDownstream() throws Throwable {
        return accessor.proceed(chain, args);
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

    /**
     * The array callbacks read and wrote, so downstream and every stage observe the same slots. If
     * no MUTABLE_ARGS or AROUND callback touched the slots before downstream runs, null keeps the
     * incoming arguments (always for NO_ARGS and READ_ARGS_VIEW); a later slot access then obtains
     * them from the backend.
     */
    Object[] argsForProceed() {
        return args;
    }

    /** The explicit early outcome: returns the early result or throws the early throwable. */
    Object earlyOutcome() throws Throwable {
        if (earlyThrowable != null) throw earlyThrowable;
        return earlyResult;
    }

    void initializeEarlyResult(ResultSlot slot) {
        if (earlyThrowable != null) slot.initThrown(earlyThrowable);
        else slot.initReturned(earlyResult);
    }

    /** Internal access after the sole callback's borrowed frame has expired. */
    boolean hasEarlyOutcomeForDispatch() {
        return earlyOutcomeSet;
    }

    CallOutcome proceedOutcome() {
        return proceedOutcome;
    }

    /** Internal outcome state after the only around callback's borrow has expired. */
    CallOutcome.State proceedStateForDispatch() {
        return proceedState;
    }

    ResultSlot resultSlot() {
        if (resultSlot == null) resultSlot = new ResultSlot();
        return resultSlot;
    }

    BorrowedInvocation borrow() {
        check();
        return new BorrowedInvocation();
    }

    /** A distinct lease for one after callback that also serves as that callback's result view. */
    AfterBorrow borrowAfter() {
        // The dispatcher initializes the shared result slot before the first after callback.
        check();
        return new AfterBorrow();
    }

    void rethrowObservedFatal() {
        if (observedFatal != null) {
            throw TypeChecks.fatal(observedFatal);
        }
    }

    private void rejectFatal(Throwable throwable) {
        if (TypeChecks.isFatal(throwable)) {
            if (observedFatal == null) {
                observedFatal = throwable;
            }
            throw TypeChecks.fatal(observedFatal);
        }
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
        return plan.isInstanceMethod() ? accessor.thisObject(chain) : null;
    }

    @Override
    public Object constructorReceiver() {
        check();
        return plan.isConstructor() ? accessor.thisObject(chain) : null;
    }

    @Override
    public ParameterAccess access() {
        check();
        return plan.access();
    }

    @Override
    public Thread ownerThread() {
        check();
        return owner;
    }

    @Override
    public int argumentCount() {
        requireReadable();
        return plan.parameterCount();
    }

    @Override
    public Object getArgument(int index) {
        requireReadable();
        if (plan.access() != ParameterAccess.READ_ARGS_VIEW) return slots()[index];
        Object view = shapeState;
        return view != null ? ((List<?>) view).get(index) : accessor.argument(chain, index);
    }

    @Override
    public Class<?> argumentType(int index) {
        requireReadable();
        return plan.parameterType(index);
    }

    @Override
    public Object getEntryArgument(int index) {
        requireReadable();
        if (plan.access() != ParameterAccess.ENTRY_SNAPSHOT) {
            throw new HookContractException("entry snapshot requires ParameterAccess.ENTRY_SNAPSHOT");
        }
        return ((Object[]) shapeState)[index];
    }

    @Override
    public Object[] entrySnapshot() {
        requireReadable();
        if (plan.access() != ParameterAccess.ENTRY_SNAPSHOT) {
            throw new HookContractException("entry snapshot requires ParameterAccess.ENTRY_SNAPSHOT");
        }
        return ((Object[]) shapeState).clone();
    }

    @Override
    public void setArgument(int index, Object value) {
        requireMutable();
        TypeChecks.checkArgumentValue(plan, index, value);
        slots()[index] = value;
    }

    @Override
    public void setEarlyResult(Object value) {
        check();
        TypeChecks.checkResultValue(plan, value);
        earlyOutcomeSet = true;
        earlyResult = value;
        earlyThrowable = null;
    }

    @Override
    public void setEarlyThrowable(Throwable throwable) {
        check();
        Objects.requireNonNull(throwable, "throwable");
        rejectFatal(throwable);
        earlyOutcomeSet = true;
        earlyThrowable = throwable;
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
        if (earlyOutcomeSet) {
            throw new HookContractException("proceed() cannot follow an explicit early outcome");
        }
        proceedState = CallOutcome.State.RUNNING;
        try {
            Object value = accessor.proceed(chain, argsForProceed());
            proceedOutcome = CallOutcome.returned(value);
            proceedState = CallOutcome.State.RETURNED;
        } catch (Throwable throwable) {
            proceedOutcome = CallOutcome.threw(throwable);
            proceedState = CallOutcome.State.THREW;
            rejectFatal(throwable);
        }
        return proceedOutcome;
    }

    @Override
    public CallOutcome.State proceedState() {
        check();
        return proceedState;
    }

    /**
     * A distinct lease for one callback, sharing only the enclosing frame's invocation state. Before
     * callbacks get exactly this type, which never implements {@link InvocationResult}, so a cast
     * cannot reach the result slot.
     */
    class BorrowedInvocation implements AroundInvocation, EntrySnapshotInvocation, MutableArgsInvocation {
        private boolean active = true;

        final void invalidate() {
            active = false;
        }

        final void checkBorrow() {
            if (!active) {
                // Preserve frame-expired / wrong-thread precedence for an inactive borrow.
                InvocationFrame.this.check();
                throw new HookContractException("invocation is no longer valid: the callback already returned");
            }
            // Every public forwarding method validates the frame in its target method. Avoid a
            // duplicate owner-thread and validity check while this particular borrow is active.
        }

        @Override public HookId id() { checkBorrow(); return InvocationFrame.this.id(); }
        @Override public long generation() { checkBorrow(); return InvocationFrame.this.generation(); }
        @Override public Executable target() { checkBorrow(); return InvocationFrame.this.target(); }
        @Override public Object thisObject() { checkBorrow(); return InvocationFrame.this.thisObject(); }
        @Override public Object constructorReceiver() { checkBorrow(); return InvocationFrame.this.constructorReceiver(); }
        @Override public ParameterAccess access() { checkBorrow(); return InvocationFrame.this.access(); }
        @Override public Thread ownerThread() { checkBorrow(); return InvocationFrame.this.ownerThread(); }
        @Override public int argumentCount() { checkBorrow(); return InvocationFrame.this.argumentCount(); }
        @Override public Object getArgument(int index) { checkBorrow(); return InvocationFrame.this.getArgument(index); }
        @Override public Class<?> argumentType(int index) { checkBorrow(); return InvocationFrame.this.argumentType(index); }
        @Override public Object getEntryArgument(int index) { checkBorrow(); return InvocationFrame.this.getEntryArgument(index); }
        @Override public Object[] entrySnapshot() { checkBorrow(); return InvocationFrame.this.entrySnapshot(); }
        @Override public void setArgument(int index, Object value) { checkBorrow(); InvocationFrame.this.setArgument(index, value); }
        @Override public void setEarlyResult(Object value) { checkBorrow(); InvocationFrame.this.setEarlyResult(value); }
        @Override public void setEarlyThrowable(Throwable throwable) { checkBorrow(); InvocationFrame.this.setEarlyThrowable(throwable); }
        @Override public boolean hasEarlyOutcome() { checkBorrow(); return InvocationFrame.this.hasEarlyOutcome(); }
        @Override public CallOutcome proceed() { checkBorrow(); return InvocationFrame.this.proceed(); }
        @Override public CallOutcome.State proceedState() { checkBorrow(); return InvocationFrame.this.proceedState(); }
    }

    /**
     * After-callback lease passed as both the invocation and the result argument. Merging the two
     * views removes one object per after callback without widening any shape: an after callback
     * already holds the result view, and before, around and frame views never implement
     * {@link InvocationResult}. Both views expire together when the callback returns.
     */
    final class AfterBorrow extends BorrowedInvocation implements InvocationResult {
        @Override public boolean hasReturned() { checkBorrow(); return resultSlot.hasReturned(); }
        @Override public boolean hasThrown() { checkBorrow(); return resultSlot.hasThrown(); }
        @Override public Object getResult() { checkBorrow(); return resultSlot.getResult(); }
        @Override public Throwable getThrowable() { checkBorrow(); return resultSlot.getThrowable(); }
        @Override public void setResult(Object value) { checkBorrow(); resultSlot.setResult(value); }
        @Override public void setThrowable(Throwable throwable) { checkBorrow(); resultSlot.setThrowable(throwable); }
    }

    /** After-stage result slot. Initial state is the downstream or early outcome. */
    final class ResultSlot implements InvocationResult {
        private CallOutcome.State state = CallOutcome.State.NOT_CALLED;
        private Object value;
        private Throwable throwable;

        void initReturned(Object result) {
            state = CallOutcome.State.RETURNED;
            value = result;
            throwable = null;
        }

        void initThrown(Throwable failure) {
            state = CallOutcome.State.THREW;
            value = null;
            throwable = failure;
        }

        Object dispatchValue() throws Throwable {
            if (state == CallOutcome.State.THREW) throw throwable;
            return state == CallOutcome.State.RETURNED ? value : null;
        }

        DispatchResult dispatchResult() {
            return state == CallOutcome.State.THREW
                    ? DispatchResult.threw(throwable)
                    : DispatchResult.returned(state == CallOutcome.State.RETURNED ? value : null);
        }

        void restoreOriginal(Throwable original) {
            initThrown(original);
        }

        @Override
        public boolean hasReturned() {
            check();
            return state == CallOutcome.State.RETURNED;
        }

        @Override
        public boolean hasThrown() {
            check();
            return state == CallOutcome.State.THREW;
        }

        @Override
        public Object getResult() {
            check();
            return state == CallOutcome.State.RETURNED ? value : null;
        }

        @Override
        public Throwable getThrowable() {
            check();
            return state == CallOutcome.State.THREW ? throwable : null;
        }

        @Override
        public void setResult(Object value) {
            check();
            TypeChecks.checkResultValue(plan, value);
            initReturned(value);
        }

        @Override
        public void setThrowable(Throwable throwable) {
            check();
            Objects.requireNonNull(throwable, "throwable");
            rejectFatal(throwable);
            initThrown(throwable);
        }
    }
}
