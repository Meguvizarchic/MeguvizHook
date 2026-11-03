package io.github.jichuo1.meguviz.hook.backend.libxposed102;

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
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Executable;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Adapter for libxposed API 102 (artifact {@code io.github.libxposed:api:102.0.0}).
 *
 * <p>Real interception is performed by the framework through {@link XposedInterface}; this adapter
 * only maps the core contract onto it. It is one of two mutually exclusive backend artifacts and
 * must be consumed with the framework API as {@code compileOnly}.
 *
 * <p>Semantics mapping (source-verified against the 102.0.0 sources, device cells unverified):
 * <ul>
 *   <li>102 exposes an interceptor chain ({@code Hooker}/{@code Chain}), i.e. FRAMEWORK_CHAIN. It has
 *       no separate classic before/after callbacks, so CLASSIC_CALLBACKS is UNSUPPORTED rather than
 *       emulated.</li>
 *   <li>PROPAGATE maps to {@code ExceptionMode.PASSTHROUGH}. The explicitly opted-in
 *       FRAMEWORK_PROTECTIVE maps to {@code ExceptionMode.PROTECTIVE}; the framework owns its
 *       protective behavior, and this adapter never retries a downstream call.</li>
 *   <li>{@code HookFailedError} is an {@code Error} documenting a framework bug. Upstream explicitly
 *       forbids catching it for fallback, so this adapter does not convert it into an install
 *       receipt.</li>
 *   <li>The chain is snapshot based in 102, so replacing or adding hooks does not disturb an
 *       in-flight call; the core relies on its own immutable plan for the same guarantee.</li>
 *   <li>Initial hooks have no framework ID by default. A logical ID is scoped to one HookEnvironment,
 *       while framework IDs are scoped to the whole module and executable. A framework ID may be
 *       explicitly selected with {@link #withFrameworkId}; installing the same ID on the same target
 *       may atomically replace an existing module hook and invalidate its handle. Handle-based
 *       {@code replaceHook} also supports hooks without an ID.</li>
 *   <li>Hot reload has an explicit old-handle adoption bridge; framework support and the module's
 *       lifecycle/resource cleanup remain runtime-dependent.</li>
 * </ul>
 */
public final class LibXposed102Backend implements HookBackend {
    public static final String BACKEND_ID = "libxposed-102";

    private final XposedInterface xposed;
    private final BackendContext context;
    private final BackendCapabilities capabilities;
    private final String frameworkId;

    public LibXposed102Backend(XposedInterface xposed, BackendContext context) {
        this(xposed, context, null);
    }

    /**
     * Explicitly selects a framework ID for every initial hook installed by this backend instance.
     * The value is independent of SDK logical IDs and is passed unchanged to API102. Reusing it
     * on the same executable in the same module can replace a previously installed framework hook;
     * the displaced SDK environment cannot automatically update its registration state. A caller
     * must choose and own this ID deliberately. Different executables may use the same ID.
     *
     * @throws NullPointerException if {@code frameworkId} is null
     * @throws IllegalArgumentException if {@code frameworkId} is blank
     */
    public static LibXposed102Backend withFrameworkId(XposedInterface xposed, BackendContext context,
                                                       String frameworkId) {
        Objects.requireNonNull(frameworkId, "frameworkId");
        if (frameworkId.trim().isEmpty()) throw new IllegalArgumentException("frameworkId must not be blank");
        return new LibXposed102Backend(xposed, context, frameworkId);
    }

    private LibXposed102Backend(XposedInterface xposed, BackendContext context, String frameworkId) {
        this.xposed = Objects.requireNonNull(xposed, "xposed");
        this.context = Objects.requireNonNull(context, "context");
        this.frameworkId = frameworkId;
        if (xposed.getApiVersion() < XposedInterface.API_102) {
            throw new IllegalArgumentException("libxposed API 102 required, got " + xposed.getApiVersion());
        }
        this.capabilities = buildCapabilities(xposed);
    }

    private static BackendCapabilities buildCapabilities(XposedInterface xposed) {
        String cell = "libxposed-api=" + XposedInterface.LIB_API
                + "; framework=" + xposed.getFrameworkName() + "@" + xposed.getFrameworkVersion()
                + "; device_cell=not_verified";
        return new BackendCapabilities.Builder(BACKEND_ID, ChainSemantics.FRAMEWORK_CHAIN)
                .declare(Capability.FRAMEWORK_CHAIN, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "102.0.0", cell,
                        "102 Hooker/Chain interceptor model"))
                .declare(Capability.MODULE_LOCAL_CHAIN, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "102.0.0", cell,
                        "core composed inside one external registration"))
                .declare(Capability.CLASSIC_CALLBACKS, CapabilityResult.unsupported(BACKEND_ID,
                        "102 has no separate classic before/after callback API"))
                .declare(Capability.ATOMIC_REPLACE, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "102.0.0", cell,
                        "102 handle-based replaceHook; runtime behaviour not verified on a device"))
                .declare(Capability.FRAMEWORK_PROTECTIVE, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, BACKEND_ID, "102.0.0", cell,
                        "explicit framework PROTECTIVE mode; runtime behavior not verified"))
                .declare(Capability.HOT_RELOAD, CapabilityResult.unknown(BACKEND_ID,
                        "explicit API102 reload adoption bridge available; framework and module lifecycle support not verified"))
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
        XposedInterface.HookBuilder builder = xposed.hook(plan.target())
                .setPriority(plan.spec().priority())
                // Never inherit module.prop: the selected policy must map to an explicit mode.
                .setExceptionMode(mode);
        if (frameworkId != null) builder = builder.setId(frameworkId);
        XposedInterface.HookHandle handle = builder.intercept(hooker(plan, channel));
        if (handle == null) {
            throw new IllegalStateException("framework intercept returned a null hook handle; installation outcome is unknown");
        }
        return new Handle102(handle, policy);
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
     * lifetime of this backend class, so the lookup runs once instead of once per hooker binding.
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
            current = BridgeLookup.resolve(LibXposed102Backend.class.getClassLoader());
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

    private static XposedInterface.ExceptionMode exceptionMode(ErrorPolicy policy) throws BackendInstallException {
        if (policy == ErrorPolicy.PROPAGATE) return XposedInterface.ExceptionMode.PASSTHROUGH;
        if (policy == ErrorPolicy.FRAMEWORK_PROTECTIVE) return XposedInterface.ExceptionMode.PROTECTIVE;
        throw new BackendInstallException("unsupported error policy " + policy);
    }

    static XposedInterface.Hooker hooker(BoundPlan plan, DispatchChannel channel) {
        if (channel instanceof SuccessResultDispatchChannel) {
            SuccessResultDispatchChannel success = (SuccessResultDispatchChannel) channel;
            Object constant = success.constantValue();
            return chain -> {
                if (!success.tryEnter()) return chain.proceed();
                try {
                    chain.proceed();
                    return constant;
                } finally {
                    success.exit();
                }
            };
        }
        if (channel instanceof ConditionalDispatchChannel) {
            ConditionalDispatchChannel conditional = (ConditionalDispatchChannel) channel;
            int argumentIndex = conditional.argumentIndex();
            Object constantResult = conditional.constantValue();
            return new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return dispatchConditional(conditional, argumentIndex, constantResult, chain);
                }
            };
        }
        boolean requiresInvocation = channel.requiresInvocation();
        AfterBinding direct = requiresInvocation ? optionalAfter(channel) : null;
        if (direct != null) {
            // Captured once per binding: the target and its declared parameter count never change.
            Executable target = plan.target();
            int parameterCount = target.getParameterCount();
            return chain -> dispatchAfter(direct, new Chain102Invocation(target, chain, parameterCount), chain);
        }
        if (!requiresInvocation && channel instanceof ConstantDispatchChannel) {
            ConstantDispatchChannel constant = (ConstantDispatchChannel) channel;
            Object result = constant.constantValue();
            if (constant instanceof FusedConstantDispatchChannel
                    && result != FusedConstantDispatchChannel.NOT_ENTERED) {
                FusedConstantDispatchChannel fused = (FusedConstantDispatchChannel) constant;
                return new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object value = fused.dispatchValueOrNotEntered();
                        return value == FusedConstantDispatchChannel.NOT_ENTERED ? chain.proceed() : value;
                    }
                };
            }
            return new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!constant.tryEnter()) return chain.proceed();
                    try {
                        return result;
                    } finally {
                        constant.exit();
                    }
                }
            };
        }
        if (!requiresInvocation) {
            // Bind the invocation-free shape once; avoid deciding and wrapping on every call.
            return new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    DispatchResult result = channel.dispatch(null);
                    switch (result.kind()) {
                        case RETURNED:
                            return result.value();
                        case THREW:
                            throw result.throwable();
                        case PASSTHROUGH:
                        default:
                            return chain.proceed();
                    }
                }
            };
        }
        Executable target = plan.target();
        int parameterCount = target.getParameterCount();
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                DispatchResult result = channel.dispatch(new Chain102Invocation(target, chain, parameterCount));
                switch (result.kind()) {
                    case RETURNED:
                        return result.value();
                    case THREW:
                        throw result.throwable();
                    case PASSTHROUGH:
                    default:
                        // Registration retired: run the original once, no SDK callbacks.
                        return chain.proceed();
                }
            }
        };
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

    static final class Handle102 implements BackendHandle {
        private final XposedInterface.HookHandle handle;
        private final ErrorPolicy errorPolicy;

        Handle102(XposedInterface.HookHandle handle, ErrorPolicy errorPolicy) {
            this.handle = handle;
            this.errorPolicy = errorPolicy;
        }

        @Override
        public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            if (plan.spec().errorPolicy() != errorPolicy) {
                throw new BackendInstallException("replacement must preserve the framework exception policy");
            }
            // API102 preserves executable, priority, exception mode and id while swapping the hooker.
            XposedInterface.HookHandle replacement = handle.replaceHook(hooker(plan, channel));
            if (replacement == null) {
                throw new IllegalStateException("framework replaceHook returned a null handle; replacement outcome is unknown");
            }
            return new Handle102(replacement, errorPolicy);
        }

        @Override
        public BackendCloseReport uninstall() {
            // unhook() is idempotent by 102 contract. Restoration is claimed at API-contract level
            // only; no device cell has verified it, and no physical release is claimed.
            handle.unhook();
            return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                    CloseReport.PhysicalRelease.NOT_CLAIMED, false,
                    "unhook issued per 102 API contract; device cell not verified");
        }
    }

    static final class Chain102Invocation implements BackendInvocation {
        private final Executable target;
        private final XposedInterface.Chain chain;
        private final int parameterCount;

        Chain102Invocation(Executable target, XposedInterface.Chain chain, int parameterCount) {
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
            int count = parameterCount;
            Object[] values = new Object[count];
            try {
                for (int i = 0; i < count; i++) values[i] = chain.getArg(i);
            } catch (IndexOutOfBoundsException fewerSlots) {
                return chain.getArgs().toArray();
            }
            return values;
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
