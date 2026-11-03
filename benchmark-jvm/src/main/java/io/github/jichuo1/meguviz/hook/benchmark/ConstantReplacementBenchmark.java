package io.github.jichuo1.meguviz.hook.benchmark;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.FusedConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** Smoke benchmark for the fused constant channel; validates the harness wiring. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class ConstantReplacementBenchmark {
    public static long target() { return 1L; }

    private HookEnvironment environment;
    private FusedConstantDispatchChannel fused;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        CapturingBackend backend = new CapturingBackend();
        environment = HookEnvironment.create(backend);
        Method method = ConstantReplacementBenchmark.class.getMethod("target");
        RegistrationReceipt receipt = environment.install(
                HookSpec.forMember("bench.constant", "bench", method).replaceConstant(42L).build());
        if (!receipt.isInstalled()) throw new IllegalStateException(receipt.toString());
        DispatchChannel channel = backend.lastChannel();
        if (!(channel instanceof FusedConstantDispatchChannel)) {
            throw new IllegalStateException("expected the fused constant channel: " + channel);
        }
        fused = (FusedConstantDispatchChannel) channel;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        environment.close();
    }

    @Benchmark
    public Object fusedConstant() {
        return fused.dispatchValueOrNotEntered();
    }
}
