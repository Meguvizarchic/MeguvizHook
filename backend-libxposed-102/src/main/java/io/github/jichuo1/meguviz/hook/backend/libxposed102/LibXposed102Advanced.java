package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.core.advanced.AdvancedHookOperations;
import io.github.jichuo1.meguviz.hook.core.advanced.ManagedInitializerRegistration;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Explicit API102 advanced operations; framework behavior is not inferred from the API number.
 * For initializer hooks, {@link ErrorPolicy#FRAMEWORK_PROTECTIVE} delegates escaped failures to
 * the framework. The SDK's at-most-once continuation does not constrain framework recovery or
 * replay after its hooker exits, and such later work is outside the SDK's in-flight count.
 */
public final class LibXposed102Advanced implements AdvancedHookOperations {
    private final XposedInterface framework;

    public LibXposed102Advanced(XposedInterface framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
        if (framework.getApiVersion() < XposedInterface.API_102) {
            throw new IllegalArgumentException("libxposed API 102 required");
        }
    }

    @Override public CapabilityResult availability(Feature feature) {
        Objects.requireNonNull(feature, "feature");
        return CapabilityResult.of(CapabilityResult.Status.UNKNOWN, LibXposed102Backend.BACKEND_ID,
                "102.0.0", null, "API declares " + feature + "; actual framework support is unverified");
    }

    @Override public boolean deoptimizeCaller(Executable caller) {
        return framework.deoptimize(Objects.requireNonNull(caller, "caller"));
    }

    @Override public Object invokeOriginal(Method method, Object receiver, Object... args)
            throws InvocationTargetException, IllegalAccessException {
        return invoke(method, receiver, InvocationRoute.ORIGINAL, args);
    }

    @Override public Object invoke(Method method, Object receiver, InvocationRoute route, Object... args)
            throws InvocationTargetException, IllegalAccessException {
        return framework.getInvoker(Objects.requireNonNull(method, "method"))
                .setType(route(route)).invoke(receiver, args);
    }

    @Override public Object invokeSpecial(Method method, Object receiver, InvocationRoute route, Object... args)
            throws InvocationTargetException, IllegalAccessException {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(receiver, "receiver");
        if (Modifier.isStatic(method.getModifiers()) || !method.getDeclaringClass().isInstance(receiver)) {
            throw new IllegalArgumentException("special invocation requires a compatible instance method receiver");
        }
        return framework.getInvoker(method).setType(route(route)).invokeSpecial(receiver, args);
    }

    @Override public <T> T newInstance(Constructor<T> constructor, InvocationRoute route, Object... args)
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        return framework.getInvoker(Objects.requireNonNull(constructor, "constructor"))
                .setType(route(route)).newInstance(args);
    }

    @Override public <T> T newInstanceSpecial(Constructor<?> constructor, Class<T> subclass,
                                               InvocationRoute route, Object... args)
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        Objects.requireNonNull(constructor, "constructor");
        Objects.requireNonNull(subclass, "subclass");
        if (!constructor.getDeclaringClass().isAssignableFrom(subclass)) {
            throw new IllegalArgumentException("special instance must use the constructor's class or subclass");
        }
        return framework.getInvoker(constructor).setType(route(route)).newInstanceSpecial(subclass, args);
    }

    @Override public ClassInitializerRegistration hookClassInitializer(Class<?> target, int priority,
            ErrorPolicy policy, ClassInitializerCallback callback) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(callback, "callback");
        XposedInterface.ExceptionMode mode = mode(policy);
        return ManagedInitializerRegistration.install(target, callback, dispatch -> {
            XposedInterface.HookHandle handle = framework.hookClassInitializer(target)
                    .setPriority(priority).setExceptionMode(mode)
                    .intercept(chain -> dispatch.intercept(chain::proceed));
            if (handle == null) throw new IllegalStateException("initializer handle is null; install outcome unknown");
            return handle::unhook;
        });
    }

    private static XposedInterface.Invoker.Type route(InvocationRoute route) {
        Objects.requireNonNull(route, "route");
        return route == InvocationRoute.ORIGINAL ? XposedInterface.Invoker.Type.ORIGIN
                : XposedInterface.Invoker.Type.Chain.FULL;
    }

    private static XposedInterface.ExceptionMode mode(ErrorPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        switch (policy) {
            case PROPAGATE: return XposedInterface.ExceptionMode.PASSTHROUGH;
            case FRAMEWORK_PROTECTIVE: return XposedInterface.ExceptionMode.PROTECTIVE;
            default: throw new IllegalArgumentException("unsupported initializer error policy");
        }
    }
}
