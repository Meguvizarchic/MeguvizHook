package io.github.jichuo1.meguviz.hook.backend.libxposed102;

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

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Executable;
import java.util.Objects;

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
 *   <li>The core's PROPAGATE policy requires {@code ExceptionMode.PASSTHROUGH}: the 102 default
 *       (PROTECTIVE, also the default of {@code module.prop}) would catch hooker failures and
 *       continue as if no hook existed, which would break the frozen contract. The adapter sets the
 *       mode explicitly on every hook instead of relying on module metadata.</li>
 *   <li>{@code HookFailedError} is an {@code Error} documenting a framework bug. Upstream explicitly
 *       forbids catching it for fallback, so this adapter does not convert it into an install
 *       receipt.</li>
 *   <li>The chain is snapshot based in 102, so replacing or adding hooks does not disturb an
 *       in-flight call; the core relies on its own immutable plan for the same guarantee.</li>
 *   <li>Hot reload is reported UNKNOWN: this slice does not implement it and never claims it.</li>
 * </ul>
 */
public final class LibXposed102Backend implements HookBackend {
    public static final String BACKEND_ID = "libxposed-102";

    private final XposedInterface xposed;
    private final BackendContext context;
    private final BackendCapabilities capabilities;

    public LibXposed102Backend(XposedInterface xposed, BackendContext context) {
        this.xposed = Objects.requireNonNull(xposed, "xposed");
        this.context = Objects.requireNonNull(context, "context");
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
                        "102 setId/replaceHook; runtime behaviour not verified on a device"))
                .declare(Capability.HOT_RELOAD, CapabilityResult.unknown(BACKEND_ID,
                        "hot reload is not implemented by this slice"))
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
        if (plan.spec().errorPolicy() != ErrorPolicy.PROPAGATE) {
            throw new BackendInstallException("unsupported error policy " + plan.spec().errorPolicy());
        }
        XposedInterface.HookHandle handle = xposed.hook(plan.target())
                .setPriority(plan.spec().priority())
                // PROPAGATE cannot be implemented on top of PROTECTIVE; never inherit module.prop here.
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId(plan.id().value())
                .intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        DispatchResult result = channel.dispatch(new Chain102Invocation(plan.target(), chain));
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
                });
        return new Handle102(handle);
    }

    private static final class Handle102 implements BackendHandle {
        private final XposedInterface.HookHandle handle;

        private Handle102(XposedInterface.HookHandle handle) {
            this.handle = handle;
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

    private static final class Chain102Invocation implements BackendInvocation {
        private final Executable target;
        private final XposedInterface.Chain chain;

        private Chain102Invocation(Executable target, XposedInterface.Chain chain) {
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
            // Called only for shapes that need arguments; NO_ARGS never reaches this method.
            return chain.getArgs().toArray();
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }
}
