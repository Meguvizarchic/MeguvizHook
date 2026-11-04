package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM model of one node of JingMatrix/Vector's {@code VectorChain} (the libxposed 101/102 chain used
 * by the current Vector framework).
 *
 * <ul>
 *   <li>{@link #getArgs()} copies the slots into a new list and wraps it as unmodifiable on every
 *       call, like {@code Collections.unmodifiableList(args.toMutableList())}. The model allocates
 *       the ArrayList, its backing array and the wrapper; the transient Kotlin
 *       {@code ArrayAsCollection} adapter of the real implementation is not modelled, so the model
 *       under-counts a real {@code getArgs()} by at most one small object.</li>
 *   <li>{@link #getArg(int)} reads one slot directly and does not allocate.</li>
 *   <li>{@link #proceed()} and {@link #proceed(Object[])} hand an array downstream without copying
 *       it and record the downstream outcome like {@code executeDownstream}.</li>
 *   <li>A node built with {@link #root} models several framework hooker levels on one executable:
 *       each {@code proceed} allocates the next node and calls the next level's hooker, exactly as
 *       {@code VectorChain.internalProceed} does, until the original runs after the last level.</li>
 * </ul>
 *
 * <p>A node built with the three-argument constructor is positioned as the last hooker before the
 * original (single level). Not modelled: the native trampoline, the receiver/argument split of
 * {@code VectorNativeHooker}, and the framework's per-call root node for the single-level form
 * (benchmarks reuse one instance, which is equivalent for the SDK because it never retains the chain
 * after dispatch). PROTECTIVE recovery is not modelled; the levels behave as PASSTHROUGH.
 */
public final class VectorLikeChain {
    /** Downstream original method. */
    public interface Terminal {
        Object invoke(Object thisObject, Object[] args) throws Throwable;
    }

    /** One framework hooker level ({@code XposedInterface.Hooker}). */
    public interface Level {
        Object intercept(VectorLikeChain chain) throws Throwable;
    }

    private final Object thisObject;
    private final Object[] args;
    private final Level[] levels;
    private final int levelIndex;
    private final Terminal terminal;
    private boolean proceedCalled;
    private Object downstreamResult;
    private Throwable downstreamThrowable;

    public VectorLikeChain(Object thisObject, Object[] args, Terminal terminal) {
        this(thisObject, args, null, 0, terminal);
    }

    private VectorLikeChain(Object thisObject, Object[] args, Level[] levels, int levelIndex, Terminal terminal) {
        this.thisObject = thisObject;
        this.args = args;
        this.levels = levels;
        this.levelIndex = levelIndex;
        this.terminal = terminal;
    }

    /** The per-call root node of a multi-level chain; {@code proceed()} enters the first level. */
    public static VectorLikeChain root(Object thisObject, Object[] args, Level[] levels, Terminal terminal) {
        return new VectorLikeChain(thisObject, args, levels, 0, terminal);
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
        Level[] chainLevels = levels;
        try {
            Object result;
            if (chainLevels == null || levelIndex >= chainLevels.length) {
                result = terminal.invoke(thisObject, currentArgs);
            } else {
                VectorLikeChain next = new VectorLikeChain(thisObject, currentArgs, chainLevels, levelIndex + 1, terminal);
                result = chainLevels[levelIndex].intercept(next);
            }
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
