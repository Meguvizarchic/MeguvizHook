package io.github.jichuo1.meguviz.hook.backend.libxposed101;

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
 * Adapter for the real libxposed API 101.0.0 artifact.
 *
 * <p>Source verification corrected an earlier planning assumption: 101 already exposes the same
 * {@code Hooker}/{@code Chain} interception model as 102. It is therefore a FRAMEWORK_CHAIN
 * backend, not a classic-callback emulation. The 101 adapter deliberately has no references to
 * 102-only {@code setId}, {@code replaceHook}, {@code API_102} or hot-reload entry types.
 *
 * <p>As in 102, PROPAGATE requires an explicit {@code ExceptionMode.PASSTHROUGH}; the upstream
 * default can otherwise swallow hooker failures. Real framework loading and ART behaviour remain
 * unverified until a physical 101 device cell is run.
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
                .declare(Capability.ATOMIC_REPLACE, CapabilityResult.unsupported(BACKEND_ID,
                        "setId/replaceHook were introduced in API 102"))
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
        if (plan.spec().errorPolicy() != ErrorPolicy.PROPAGATE) {
            throw new BackendInstallException("unsupported error policy " + plan.spec().errorPolicy());
        }
        XposedInterface.HookHandle handle = xposed.hook(plan.target())
                .setPriority(plan.spec().priority())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        DispatchResult result = channel.dispatch(new Chain101Invocation(plan.target(), chain));
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
                });
        return new Handle101(handle);
    }

    private static final class Handle101 implements BackendHandle {
        private final XposedInterface.HookHandle handle;

        private Handle101(XposedInterface.HookHandle handle) {
            this.handle = handle;
        }

        @Override
        public BackendCloseReport uninstall() {
            handle.unhook();
            return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                    CloseReport.PhysicalRelease.NOT_CLAIMED, false,
                    "unhook issued per 101 API contract; device cell not verified");
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
        public Object proceed(Object[] args) throws Throwable {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
    }
}
