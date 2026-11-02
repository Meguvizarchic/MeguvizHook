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
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Objects;

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

    private static XposedInterface.ExceptionMode exceptionMode(ErrorPolicy policy) throws BackendInstallException {
        if (policy == ErrorPolicy.PROPAGATE) return XposedInterface.ExceptionMode.PASSTHROUGH;
        if (policy == ErrorPolicy.FRAMEWORK_PROTECTIVE) return XposedInterface.ExceptionMode.PROTECTIVE;
        throw new BackendInstallException("unsupported error policy " + policy);
    }

    /** One framework entry for its entire lifetime; only its SDK channel changes. */
    private static final class SwitchingHooker implements XposedInterface.Hooker {
        private final Executable target;
        private volatile Route route;

        private SwitchingHooker(Executable target, DispatchChannel channel) {
            this.target = target;
            this.route = new Route(channel);
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
            DispatchResult result;
            if (selected.requiresInvocation) {
                result = selected.channel.dispatch(new Chain101Invocation(target, chain));
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
        private final SuccessResultDispatchChannel success;
        private final Object successResult;
        private final int argumentIndex;
        private final Object constantResult;

        private Route(DispatchChannel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.success = channel instanceof SuccessResultDispatchChannel
                    ? (SuccessResultDispatchChannel) channel : null;
            if (success != null) {
                // The chosen route is complete. A third-party channel may implement several
                // optional interfaces; never call unselected getters or requiresInvocation here.
                this.successResult = success.constantValue();
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
            Route replacementRoute = new Route(channel);
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

    private static final class Chain101Invocation implements BackendInvocation {
        private final Executable target;
        private final XposedInterface.Chain chain;

        private Chain101Invocation(Executable target, XposedInterface.Chain chain) {
            this.target = target;
            this.chain = chain;
        }

        @Override
        public Executable target() {
            return target;
        }

        @Override
        public Object thisObject() {
            return chain.getThisObject();
        }

        @Override
        public Object[] arguments() {
            return chain.getArgs().toArray();
        }

        @Override
        public List<?> readOnlyArguments() {
            return chain.getArgs();
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }
}
