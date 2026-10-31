package io.github.jichuo1.meguviz.hook.testing;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;

import java.lang.reflect.Executable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test fake of a framework-chain backend. It proves this SDK's own contract only; it is not a
 * runtime qualification of any real framework, manager or ART backend.
 *
 * <p>The fake reproduces the parts the core depends on: a continuation that runs the original
 * exactly once per call, argument slots owned by the invocation, an uninstall handle, and a code
 * epoch the test can advance. Ordering or classloading behaviour of a real manager is out of scope.
 */
public final class FakeBackend implements HookBackend {
    /** Behaviour of the "original method" behind a target. */
    public interface OriginalBehavior {
        Object invoke(Object thisObject, Object[] args) throws Throwable;
    }

    private final String backendId;
    private BackendCapabilities capabilities;
    private final FakeContext context;
    private final Map<Executable, FakeHook> hooks = new java.util.concurrent.ConcurrentHashMap<Executable, FakeHook>();
    private final Map<Executable, OriginalBehavior> originals = new java.util.concurrent.ConcurrentHashMap<Executable, OriginalBehavior>();
    private final Map<Executable, AtomicInteger> originalCalls = new java.util.concurrent.ConcurrentHashMap<Executable, AtomicInteger>();
    private final AtomicInteger argumentMaterializations = new AtomicInteger();
    private final AtomicInteger installCount = new AtomicInteger();
    private boolean residentOnClose;
    private boolean failNextInstall;

    private FakeBackend(String backendId, BackendCapabilities capabilities, FakeContext context) {
        this.backendId = backendId;
        this.capabilities = capabilities;
        this.context = context;
    }

    public static FakeBackend create() {
        return create("fake-chain", ChainSemantics.FRAMEWORK_CHAIN);
    }

    public static FakeBackend create(String backendId, ChainSemantics semantics) {
        BackendCapabilities capabilities = new BackendCapabilities.Builder(backendId, semantics)
                .declare(Capability.FRAMEWORK_CHAIN, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, backendId, "fake-1", "jvm-fake",
                        "fake continuation; not a device cell"))
                .declare(Capability.MODULE_LOCAL_CHAIN, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, backendId, "fake-1", "jvm-fake", "core composed"))
                .declare(Capability.CLASSIC_CALLBACKS, CapabilityResult.unsupported(backendId, "no classic callbacks in the fake"))
                .declare(Capability.ATOMIC_REPLACE, CapabilityResult.unsupported(backendId, "replacement is not modelled"))
                .declare(Capability.HOT_RELOAD, CapabilityResult.unsupported(backendId, "hot reload is not modelled"))
                .build();
        return new FakeBackend(backendId, capabilities, new FakeContext());
    }

    /** Test hook: declare a capability answer, including UNKNOWN, to exercise install-time refusal. */
    public FakeBackend withCapability(Capability capability, CapabilityResult result) {
        Map<Capability, CapabilityResult> copy = new LinkedHashMap<Capability, CapabilityResult>(capabilities.asMap());
        copy.put(capability, result);
        BackendCapabilities.Builder builder = new BackendCapabilities.Builder(backendId, capabilities.chainSemantics());
        for (Map.Entry<Capability, CapabilityResult> entry : copy.entrySet()) {
            builder.declare(entry.getKey(), entry.getValue());
        }
        this.capabilities = builder.build();
        return this;
    }

    public FakeBackend withResidentOnClose(boolean value) {
        this.residentOnClose = value;
        return this;
    }

    public FakeBackend withFailingNextInstall() {
        this.failNextInstall = true;
        return this;
    }

    public void onTarget(Executable target, OriginalBehavior behavior) {
        originals.put(target, behavior);
    }

    public void bumpCodeEpoch() {
        context.epoch++;
    }

    public void setProcessName(String processName) {
        context.processName = processName;
    }

    public void setClassLoader(ClassLoader loader) {
        context.loader = loader;
    }

    public int originalCallCount(Executable target) {
        AtomicInteger counter = originalCalls.get(target);
        return counter == null ? 0 : counter.get();
    }

    /** How often the SDK asked for the argument array; NO_ARGS must keep this at zero. */
    public int argumentMaterializationCount() {
        return argumentMaterializations.get();
    }

    public int installCount() {
        return installCount.get();
    }

    public boolean isHooked(Executable target) {
        return hooks.containsKey(target);
    }

    /** Simulates a host call, whether or not a hook is installed. */
    public Object call(Executable target, Object thisObject, Object... args) throws Throwable {
        FakeHook hook = hooks.get(target);
        if (hook == null) {
            return invokeOriginal(target, thisObject, args);
        }
        DispatchResult result = hook.channel.dispatch(new FakeInvocation(target, thisObject, args));
        switch (result.kind()) {
            case RETURNED:
                return result.value();
            case THREW:
                throw result.throwable();
            case PASSTHROUGH:
            default:
                return invokeOriginal(target, thisObject, args);
        }
    }

    private Object invokeOriginal(Executable target, Object thisObject, Object[] args) throws Throwable {
        originalCalls.computeIfAbsent(target, key -> new AtomicInteger()).incrementAndGet();
        OriginalBehavior behavior = originals.get(target);
        return behavior == null ? null : behavior.invoke(thisObject, args);
    }

    @Override
    public String id() {
        return backendId;
    }

    @Override
    public BackendCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public BackendContext context() {
        return context;
    }

    @Override
    public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
        if (failNextInstall) {
            failNextInstall = false;
            throw new BackendInstallException("fake install failure requested by the test");
        }
        installCount.incrementAndGet();
        FakeHook hook = new FakeHook(channel);
        hooks.put(plan.target(), hook);
        return () -> {
            hooks.remove(plan.target());
            return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                    CloseReport.PhysicalRelease.NOT_CLAIMED, residentOnClose,
                    residentOnClose ? "fake stays resident" : "fake entry removed; no physical release claim");
        };
    }

    private static final class FakeHook {
        private final DispatchChannel channel;

        private FakeHook(DispatchChannel channel) {
            this.channel = channel;
        }
    }

    private final class FakeInvocation implements BackendInvocation {
        private final Executable target;
        private final Object thisObject;
        private final Object[] args;

        private FakeInvocation(Executable target, Object thisObject, Object[] args) {
            this.target = target;
            this.thisObject = thisObject;
            this.args = args;
        }

        @Override
        public Executable target() {
            return target;
        }

        @Override
        public Object thisObject() {
            return thisObject;
        }

        @Override
        public Object[] arguments() {
            argumentMaterializations.incrementAndGet();
            return args;
        }

        @Override
        public Object proceed(Object[] arguments) throws Throwable {
            return invokeOriginal(target, thisObject, arguments == null ? args : arguments);
        }
    }

    /** Mutable backend context: tests can advance the epoch or swap the loader. */
    public static final class FakeContext implements BackendContext {
        private String processName = "fake.process";
        private ClassLoader loader = FakeContext.class.getClassLoader();
        private long epoch = 1L;

        @Override
        public String processName() {
            return processName;
        }

        @Override
        public ClassLoader classLoader() {
            return loader;
        }

        @Override
        public long codeEpoch() {
            return epoch;
        }
    }
}
