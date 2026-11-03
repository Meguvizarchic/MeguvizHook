package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.benchmark.CapturingBackend;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Per-invocation cost of one registration shape, from the framework's hooker entry to its return:
 * backend adapter, channel, dispatcher, callbacks and the modelled original method. Specs are
 * installed through {@link HookEnvironment} and {@link CapturingBackend}; the captured channel is
 * driven exactly like a libxposed 101/102 hooker would drive it (see {@link ChainAdapter}).
 *
 * <p>Host JDK microbenchmark only. It is not ART, device or framework evidence: HotSpot inlining and
 * escape analysis differ from ART. Each fork measures one shape; {@link #profile} chooses whether
 * the callback call sites were made megamorphic first.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
public class DispatchShapeBenchmark {
    @Param
    public DispatchShape shape;

    @Param
    public ChainAdapter adapter;

    /**
     * MEGA first drives other registrations with distinct callback classes, so the SDK's callback
     * call sites are megamorphic as in a real module (see {@link ProfilePollution}). MONO measures
     * the single shape alone, which lets HotSpot inline and scalar-replace far more than ART does.
     */
    @Param({"MEGA", "MONO"})
    public String profile;

    private HookEnvironment environment;
    private ChainAdapter.ModelHooker hooker;
    private VectorLikeChain chain;

    @Setup(Level.Trial)
    public void setUp() throws Throwable {
        if ("MEGA".equals(profile)) ProfilePollution.pollute(adapter);
        CapturingBackend backend = new CapturingBackend();
        environment = HookEnvironment.create(backend);
        Method target = DispatchTargets.class.getMethod("add", int.class, int.class);
        HookSpec spec = shape.configure(HookSpec.forMember("bench.dispatch." + shape, "bench", target)).build();
        RegistrationReceipt receipt = environment.install(spec);
        if (!receipt.isInstalled()) throw new IllegalStateException(receipt.toString());
        hooker = adapter.hooker(backend.lastPlan(), backend.lastChannel());
        chain = new VectorLikeChain(null, DispatchTargets.arguments(), DispatchTargets.ADD);
        Object value = hooker.intercept(chain);
        if (!Integer.valueOf(shape.expected()).equals(value) || !chain.proceedCalled()) {
            throw new IllegalStateException(shape + " returned " + value);
        }
        System.out.println("[dispatch] shape=" + shape + " adapter=" + adapter + " profile=" + profile
                + " channel=" + backend.lastChannel().getClass().getSimpleName()
                + " directTransport=" + hooker.usesDirectTransport()
                + " chainTransport=" + hooker.usesChainTransport());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        environment.close();
    }

    @Benchmark
    public Object invoke() throws Throwable {
        return hooker.intercept(chain);
    }
}
