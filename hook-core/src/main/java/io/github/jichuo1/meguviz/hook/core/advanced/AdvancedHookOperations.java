package io.github.jichuo1.meguviz.hook.core.advanced;

import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Optional operations outside normal HookEnvironment dispatch. An implementation may be supplied
 * by a framework backend or another integration; the core does not require any framework types.
 * Availability is scoped evidence, not a promise that a particular method or runtime will work.
 */
public interface AdvancedHookOperations {
    /** Independently named capabilities; adapters must not infer runtime support from API level. */
    enum Feature {
        DEOPTIMIZE_CALLER,
        ORIGINAL_INVOKER,
        FULL_CHAIN_INVOKER,
        SPECIAL_INVOKER,
        CONSTRUCTOR_INVOKER,
        SPECIAL_CONSTRUCTOR_INVOKER,
        CLASS_INITIALIZER
    }

    /** FULL_CHAIN may invoke this or other hooks again; ORIGINAL bypasses the framework hook chain. */
    enum InvocationRoute { FULL_CHAIN, ORIGINAL }

    /** No probing side effects: UNKNOWN must not be interpreted as a verified runtime capability. */
    CapabilityResult availability(Feature feature);

    /**
     * Ask the framework to deoptimize the caller containing an inlined callee. The boolean is the
     * framework's result; this does not discover all callers or guarantee a hook will be hit.
     */
    boolean deoptimizeCaller(Executable caller);

    /**
     * Invoke a method through the explicitly selected full chain or original route. FULL_CHAIN
     * starts a new call and can re-enter the same hooks. This is not the current invocation's
     * continuation, is not scoped to a callback lease, and is never retried automatically.
     */
    Object invoke(Method method, Object receiver, InvocationRoute route, Object... args)
            throws InvocationTargetException, IllegalAccessException;

    /**
     * Bypass all hooks and invoke a method once through the framework's original invoker. The
     * framework's checked exceptions, including InvocationTargetException, are not unwrapped.
     * No retry, re-entry guard or automatic fallback to reflection is performed.
     */
    Object invokeOriginal(Method method, Object receiver, Object... args)
            throws InvocationTargetException, IllegalAccessException;

    /**
     * Invoke a non-static method non-virtually on a compatible receiver using the explicit route.
     * FULL_CHAIN can re-enter hooks, including the caller; ORIGINAL skips them. Receiver and
     * arguments belong to the caller, and framework exceptions propagate without retry.
     */
    Object invokeSpecial(Method method, Object receiver, InvocationRoute route, Object... args)
            throws InvocationTargetException, IllegalAccessException;

    /** Construct via the selected hook chain or directly through the original constructor. */
    <T> T newInstance(Constructor<T> constructor, InvocationRoute route, Object... args)
            throws InvocationTargetException, IllegalAccessException, InstantiationException;

    /**
     * Construct a subclass with a parent constructor. The subclass constructor is not run and
     * fields can remain uninitialized. This is an explicit dangerous operation, not a fallback.
     */
    <T> T newInstanceSpecial(Constructor<?> constructor, Class<T> subclass, InvocationRoute route,
                             Object... args)
            throws InvocationTargetException, IllegalAccessException, InstantiationException;

    /**
     * Register a managed static-initializer interceptor. A class already initialized will not
     * invoke it; if initialization races installation before publication, it passes through and
     * may be permanently missed. Registration success does not prove a hit. Close rejects new
     * callbacks first, then synchronously invokes framework unhook under the registration lock;
     * an unhook implementation must not wait for a thread trying to close this same registration.
     * The callback's at-most-once {@code proceed()} contract applies only to calls initiated by
     * this SDK. With {@link ErrorPolicy#FRAMEWORK_PROTECTIVE}, an escaping exception is handed to
     * the framework; any framework recovery or downstream replay happens outside this SDK's lease
     * and is governed by the framework, not by this continuation contract.
     * Unsupported framework operations and uncertain installation failures propagate unchanged.
     */
    ClassInitializerRegistration hookClassInitializer(Class<?> target, int priority,
                                                      ErrorPolicy policy,
                                                      ClassInitializerCallback callback);

    @FunctionalInterface
    interface ClassInitializerCallback {
        void intercept(ClassInitializerInvocation invocation) throws Throwable;
    }

    /** Borrowed only on the calling thread until the callback exits. */
    interface ClassInitializerInvocation {
        Class<?> targetClass();

        /** Continue downstream once; its exact throwable propagates unless the callback handles it. */
        void proceed() throws Throwable;

        /** Explicitly skip the initializer; this may leave the class in an invalid state. */
        void skip();
    }

    interface ClassInitializerRegistration {
        /** Rejects new callbacks before attempting one unhook; never waits for admitted callbacks. */
        CloseReport close();
        int inFlight();
    }
}
