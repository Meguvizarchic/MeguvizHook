package io.github.jichuo1.meguviz.hook.core.advanced;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Framework-independent, opt-in lifecycle for a single static-initializer entry. */
public final class ManagedInitializerRegistration implements AdvancedHookOperations.ClassInitializerRegistration {
    @FunctionalInterface public interface Continuation { Object proceed() throws Throwable; }
    @FunctionalInterface public interface Dispatch { Object intercept(Continuation next) throws Throwable; }
    @FunctionalInterface public interface Installer { Runnable install(Dispatch dispatch); }

    private final Class<?> target;
    private final AdvancedHookOperations.ClassInitializerCallback callback;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean accepting;
    private Runnable uninstall;
    private CloseReport closeReport;

    private ManagedInitializerRegistration(Class<?> target, AdvancedHookOperations.ClassInitializerCallback callback) {
        this.target = target;
        this.callback = callback;
    }

    /**
     * Installer must return one unhook action only after the framework entry was created. Before
     * publication, concurrent entries pass through and a one-shot initializer may be missed.
     * If install throws or returns null, outcome may be unknown; the callback remains disabled
     * and this method does not retry or unhook. Close calls the unhook action synchronously while
     * holding this registration's monitor; avoid cross-thread circular waits involving close.
     */
    public static ManagedInitializerRegistration install(Class<?> target,
            AdvancedHookOperations.ClassInitializerCallback callback, Installer installer) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(installer, "installer");
        ManagedInitializerRegistration registration = new ManagedInitializerRegistration(target, callback);
        Runnable unhook = installer.install(registration::dispatch);
        if (unhook == null) throw new IllegalStateException("initializer install returned no unhook; outcome unknown");
        registration.uninstall = unhook;
        registration.accepting = true;
        return registration;
    }

    private Object dispatch(Continuation continuation) throws Throwable {
        Objects.requireNonNull(continuation, "continuation");
        if (!accepting) return continuation.proceed();
        inFlight.incrementAndGet();
        if (!accepting) {
            inFlight.decrementAndGet();
            return continuation.proceed();
        }
        Invocation invocation = null;
        try {
            invocation = new Invocation(target, continuation);
            try {
                callback.intercept(invocation);
            } catch (Throwable failure) {
                invocation.rethrowFatal();
                throw failure;
            }
            invocation.rethrowFatal();
            if (!invocation.decided) throw new HookContractException("initializer callback must proceed or explicitly skip");
            return null;
        } finally {
            if (invocation != null) invocation.active = false;
            inFlight.decrementAndGet();
        }
    }

    @Override public int inFlight() { return inFlight.get(); }

    @Override public synchronized CloseReport close() {
        if (closeReport != null) return closeReport;
        accepting = false;
        // Record an unknown outcome first: an unchecked or fatal interruption can happen after
        // unhook has a side effect. Never retry automatically or claim physical release.
        closeReport = new CloseReport(RegistrationState.RETIRED_RESIDENT, true,
                CloseReport.EntryRestoration.UNKNOWN, inFlight.get(), CloseReport.PhysicalRelease.UNKNOWN,
                "initializer unhook outcome unknown");
        uninstall.run();
        closeReport = new CloseReport(RegistrationState.CLOSED, true,
                CloseReport.EntryRestoration.RESTORED, inFlight.get(), CloseReport.PhysicalRelease.NOT_CLAIMED,
                "unhook returned under framework API contract; runtime restoration not independently verified");
        return closeReport;
    }

    private static final class Invocation implements AdvancedHookOperations.ClassInitializerInvocation {
        private final Class<?> target;
        private final Continuation continuation;
        private final Thread owner = Thread.currentThread();
        private volatile boolean active = true;
        private boolean decided;
        private Error observedFatal;

        private Invocation(Class<?> target, Continuation continuation) {
            this.target = target;
            this.continuation = continuation;
        }

        private void check() {
            if (!active || Thread.currentThread() != owner) {
                throw new HookContractException("initializer invocation is expired or used from another thread");
            }
        }

        private void decide() {
            check();
            if (decided) throw new HookContractException("initializer continuation already decided");
            decided = true;
        }

        private void rethrowFatal() {
            if (observedFatal != null) throw observedFatal;
        }

        @Override public Class<?> targetClass() { check(); return target; }
        @SuppressWarnings("removal")
        @Override public void proceed() throws Throwable {
            decide();
            try {
                continuation.proceed();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                observedFatal = fatal;
                throw fatal;
            }
        }
        @Override public void skip() { decide(); }
    }
}
