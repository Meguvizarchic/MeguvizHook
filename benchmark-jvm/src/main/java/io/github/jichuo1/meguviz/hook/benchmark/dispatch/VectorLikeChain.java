package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM model of one node of JingMatrix/Vector's {@code VectorChain} (the libxposed 101/102 chain used
 * by the current Vector framework), positioned as the last hooker before the original method.
 *
 * <ul>
 *   <li>{@link #getArgs()} copies the slots into a new list and wraps it as unmodifiable on every
 *       call, like {@code Collections.unmodifiableList(args.toMutableList())}. The model allocates
 *       the ArrayList, its backing array and the wrapper; the transient Kotlin
 *       {@code ArrayAsCollection} adapter of the real implementation is not modelled, so the model
 *       under-counts a real {@code getArgs()} by at most one small object.</li>
 *   <li>{@link #getArg(int)} reads one slot directly and does not allocate.</li>
 *   <li>{@link #proceed()} and {@link #proceed(Object[])} hand an array to the terminal without
 *       copying it, and record the downstream outcome like {@code executeDownstream}.</li>
 * </ul>
 *
 * <p>Not modelled: the native trampoline, the new chain node Vector allocates per hooker level, and
 * the framework's per-call chain object (benchmarks reuse one instance, which is equivalent for the
 * SDK because it never retains the chain after dispatch).
 */
public final class VectorLikeChain {
    /** Downstream original method. */
    public interface Terminal {
        Object invoke(Object thisObject, Object[] args) throws Throwable;
    }

    private final Object thisObject;
    private final Object[] args;
    private final Terminal terminal;
    private boolean proceedCalled;
    private Object downstreamResult;
    private Throwable downstreamThrowable;

    public VectorLikeChain(Object thisObject, Object[] args, Terminal terminal) {
        this.thisObject = thisObject;
        this.args = args;
        this.terminal = terminal;
    }

    public Object getThisObject() {
        return thisObject;
    }

    public List<Object> getArgs() {
        ArrayList<Object> copy = new ArrayList<Object>(args.length);
        Collections.addAll(copy, args);
        return Collections.unmodifiableList(copy);
    }

    public Object getArg(int index) {
        return args[index];
    }

    public Object proceed() throws Throwable {
        return internalProceed(args);
    }

    public Object proceed(Object[] currentArgs) throws Throwable {
        return internalProceed(currentArgs);
    }

    private Object internalProceed(Object[] currentArgs) throws Throwable {
        proceedCalled = true;
        try {
            Object result = terminal.invoke(thisObject, currentArgs);
            downstreamResult = result;
            downstreamThrowable = null;
            return result;
        } catch (Throwable failure) {
            downstreamResult = null;
            downstreamThrowable = failure;
            throw failure;
        }
    }

    boolean proceedCalled() {
        return proceedCalled;
    }
}
