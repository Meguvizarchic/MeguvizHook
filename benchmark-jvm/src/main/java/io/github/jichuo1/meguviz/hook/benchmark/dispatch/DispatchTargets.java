package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

/** Benchmark target and its modelled original-method terminal. */
public final class DispatchTargets {
    private DispatchTargets() {
    }

    public static int add(int left, int right) {
        return left + right;
    }

    /** Calls through the slots the chain passes downstream; small results stay in the Integer cache. */
    static final VectorLikeChain.Terminal ADD = (thisObject, args) -> (Integer) args[0] + (Integer) args[1];

    static Object[] arguments() {
        return new Object[]{3, 4};
    }
}
