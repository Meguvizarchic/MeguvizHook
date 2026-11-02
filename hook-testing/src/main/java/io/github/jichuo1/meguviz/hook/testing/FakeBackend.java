package io.github.jichuo1.meguviz.hook.testing;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test fake of a framework-chain backend. It proves this SDK's own contract only; it is not a
 * runtime qualification of any real framework, manager or ART backend.
 *
 * <p>The fake reproduces the parts the core depends on: a continuation that reaches the original
 * at most once per SDK invocation, argument slots owned by the invocation, an uninstall handle, and a code
 * epoch the test can advance. Higher priorities run first; ties use installation order as a fake-only
 * deterministic rule, not a claim about a real manager. Each call uses an immutable chain snapshot;
 * already retired SDK channels pass through to the next snapshot entry. Real manager ordering,
 * classloading and device behaviour remain outside this fake's qualification.
 */
public final class FakeBackend implements HookBackend {
    /** Behaviour of the "original method" behind a target. */
    public interface OriginalBehavior {
        Object invoke(Object thisObject, Object[] args) throws Throwable;
    }

    private final String backendId;
    private BackendCapabilities capabilities;
    private final FakeContext context;
    private final ConcurrentHashMap<Executable, List<FakeHook>> hooks = new ConcurrentHashMap<Executable, List<FakeHook>>();
    private final Map<Executable, OriginalBehavior> originals = new java.util.concurrent.ConcurrentHashMap<Executable, OriginalBehavior>();
    private final Map<Executable, AtomicInteger> originalCalls = new java.util.concurrent.ConcurrentHashMap<Executable, AtomicInteger>();
    private final AtomicInteger argumentMaterializations = new AtomicInteger();
    private final AtomicInteger installCount = new AtomicInteger();
    private final AtomicInteger replaceCount = new AtomicInteger();
    private boolean residentOnClose;
    private boolean failNextInstall;
    private boolean failAllInstalls;
    private boolean failNextReplace;
    private boolean bumpEpochOnNextInstall;

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
                .declare(Capability.ATOMIC_REPLACE, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, backendId, "fake-1", "jvm-fake",
                        "atomic compare-and-swap replacement; not a device cell"))
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

    public FakeBackend withFailingAllInstalls() {
        this.failAllInstalls = true;
        return this;
    }

    public FakeBackend withFailingNextReplace() {
        this.failNextReplace = true;
        return this;
    }

    public FakeBackend withEpochBumpOnNextInstall() {
        this.bumpEpochOnNextInstall = true;
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

    public int replaceCount() {
        return replaceCount.get();
    }

    public boolean isHooked(Executable target) {
        return hooks.containsKey(target);
    }

    /** Simulates a host call, whether or not a hook is installed. */
    public Object call(Executable target, Object thisObject, Object... args) throws Throwable {
        List<FakeHook> snapshot = hooks.get(target);
        if (snapshot == null) {
            return invokeOriginal(target, thisObject, args);
        }
        return dispatch(snapshot, 0, target, thisObject, args);
    }

    private Object dispatch(List<FakeHook> snapshot, int index, Executable target,
                            Object thisObject, Object[] args) throws Throwable {
        if (index == snapshot.size()) {
            return invokeOriginal(target, thisObject, args);
        }
        DispatchResult result = snapshot.get(index).channel.dispatch(
                new FakeInvocation(target, thisObject, args, snapshot, index + 1));
        switch (result.kind()) {
            case RETURNED:
                return result.value();
            case THREW:
                throw result.throwable();
            case PASSTHROUGH:
            default:
                return dispatch(snapshot, index + 1, target, thisObject, args);
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
        if (plan.spec().errorPolicy() != ErrorPolicy.PROPAGATE) {
            throw new BackendInstallException("fake backend does not model framework-protective behavior");
        }
        if (failAllInstalls || failNextInstall) {
            failNextInstall = false;
            throw new BackendInstallException("fake install failure requested by the test");
        }
        installCount.incrementAndGet();
        FakeHook hook = new FakeHook(channel, plan.spec().priority());
        hooks.compute(plan.target(), (target, current) -> {
            List<FakeHook> next = current == null ? new ArrayList<FakeHook>() : new ArrayList<FakeHook>(current);
            next.add(hook);
            // List sort is stable, so equal priorities follow compute's install order.
            Collections.sort(next, new Comparator<FakeHook>() {
                @Override public int compare(FakeHook left, FakeHook right) {
                    return Integer.compare(right.priority, left.priority);
                }
            });
            return Collections.unmodifiableList(next);
        });
        if (bumpEpochOnNextInstall) {
            bumpEpochOnNextInstall = false;
            context.epoch++;
        }
        return new FakeHandle(plan.target(), hook);
    }

    private final class FakeHandle implements BackendHandle {
        private final Executable target;
        private final FakeHook hook;

        private FakeHandle(Executable target, FakeHook hook) {
            this.target = target;
            this.hook = hook;
        }

        @Override
        public BackendCloseReport uninstall() {
            hooks.computeIfPresent(target, (ignored, current) -> {
                int position = indexOfIdentity(current, hook);
                if (position < 0) return current;
                List<FakeHook> next = new ArrayList<FakeHook>(current);
                next.remove(position);
                return next.isEmpty() ? null : Collections.unmodifiableList(next);
            });
            return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                    CloseReport.PhysicalRelease.NOT_CLAIMED, residentOnClose,
                    residentOnClose ? "fake stays resident" : "fake entry removed; no physical release claim");
        }

        @Override
        public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            if (plan.spec().errorPolicy() != ErrorPolicy.PROPAGATE) {
                throw new BackendInstallException("fake backend does not model framework-protective behavior");
            }
            replaceCount.incrementAndGet();
            if (failNextReplace) {
                failNextReplace = false;
                throw new BackendInstallException("fake replacement failure requested by the test");
            }
            if (!target.equals(plan.target()) || plan.spec().priority() != hook.priority) {
                throw new BackendInstallException("replacement must retain the target and priority");
            }
            FakeHook replacement = new FakeHook(channel, hook.priority);
            AtomicBoolean replaced = new AtomicBoolean();
            hooks.computeIfPresent(target, (ignored, current) -> {
                int position = indexOfIdentity(current, hook);
                if (position < 0) return current;
                List<FakeHook> next = new ArrayList<FakeHook>(current);
                next.set(position, replacement);
                replaced.set(true);
                return Collections.unmodifiableList(next);
            });
            if (!replaced.get()) {
                throw new BackendInstallException("old fake hook is no longer current");
            }
            return new FakeHandle(plan.target(), replacement);
        }
    }

    private static final class FakeHook {
        private final DispatchChannel channel;
        private final int priority;

        private FakeHook(DispatchChannel channel, int priority) {
            this.channel = channel;
            this.priority = priority;
        }
    }

    private static int indexOfIdentity(List<FakeHook> hooks, FakeHook expected) {
        for (int i = 0; i < hooks.size(); i++) {
            if (hooks.get(i) == expected) return i;
        }
        return -1;
    }

    private final class FakeInvocation implements BackendInvocation {
        private final Executable target;
        private final Object thisObject;
        private final Object[] args;
        private final List<FakeHook> snapshot;
        private final int nextIndex;

        private FakeInvocation(Executable target, Object thisObject, Object[] args,
                               List<FakeHook> snapshot, int nextIndex) {
            this.target = target;
            this.thisObject = thisObject;
            this.args = args;
            this.snapshot = snapshot;
            this.nextIndex = nextIndex;
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
            return args.clone();
        }

        @Override
        public Object proceed(Object[] arguments) throws Throwable {
            return dispatch(snapshot, nextIndex, target, thisObject, arguments == null ? args : arguments);
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
