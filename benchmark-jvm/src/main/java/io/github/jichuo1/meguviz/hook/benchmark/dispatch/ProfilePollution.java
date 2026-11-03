package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.AroundCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.benchmark.CapturingBackend;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes the SDK's user-callback call sites megamorphic before measurement, as they are in a module
 * with many hooks. Without it, a single-shape JMH fork lets HotSpot inline the one callback lambda
 * and scalar-replace the per-call frame, which no production module (and no ART build) sees.
 * Every core path (single before, before + after, single after, around) is driven with three
 * distinct callback classes each, through the same adapter as the measurement so that backend
 * invocation call sites stay monomorphic, as they are with one backend per process.
 */
final class ProfilePollution {
    private static final int CALLS = 20_000;

    private ProfilePollution() {
    }

    static void pollute(ChainAdapter adapter) throws Throwable {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment environment = HookEnvironment.create(backend);
        Method target = DispatchTargets.class.getMethod("add", int.class, int.class);
        BeforeCallback[] befores = {
                invocation -> Sink.calls++,
                invocation -> Sink.calls += 2,
                invocation -> Sink.value = invocation.access()};
        AfterCallback[] afters = {
                (invocation, result) -> Sink.calls++,
                (invocation, result) -> Sink.value = result.getResult(),
                (invocation, result) -> Sink.value = result.hasThrown() ? null : invocation.access()};
        AroundCallback[] arounds = {
                invocation -> invocation.proceed(),
                invocation -> Sink.value = invocation.proceed(),
                invocation -> { Sink.calls++; invocation.proceed(); }};
        List<HookSpec> specs = new ArrayList<HookSpec>();
        for (int i = 0; i < 3; i++) {
            specs.add(HookSpec.forMember("pollute.before." + i, "bench", target).before(befores[i]).build());
            specs.add(HookSpec.forMember("pollute.after." + i, "bench", target).after(afters[i]).build());
            specs.add(HookSpec.forMember("pollute.both." + i, "bench", target)
                    .before(befores[i]).after(afters[i]).build());
            specs.add(HookSpec.forMember("pollute.around." + i, "bench", target).around(arounds[i]).build());
        }
        try {
            List<ChainAdapter.ModelHooker> hookers = new ArrayList<ChainAdapter.ModelHooker>();
            for (HookSpec spec : specs) {
                RegistrationReceipt receipt = environment.install(spec);
                if (!receipt.isInstalled()) throw new IllegalStateException(receipt.toString());
                hookers.add(adapter.hooker(backend.lastPlan(), backend.lastChannel()));
            }
            VectorLikeChain chain = new VectorLikeChain(null, DispatchTargets.arguments(), DispatchTargets.ADD);
            for (int round = 0; round < CALLS; round++) {
                for (ChainAdapter.ModelHooker hooker : hookers) hooker.intercept(chain);
            }
        } finally {
            environment.close();
        }
    }

    /** Separate sink so polluting callbacks never touch the measured shape's state. */
    static final class Sink {
        static long calls;
        static Object value;
    }
}
