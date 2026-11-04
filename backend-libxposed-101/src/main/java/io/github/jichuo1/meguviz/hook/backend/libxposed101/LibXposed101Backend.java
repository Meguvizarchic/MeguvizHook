package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.ConditionalDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.ConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.FusedConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.SuccessResultDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Executable;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Adapter for the real libxposed API 101.0.0 artifact.
 *
 * <p>Source verification corrected an earlier planning assumption: 101 already exposes the same
 * {@code Hooker}/{@code Chain} interception model as 102. It is therefore a FRAMEWORK_CHAIN
 * backend, not a classic-callback emulation. The 101 adapter deliberately has no references to
 * 102-only {@code setId}, {@code replaceHook}, {@code API_102} or hot-reload entry types.
 *
 * <p>PROPAGATE maps explicitly to {@code ExceptionMode.PASSTHROUGH}; the opt-in
 * FRAMEWORK_PROTECTIVE policy maps to {@code ExceptionMode.PROTECTIVE} and leaves recovery to the
 * framework. Real framework loading and ART behaviour remain unverified until a device cell is run.
 */
public final class LibXposed101Backend implements HookBackend {
    public static final String BACKEND_ID = "libxposed-101";
    public static final int API_LEVEL = 101;

    private final XposedInterface xposed;
    private final BackendContext context;
    private final BackendCapabilities capabilities;

    public LibXposed101Backend(XposedInterface xposed, BackendContext context) {
        this.xposed = Objects.requireNonNull(xposed, "xposed");
        this.context = Objects.requireNonNull(context, "context");
        if (xposed.getApiVersion() < API_LEVEL) {
            throw new IllegalArgumentException("libxposed API 101 required, got " + xposed.getApiVersion());
        }
        this.capabilities = buildCapabilities(xposed);
    }

    private static BackendCapabilities buildCapabilities(XposedInterface xposed) {
        String cell = "libxposed-api=" + XposedInterface.LIB_API
                + "; framework=" + xposed.getFrameworkName() + "@" + xposed.getFrameworkVersion()
                + "; device_cell=not_verified";
        return new BackendCapabilities.Builder(BACKEND_ID, ChainSemantics.FRAMEWORK_CHAIN)
                .declare(Capability.FRAMEWORK_CHAIN, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "101.0.0", cell,
                        "101 Hooker/Chain interceptor model; source and stub verified only"))
                .declare(Capability.MODULE_LOCAL_CHAIN, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "101.0.0", cell,
                        "core composed inside one external registration"))
                .declare(Capability.CLASSIC_CALLBACKS, CapabilityResult.unsupported(BACKEND_ID,
                        "101 has a chain API, not separate classic before/after callbacks"))
                .declare(Capability.ATOMIC_REPLACE, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "101.0.0", cell,
                        "SDK-level single-target channel switch within one framework hook; not a framework replaceHook"))
                .declare(Capability.FRAMEWORK_PROTECTIVE, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "101.0.0", cell,
                        "explicit framework PROTECTIVE mode; runtime behavior not verified"))
                .declare(Capability.HOT_RELOAD, CapabilityResult.unsupported(BACKEND_ID,
                        "module hot-reload entry points were introduced in API 102"))
                .build();
    }

    @Override
    public String id() {
        return BACKEND_ID;
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
        ErrorPolicy policy = plan.spec().errorPolicy();
        XposedInterface.ExceptionMode mode = exceptionMode(policy);
        SwitchingHooker hooker = new SwitchingHooker(plan.target(), channel);
        XposedInterface.HookHandle handle = xposed.hook(plan.target())
                .setPriority(plan.spec().priority())
                .setExceptionMode(mode)
                .intercept(hooker);
        if (handle == null) {
            throw new IllegalStateException("framework intercept returned a null hook handle; installation outcome is unknown");
        }
        return new Shared101(handle, hooker, plan.spec().priority(), policy).initialHandle();
    }

    // The compatibility boundary references only types available in the earlier core.
    private static final class AfterBinding {
        final java.util.function.Function<BackendInvocation, Object> function;
        final Object notEntered;
        AfterBinding(java.util.function.Function<BackendInvocation, Object> function, Object notEntered) {
            this.function = function;
            this.notEntered = notEntered;
        }
    }

    /**
     * Outcome of the reflective {@code CoreAfterBridge.bind} lookup. The core is fixed for the
     * lifetime of this backend class, so the lookup runs once instead of once per route binding.
     * Only the two definite outcomes are cached; an unexpected lookup failure propagates uncached.
     */
    static final class BridgeLookup {
        static final String BRIDGE = "io.github.jichuo1.meguviz.hook.core.CoreAfterBridge";
        static final String DIRECT = "io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel";

        /** Null for an older core without the direct transport. */
        final java.lang.reflect.Method bind;
        /** Non-null when the core is inconsistent; every binding attempt then fails loudly. */
        final String failure;
        final Throwable cause;

        private BridgeLookup(java.lang.reflect.Method bind, String failure, Throwable cause) {
            this.bind = bind;
            this.failure = failure;
            this.cause = cause;
        }

        static BridgeLookup resolve(ClassLoader loader) {
            final Class<?> bridge;
            try {
                bridge = Class.forName(BRIDGE, false, loader);
            } catch (ClassNotFoundException missingBridge) {
                try {
                    Class.forName(DIRECT, false, loader);
                } catch (ClassNotFoundException oldCore) {
                    return new BridgeLookup(null, null, null);
                }
                return new BridgeLookup(null, "core advertises direct after but bridge is missing", missingBridge);
            }
            try {
                return new BridgeLookup(bridge.getMethod("bind", DispatchChannel.class, Object.class), null, null);
            } catch (NoSuchMethodException incomplete) {
                return new BridgeLookup(null, "direct after bridge is incomplete", incomplete);
            }
        }
    }

    private static volatile BridgeLookup bridgeLookup;

    static BridgeLookup bridgeLookup() {
        BridgeLookup current = bridgeLookup;
        if (current == null) {
            // Benign race: concurrent first binders resolve the same immutable answer.
            current = BridgeLookup.resolve(LibXposed101Backend.class.getClassLoader());
            bridgeLookup = current;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static AfterBinding optionalAfter(DispatchChannel channel) {
        BridgeLookup lookup = bridgeLookup();
        if (lookup.failure != null) throw new IllegalStateException(lookup.failure, lookup.cause);
        if (lookup.bind == null) return null;
        try {
            Object marker = new Object();
            Object bound = lookup.bind.invoke(null, channel, marker);
            if (bound == null) return null;
            if (!(bound instanceof java.util.function.Function)) {
                throw new IllegalStateException("core returned an invalid direct after binding");
            }
            return new AfterBinding((java.util.function.Function<BackendInvocation, Object>) bound, marker);
        } catch (java.lang.reflect.InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("direct after bridge binding failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("direct after bridge is incomplete", failure);
        }
    }

    private static Object dispatchAfter(AfterBinding binding, BackendInvocation invocation,
                                        XposedInterface.Chain chain) throws Throwable {
        Object value = binding.function.apply(invocation);
        if (value == binding.notEntered) return chain.proceed();
        return value instanceof DispatchResult ? ((DispatchResult) value).value() : value;
    }

    /** Flyweight binding; like {@link AfterBinding} it holds only types of the earlier core. */
    static final class ChainBinding {
        final java.util.function.Function<Object, Object> function;
        final Object notEntered;
        ChainBinding(java.util.function.Function<Object, Object> function, Object notEntered) {
            this.function = function;
            this.notEntered = notEntered;
        }
    }

    /**
     * Outcome of the reflective {@code CoreChainBridge.bind} lookup, resolved once per backend class
     * like {@link BridgeLookup}. A core without the flyweight transport yields no method, and routes
     * then keep the direct-after or ordinary transport; a core that has the channel type but lacks
     * the bridge or accessor type is inconsistent and fails every binding loudly.
     */
    static final class ChainBridgeLookup {
        static final String BRIDGE = "io.github.jichuo1.meguviz.hook.core.CoreChainBridge";
        static final String CHANNEL = "io.github.jichuo1.meguviz.hook.core.ChainDispatchChannel";
        static final String ACCESSOR = "io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor";

        final java.lang.reflect.Method bind;
        final String failure;
        final Throwable cause;

        private ChainBridgeLookup(java.lang.reflect.Method bind, String failure, Throwable cause) {
            this.bind = bind;
            this.failure = failure;
            this.cause = cause;
        }

        static ChainBridgeLookup resolve(ClassLoader loader) {
            final Class<?> bridge;
            try {
                bridge = Class.forName(BRIDGE, false, loader);
            } catch (ClassNotFoundException missingBridge) {
                try {
                    Class.forName(CHANNEL, false, loader);
                } catch (ClassNotFoundException olderCore) {
                    return new ChainBridgeLookup(null, null, null);
                }
                return new ChainBridgeLookup(null, "core advertises the chain transport but its bridge is missing",
                        missingBridge);
            }
            try {
                Class<?> accessor = Class.forName(ACCESSOR, false, loader);
                return new ChainBridgeLookup(bridge.getMethod("bind", DispatchChannel.class, Object.class, accessor),
                        null, null);
            } catch (ClassNotFoundException incomplete) {
                return new ChainBridgeLookup(null, "chain bridge is incomplete", incomplete);
            } catch (NoSuchMethodException incomplete) {
                return new ChainBridgeLookup(null, "chain bridge is incomplete", incomplete);
            }
        }
    }

    private static volatile ChainBridgeLookup chainBridgeLookup;

    static ChainBridgeLookup chainBridgeLookup() {
        ChainBridgeLookup current = chainBridgeLookup;
        if (current == null) {
            current = ChainBridgeLookup.resolve(LibXposed101Backend.class.getClassLoader());
            chainBridgeLookup = current;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static ChainBinding optionalChain(DispatchChannel channel, int parameterCount) {
        ChainBridgeLookup lookup = chainBridgeLookup();
        if (lookup.failure != null) throw new IllegalStateException(lookup.failure, lookup.cause);
        if (lookup.bind == null) return null;
        try {
            Object marker = new Object();
            Object bound = lookup.bind.invoke(null, channel, marker, FlyweightAccessors.create(parameterCount));
            if (bound == null) return null;
            if (!(bound instanceof java.util.function.Function)) {
                throw new IllegalStateException("core returned an invalid chain binding");
            }
            return new ChainBinding((java.util.function.Function<Object, Object>) bound, marker);
        } catch (java.lang.reflect.InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("chain bridge binding failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("chain bridge is incomplete", failure);
        }
    }

    /**
     * The only code that names {@link ChainAccessor101}, kept out of this class: it runs only after
     * the lookup found the flyweight types, and a core without them can then affect (for example in
     * verification) just this cold holder, never the hot hooker paths of the backend class.
     */
    private static final class FlyweightAccessors {
        private FlyweightAccessors() {
        }

        static Object create(int parameterCount) {
            return new ChainAccessor101(parameterCount);
        }
    }

    private static Object dispatchChain(ChainBinding binding, XposedInterface.Chain chain) throws Throwable {
        Object value = binding.function.apply(chain);
        if (value == binding.notEntered) return chain.proceed();
        return value instanceof DispatchResult ? ((DispatchResult) value).value() : value;
    }

    private static XposedInterface.ExceptionMode exceptionMode(ErrorPolicy policy) throws BackendInstallException {
        if (policy == ErrorPolicy.PROPAGATE) return XposedInterface.ExceptionMode.PASSTHROUGH;
        if (policy == ErrorPolicy.FRAMEWORK_PROTECTIVE) return XposedInterface.ExceptionMode.PROTECTIVE;
        throw new BackendInstallException("unsupported error policy " + policy);
    }

    /** One framework entry for its entire lifetime; only its SDK channel changes. */
    private static final class SwitchingHooker implements XposedInterface.Hooker {
        private final Executable target;
        // Captured once: replacement keeps the same target, so the declared count never changes.
        private final int parameterCount;
        private volatile Route route;

        private SwitchingHooker(Executable target, DispatchChannel channel) {
            this.target = target;
            this.parameterCount = target.getParameterCount();
            this.route = new Route(channel, parameterCount);
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Route selected = route;
            if (selected.success != null) {
                if (!selected.success.tryEnter()) return chain.proceed();
                try {
                    chain.proceed();
                    return selected.successResult;
                } finally {
                    selected.success.exit();
                }
            }
            if (selected.conditional != null) {
                return dispatchConditional(selected.conditional, selected.argumentIndex, selected.constantResult, chain);
            }
            if (selected.chainDirect != null) {
                // Flyweight: the core reads this chain through the route's stateless accessor.
                return dispatchChain(selected.chainDirect, chain);
            }
            if (selected.directAfter != null) {
                return dispatchAfter(selected.directAfter, new Chain101Invocation(target, chain, parameterCount), chain);
            }
            DispatchResult result;
            if (selected.requiresInvocation) {
                result = selected.channel.dispatch(new Chain101Invocation(target, chain, parameterCount));
            } else if (selected.fused != null) {
                Object value = selected.fused.dispatchValueOrNotEntered();
                return value == FusedConstantDispatchChannel.NOT_ENTERED ? chain.proceed() : value;
            } else if (selected.constant != null) {
                if (!selected.constant.tryEnter()) return chain.proceed();
                try {
                    return selected.constantResult;
                } finally {
                    selected.constant.exit();
                }
            } else {
                // A third-party invocation-free channel can still use the ordinary SPI fallback.
                result = selected.channel.dispatch(null);
            }
            switch (result.kind()) {
                case RETURNED:
                    return result.value();
                case THREW:
                    throw result.throwable();
                case PASSTHROUGH:
                default:
                    // An old snapshot retired before entry follows the SPI passthrough contract.
                    return chain.proceed();
            }
        }
    }

    /** Channel and its invocation requirement must be published as one immutable generation. */
    private static final class Route {
        private final DispatchChannel channel;
        private final boolean requiresInvocation;
        private final ConditionalDispatchChannel conditional;
        private final ConstantDispatchChannel constant;
        private final FusedConstantDispatchChannel fused;
        private final ChainBinding chainDirect;
        private final AfterBinding directAfter;
        private final SuccessResultDispatchChannel success;
        private final Object successResult;
        private final int argumentIndex;
        private final Object constantResult;

        private Route(DispatchChannel channel, int parameterCount) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.success = channel instanceof SuccessResultDispatchChannel
                    ? (SuccessResultDispatchChannel) channel : null;
            if (success != null) {
                // The chosen route is complete. A third-party channel may implement several
                // optional interfaces; never call unselected getters or requiresInvocation here.
                this.successResult = success.constantValue();
                this.chainDirect = null;
                this.directAfter = null;
                this.conditional = null;
                this.requiresInvocation = true;
                this.constant = null;
                this.fused = null;
                this.argumentIndex = -1;
                this.constantResult = null;
                return;
            }
            this.successResult = null;
            this.conditional = channel instanceof ConditionalDispatchChannel
                    ? (ConditionalDispatchChannel) channel : null;
            this.requiresInvocation = channel.requiresInvocation();
            // Prefer the flyweight transport; a core without it keeps the direct-after binding.
            this.chainDirect = conditional == null && requiresInvocation ? optionalChain(channel, parameterCount) : null;
            this.directAfter = conditional == null && requiresInvocation && chainDirect == null
                    ? optionalAfter(channel) : null;
            this.constant = !requiresInvocation && channel instanceof ConstantDispatchChannel
                    ? (ConstantDispatchChannel) channel : null;
            this.argumentIndex = conditional == null ? -1 : conditional.argumentIndex();
            this.constantResult = conditional != null ? conditional.constantValue()
                    : constant != null ? constant.constantValue() : null;
            this.fused = constant instanceof FusedConstantDispatchChannel
                    && constantResult != FusedConstantDispatchChannel.NOT_ENTERED
                    ? (FusedConstantDispatchChannel) constant : null;
        }
    }

    /** A successful entry remains counted until even a non-matching downstream call returns. */
    private static Object dispatchConditional(ConditionalDispatchChannel channel, int argumentIndex, Object constantResult,
                                              XposedInterface.Chain chain) throws Throwable {
        if (!channel.tryEnter()) return chain.proceed();
        try {
            Object argument;
            try {
                argument = chain.getArg(argumentIndex);
            } catch (IndexOutOfBoundsException absent) {
                argument = null;
            }
            if (channel.matches(argument)) {
                channel.notifyMatch();
                return constantResult;
            }
            return chain.proceed();
        } finally {
            channel.exit();
        }
    }

    private static final class Shared101 {
        private final XposedInterface.HookHandle frameworkHandle;
        private final SwitchingHooker hooker;
        private final int priority;
        private final ErrorPolicy errorPolicy;
        private Handle101 active;

        private Shared101(XposedInterface.HookHandle frameworkHandle, SwitchingHooker hooker, int priority,
                          ErrorPolicy errorPolicy) {
            this.frameworkHandle = frameworkHandle;
            this.hooker = hooker;
            this.priority = priority;
            this.errorPolicy = errorPolicy;
        }

        private Handle101 initialHandle() {
            active = new Handle101(this);
            return active;
        }

        private synchronized BackendHandle replace(Handle101 owner, BoundPlan plan, DispatchChannel channel)
                throws BackendInstallException {
            if (active != owner) throw new BackendInstallException("101 hook handle is no longer active");
            if (!hooker.target.equals(plan.target()) || priority != plan.spec().priority()
                    || plan.spec().errorPolicy() != errorPolicy) {
                throw new BackendInstallException("replacement must preserve target, priority and error policy");
            }
            Objects.requireNonNull(channel, "channel");
            Handle101 replacement = new Handle101(this);
            Route replacementRoute = new Route(channel, hooker.parameterCount);
            // This volatile write is the single switch point; it never calls framework or user code.
            hooker.route = replacementRoute;
            active = replacement;
            return replacement;
        }

        private synchronized BackendCloseReport uninstall(Handle101 owner) {
            if (owner.closeReport != null) return owner.closeReport;
            if (owner.uninstallAttempted) {
                return new BackendCloseReport(CloseReport.EntryRestoration.UNKNOWN,
                        CloseReport.PhysicalRelease.UNKNOWN, true,
                        "an earlier 101 unhook outcome is unknown; not attempted again");
            }
            if (active != owner) {
                return new BackendCloseReport(CloseReport.EntryRestoration.NOT_RESTORED,
                        CloseReport.PhysicalRelease.NOT_CLAIMED, false,
                        "superseded 101 handle does not unhook the current generation");
            }
            owner.uninstallAttempted = true;
            active = null;
            frameworkHandle.unhook();
            owner.closeReport = new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                    CloseReport.PhysicalRelease.NOT_CLAIMED, false,
                    "unhook issued per 101 API contract; device cell not verified");
            return owner.closeReport;
        }
    }

    private static final class Handle101 implements BackendHandle {
        private final Shared101 shared;
        private boolean uninstallAttempted;
        private BackendCloseReport closeReport;

        private Handle101(Shared101 shared) {
            this.shared = shared;
        }

        @Override
        public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            return shared.replace(this, plan, channel);
        }

        @Override
        public BackendCloseReport uninstall() {
            return shared.uninstall(this);
        }
    }

    static final class Chain101Invocation implements BackendInvocation {
        private final Executable target;
        private final XposedInterface.Chain chain;
        private final int parameterCount;

        Chain101Invocation(Executable target, XposedInterface.Chain chain, int parameterCount) {
            this.target = target;
            this.chain = chain;
            this.parameterCount = parameterCount;
        }

        @Override
        public Executable target() {
            return target;
        }

        @Override
        public Object thisObject() {
            return chain.getThisObject();
        }

        /**
         * One new array filled slot by slot. {@code getArgs()} may copy the slots into a new list on
         * every call (Vector does), so it is used only when the chain exposes fewer slots than the
         * target declares. Called only for shapes that touch arguments; NO_ARGS never reaches it.
         */
        @Override
        public Object[] arguments() {
            return slotArray(chain, parameterCount);
        }

        @Override
        public List<?> readOnlyArguments() {
            return new ArgumentView(chain, parameterCount);
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }

    /** Shared by both transports: one new array filled by getArg, list form if slots are missing. */
    static Object[] slotArray(XposedInterface.Chain chain, int count) {
        Object[] values = new Object[count];
        try {
            for (int i = 0; i < count; i++) values[i] = chain.getArg(i);
        } catch (IndexOutOfBoundsException fewerSlots) {
            return chain.getArgs().toArray();
        }
        return values;
    }

    /**
     * Stateless flyweight accessor bound once per route: the core reads the framework chain
     * directly, so a call allocates neither {@link Chain101Invocation} nor {@link ArgumentView}.
     * Single-slot reads keep the view's bounds (declared parameter count) and slot stability.
     */
    static final class ChainAccessor101 implements ChainAccessor<XposedInterface.Chain> {
        private final int parameterCount;

        ChainAccessor101(int parameterCount) {
            this.parameterCount = parameterCount;
        }

        @Override
        public Object thisObject(XposedInterface.Chain chain) {
            return chain.getThisObject();
        }

        @Override
        public Object argument(XposedInterface.Chain chain, int index) {
            if (index < 0 || index >= parameterCount) {
                throw new IndexOutOfBoundsException("argument index " + index + ", size " + parameterCount);
            }
            return chain.getArg(index);
        }

        @Override
        public Object[] arguments(XposedInterface.Chain chain) {
            return slotArray(chain, parameterCount);
        }

        @Override
        public Object proceed(XposedInterface.Chain chain, Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }

    /**
     * Read-only, index-backed view of the chain's argument slots: one small object per invocation
     * instead of a list copy. The chain's slots are fixed for the call (API {@code getArgs()} is
     * immutable), so the view keeps BackendInvocation's slot-stability rule without copying. It is
     * unmodifiable through AbstractList's defaults and sized by the declared parameter count.
     */
    static final class ArgumentView extends AbstractList<Object> implements RandomAccess {
        private final XposedInterface.Chain chain;
        private final int size;

        ArgumentView(XposedInterface.Chain chain, int size) {
            this.chain = chain;
            this.size = size;
        }

        @Override
        public Object get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("argument index " + index + ", size " + size);
            }
            return chain.getArg(index);
        }

        @Override
        public int size() {
            return size;
        }
    }
}
