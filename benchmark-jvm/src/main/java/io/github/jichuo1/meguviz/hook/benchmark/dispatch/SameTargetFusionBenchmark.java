package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.benchmark.CapturingBackend;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Research benchmark for "same-target fusion": N callbacks on one executable, either as N
 * independent registrations (N framework hooker levels, each with its own chain node, backend
 * adapter and SDK frame) or as one registration whose spec carries all N callbacks (one level,
 * one frame, one borrow per callback). Each call starts from a fresh root node as Vector's native
 * hooker does. Callback call sites are made megamorphic first ({@link ProfilePollution}).
 *
 * <p>Host JDK microbenchmark only, not ART or device evidence. It measures cost, not equivalence:
 * the two layouts differ in lifecycle, ordering and failure isolation (see
 * docs/research/hotpath-round-2.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class SameTargetFusionBenchmark {
    public enum Layout {
        /** N registrations, N framework hooker levels. */
        SEPARATE,
        /** One registration with N callbacks of the same kind. */
        ONE_SPEC
    }

    public enum Work {
        /** Each callback is a NO_ARGS before that writes one field. */
        BEFORE,
        /** Each callback is an after that reads the result. */
        AFTER
    }

    @Param({"1", "2", "4", "8"})
    public int callbacks;

    @Param
    public Layout layout;

    @Param
    public Work work;

    @Param({"GET_ARG"})
    public ChainAdapter adapter;

    private HookEnvironment environment;
    private VectorLikeChain.Level[] levels;
    private final Object[] args = DispatchTargets.arguments();

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setUp() throws Throwable {
        ProfilePollution.pollute(adapter);
        CapturingBackend backend = new CapturingBackend();
        environment = HookEnvironment.create(backend);
        Method target = DispatchTargets.class.getMethod("add", int.class, int.class);
        List<VectorLikeChain.Level> installed = new ArrayList<VectorLikeChain.Level>();
        if (layout == Layout.SEPARATE) {
            for (int i = 0; i < callbacks; i++) {
                HookSpec.Builder builder = HookSpec.forMember("bench.fusion." + work + "." + i, "bench", target);
                install(backend, add(builder, i).build(), installed);
            }
        } else {
            HookSpec.Builder builder = HookSpec.forMember("bench.fusion." + work + ".all", "bench", target);
            for (int i = 0; i < callbacks; i++) add(builder, i);
            install(backend, builder.build(), installed);
        }
        levels = installed.toArray(new VectorLikeChain.Level[0]);
        Object value = invoke();
        if (!Integer.valueOf(7).equals(value)) throw new IllegalStateException(layout + " returned " + value);
    }

    private HookSpec.Builder add(HookSpec.Builder builder, int index) {
        return work == Work.BEFORE ? builder.before(before(index)) : builder.after(after(index));
    }

    /** Distinct callback instances of one class in both layouts, so call-site shapes match. */
    private static BeforeCallback before(int index) {
        return invocation -> DispatchShape.Sink.SINK.calls += index;
    }

    private static AfterCallback after(int index) {
        return (invocation, result) -> DispatchShape.Sink.SINK.value = index == 0 ? result.getResult() : null;
    }

    private void install(CapturingBackend backend, HookSpec spec, List<VectorLikeChain.Level> installed) {
        RegistrationReceipt receipt = environment.install(spec);
        if (!receipt.isInstalled()) throw new IllegalStateException(receipt.toString());
        installed.add(adapter.hooker(backend.lastPlan(), backend.lastChannel()));
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Trial)
    public void tearDown() {
        environment.close();
    }

    @Benchmark
    public Object invoke() throws Throwable {
        return VectorLikeChain.root(null, args, levels, DispatchTargets.ADD).proceed();
    }
}
