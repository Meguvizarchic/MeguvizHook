package io.github.jichuo1.meguviz.hook.benchmark.install;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Install-time cost of a module that hooks several descriptor targets on one large class, the way
 * a module does at process start. Each operation creates an environment over a capturing backend
 * (no framework hooking cost), installs {@link #hooks} descriptor specs on {@link #owner}, then
 * closes the environment. Framework hook installation itself is not modeled, so the numbers show
 * only the SDK and resolver share of startup work.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
public class InstallBatchBenchmark {
    @Param({"java.lang.String", "java.util.Collections"})
    public String owner;

    @Param({"1", "20"})
    public int hooks;

    private final BeforeCallback callback = invocation -> { };
    private List<HookSpec> specs;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        Class<?> type = Class.forName(owner);
        // Distinct, non-overloaded public names keep every spec UNIQUE and installable.
        Map<String, Method> unique = new LinkedHashMap<String, Method>();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge() || !Modifier.isPublic(method.getModifiers())) continue;
            counts.merge(method.getName(), 1, Integer::sum);
            unique.put(method.getName(), method);
        }
        specs = new ArrayList<HookSpec>();
        for (Map.Entry<String, Method> entry : unique.entrySet()) {
            if (counts.get(entry.getKey()) != 1) continue;
            Method method = entry.getValue();
            List<String> parameters = new ArrayList<String>();
            for (Class<?> parameter : method.getParameterTypes()) parameters.add(parameter.getName());
            TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, owner, method.getName())
                    .parameters(parameters.toArray(new String[0]))
                    .returning(method.getReturnType().getName())
                    .build();
            specs.add(HookSpec.builder("bench.install." + specs.size(), "bench", target).before(callback).build());
            if (specs.size() == hooks) break;
        }
        if (specs.size() != hooks) throw new IllegalStateException("only " + specs.size() + " unique targets on " + owner);
    }

    @Benchmark
    public int installSequentially() {
        HookEnvironment environment = HookEnvironment.create(new CapturingBackend());
        int installed = 0;
        try {
            for (HookSpec spec : specs) {
                RegistrationReceipt receipt = environment.install(spec);
                if (!receipt.isInstalled()) throw new IllegalStateException(receipt.toString());
                installed++;
            }
        } finally {
            environment.close();
        }
        return installed;
    }
}
