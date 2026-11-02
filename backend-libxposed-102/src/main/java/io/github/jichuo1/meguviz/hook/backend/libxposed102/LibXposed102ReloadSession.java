package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, callback-scoped access to the old handles supplied by API 102 hot reload.
 * Create exactly one session per {@code onHotReloaded} callback and close it before returning.
 * The caller enforces this lifetime; the API cannot independently authenticate the callback.
 * The module must map each old handle to its own new descriptor without guessing from the
 * executable alone. It must also ensure that owner, priority and exception mode are compatible;
 * the framework handle does not expose those attributes and replacement preserves them.
 * Unclaimed old handles remain the module's responsibility.
 */
public final class LibXposed102ReloadSession implements AutoCloseable {
    private final LibXposed102Backend backend;
    private final List<XposedInterface.HookHandle> oldHandles;
    private final List<Token> tokens = new ArrayList<Token>();
    private final Set<XposedInterface.HookHandle> issued =
            Collections.newSetFromMap(new IdentityHashMap<XposedInterface.HookHandle, Boolean>());
    private boolean closed;

    public LibXposed102ReloadSession(LibXposed102Backend backend, XposedModuleInterface.HotReloadedParam param) {
        this.backend = Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(param, "param");
        List<XposedInterface.HookHandle> handles = Objects.requireNonNull(param.getOldHookHandles(), "oldHookHandles");
        oldHandles = new ArrayList<XposedInterface.HookHandle>(handles);
        Set<XposedInterface.HookHandle> unique =
                Collections.newSetFromMap(new IdentityHashMap<XposedInterface.HookHandle, Boolean>());
        for (XposedInterface.HookHandle handle : oldHandles) {
            if (handle == null || !unique.add(handle)) {
                throw new IllegalArgumentException("old hook handle list contains null or duplicate identity");
            }
        }
    }

    /** Returns a snapshot of this callback's framework handles for explicit caller mapping. */
    public synchronized List<XposedInterface.HookHandle> oldHandles() {
        if (closed) throw new IllegalStateException("reload session is closed");
        return Collections.unmodifiableList(new ArrayList<XposedInterface.HookHandle>(oldHandles));
    }

    /**
     * Issues one adoption token assuming the old hook used PROPAGATE. The caller must know that
     * old policy: framework handles expose no exception-mode getter.
     */
    public synchronized BackendAdoption adoption(XposedInterface.HookHandle handle) {
        return adoption(handle, ErrorPolicy.PROPAGATE);
    }

    /**
     * Issues one token for a handle from this callback, compared by identity, with the old policy
     * explicitly declared by the caller. This declaration is not verified by the framework API.
     * The new plan must use the same policy because replaceHook preserves the old exception mode.
     */
    public synchronized BackendAdoption adoption(XposedInterface.HookHandle handle, ErrorPolicy expectedOldPolicy) {
        if (closed) throw new IllegalStateException("reload session is closed");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(expectedOldPolicy, "expectedOldPolicy");
        if (expectedOldPolicy != ErrorPolicy.PROPAGATE && expectedOldPolicy != ErrorPolicy.FRAMEWORK_PROTECTIVE) {
            throw new IllegalArgumentException("unsupported old framework exception policy");
        }
        boolean found = false;
        for (XposedInterface.HookHandle old : oldHandles) {
            if (old == handle) { found = true; break; }
        }
        if (!found) throw new IllegalArgumentException("handle is not from this reload callback");
        if (!issued.add(handle)) throw new IllegalStateException("handle already issued in this session");
        Token token = new Token(handle, expectedOldPolicy);
        tokens.add(token);
        return token;
    }

    @Override
    public synchronized void close() {
        closed = true;
        oldHandles.clear();
        issued.clear();
        for (Token token : tokens) token.old = null;
        tokens.clear();
    }

    private final class Token implements BackendAdoption {
        private XposedInterface.HookHandle old;
        private final ErrorPolicy expectedOldPolicy;
        private boolean attempted;

        private Token(XposedInterface.HookHandle old, ErrorPolicy expectedOldPolicy) {
            this.old = old;
            this.expectedOldPolicy = expectedOldPolicy;
        }

        @Override
        public HookBackend backend() {
            return backend;
        }

        @Override
        public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            synchronized (LibXposed102ReloadSession.this) {
                if (closed || attempted) {
                    throw new IllegalStateException("reload token expired or already attempted; handle state is unknown");
                }
                if (plan.spec().errorPolicy() != expectedOldPolicy) {
                    throw new BackendInstallException("new policy must match the caller-declared old framework policy");
                }
                if (!plan.target().equals(old.getExecutable())) {
                    throw new BackendInstallException("old handle target does not match the new plan");
                }
                // Consume before invoking the framework: a throw or null may follow a commit.
                attempted = true;
                XposedInterface.HookHandle handle = old;
                old = null;
                XposedInterface.HookHandle next = handle.replaceHook(LibXposed102Backend.hooker(plan, channel));
                if (next == null) {
                    throw new IllegalStateException("framework returned a null handle; replacement outcome is unknown");
                }
                return new LibXposed102Backend.Handle102(next, expectedOldPolicy);
            }
        }
    }
}
