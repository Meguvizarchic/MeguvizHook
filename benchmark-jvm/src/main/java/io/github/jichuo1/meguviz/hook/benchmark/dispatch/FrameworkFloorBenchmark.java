package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Floor of the chain model without the SDK (an A0-style raw hooker), plus the raw cost of the two
 * argument-access strategies the backends can use. Host JDK microbenchmark only, not ART evidence.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class FrameworkFloorBenchmark {
    private final VectorLikeChain chain = new VectorLikeChain(null, DispatchTargets.arguments(), DispatchTargets.ADD);

    @Benchmark
    public Object rawProceed() throws Throwable {
        return chain.proceed();
    }

    @Benchmark
    public Object[] getArgsToArray() {
        return chain.getArgs().toArray();
    }

    @Benchmark
    public Object[] getArgLoop() {
        Object[] values = new Object[2];
        for (int i = 0; i < 2; i++) values[i] = chain.getArg(i);
        return values;
    }
}
