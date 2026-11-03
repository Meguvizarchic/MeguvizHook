package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.benchmark.CapturingBackend;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.FusedConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Thread scaling of one registration hit by 1, 2, 4 or 8 threads at once: every thread drives the
 * same channel (and therefore the same registration state and in-flight accounting) through a
 * fresh chain node per call. Callbacks and the modelled original write no shared state, so the only
 * shared writes are the SDK's own. B/op includes the modelled chain node unless the JIT removes it.
 * Callback call sites are made megamorphic first ({@link ProfilePollution}).
 *
 * <p>Host JDK microbenchmark on an 8-vCPU (4 cores x 2 SMT) x86-64 host; not ART or device evidence.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class RegistrationContentionBenchmark {
    public enum Shape {
        /** {@code replaceConstant}: the backend's fused admission + value + exit call. */
        FUSED_CONSTANT,
        /** One before callback, NO_ARGS, through the backend hooker's preferred transport. */
        SINGLE_BEFORE,
        /** One around callback that proceeds once. */
        AROUND
    }

    @Param
    public Shape shape;

    @Param({"GET_ARG"})
    public ChainAdapter adapter;

    private HookEnvironment environment;
    private VectorLikeChain.Level hooker;

    /**
     * Per-thread arguments. Each call gets a fresh chain node, as Vector allocates one per call and
     * hooker level: a node reused per thread is written on every proceed, and once the collector
     * copies such long-lived nodes next to each other their lines are falsely shared between
     * threads, which made unrelated forks differ by up to 5x.
     */
    @State(Scope.Thread)
    public static class Caller {
        final Object[] args = DispatchTargets.arguments();

        VectorLikeChain chain() {
            return new VectorLikeChain(null, args, DispatchTargets.ADD);
        }
    }

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setUp() throws Throwable {
        ProfilePollution.pollute(adapter);
        CapturingBackend backend = new CapturingBackend();
        environment = HookEnvironment.create(backend);
        Method target = DispatchTargets.class.getMethod("add", int.class, int.class);
        HookSpec.Builder builder = HookSpec.forMember("bench.contention." + shape, "bench", target);
        int expected;
        switch (shape) {
            case FUSED_CONSTANT:
                builder.replaceConstant(42);
                expected = 42;
                break;
            case SINGLE_BEFORE:
                builder.before(invocation -> { });
                expected = 7;
                break;
            case AROUND:
            default:
                builder.around(invocation -> invocation.proceed());
                expected = 7;
                break;
        }
        RegistrationReceipt receipt = environment.install(builder.build());
        if (!receipt.isInstalled()) throw new IllegalStateException(receipt.toString());
        DispatchChannel channel = backend.lastChannel();
        if (shape == Shape.FUSED_CONSTANT) {
            // Mirrors the fused branch of LibXposed101Backend / LibXposed102Backend.
            FusedConstantDispatchChannel fused = (FusedConstantDispatchChannel) channel;
            hooker = chain -> {
                Object value = fused.dispatchValueOrNotEntered();
                return value == FusedConstantDispatchChannel.NOT_ENTERED ? chain.proceed() : value;
            };
        } else {
            hooker = adapter.hooker(backend.lastPlan(), channel);
        }
        Object value = hooker.intercept(new Caller().chain());
        if (!Integer.valueOf(expected).equals(value)) throw new IllegalStateException(shape + " returned " + value);
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Trial)
    public void tearDown() {
        environment.close();
    }

    @Benchmark @Threads(1)
    public Object threads1(Caller caller) throws Throwable {
        return hooker.intercept(caller.chain());
    }

    @Benchmark @Threads(2)
    public Object threads2(Caller caller) throws Throwable {
        return hooker.intercept(caller.chain());
    }

    @Benchmark @Threads(4)
    public Object threads4(Caller caller) throws Throwable {
        return hooker.intercept(caller.chain());
    }

    @Benchmark @Threads(8)
    public Object threads8(Caller caller) throws Throwable {
        return hooker.intercept(caller.chain());
    }
}
