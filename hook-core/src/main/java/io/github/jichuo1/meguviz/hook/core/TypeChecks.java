package io.github.jichuo1.meguviz.hook.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Install-time and slot-write type checks. Primitive/void/constructor cases are handled explicitly. */
final class TypeChecks {
    private static final Map<Class<?>, Class<?>> WRAPPERS = new HashMap<Class<?>, Class<?>>();

    static {
        WRAPPERS.put(boolean.class, Boolean.class);
        WRAPPERS.put(byte.class, Byte.class);
        WRAPPERS.put(char.class, Character.class);
        WRAPPERS.put(short.class, Short.class);
        WRAPPERS.put(int.class, Integer.class);
        WRAPPERS.put(long.class, Long.class);
        WRAPPERS.put(float.class, Float.class);
        WRAPPERS.put(double.class, Double.class);
    }

    private TypeChecks() {
    }

    static Class<?> returnType(Executable target) {
        return target instanceof Method ? ((Method) target).getReturnType() : void.class;
    }

    static boolean isConstructor(Executable target) {
        return target instanceof Constructor;
    }

    /** Validates a value written into a result slot. Void targets only accept {@code null}. */
    static void checkResultValue(Executable target, Object value) {
        Class<?> type = returnType(target);
        if (type == void.class) {
            if (value != null) {
                throw new IllegalArgumentException("void/constructor target cannot return a value: " + target);
            }
            return;
        }
        checkAssignable(type, value, "result of " + target);
    }

    static void checkArgumentValue(Executable target, int index, Object value) {
        Class<?>[] types = target.getParameterTypes();
        if (index < 0 || index >= types.length) {
            throw new IndexOutOfBoundsException("argument index " + index + " of " + target);
        }
        checkAssignable(types[index], value, "argument " + index + " of " + target);
    }

    private static void checkAssignable(Class<?> type, Object value, String what) {
        if (type.isPrimitive()) {
            if (value == null) {
                throw new IllegalArgumentException(what + " is primitive " + type.getName() + ", null is not allowed");
            }
            Class<?> wrapper = WRAPPERS.get(type);
            if (wrapper != null && !wrapper.isInstance(value)) {
                throw new IllegalArgumentException(what + " expects " + wrapper.getSimpleName()
                        + " for primitive " + type.getName() + " but got " + value.getClass().getName());
            }
            return;
        }
        if (value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException(what + " expects " + type.getName()
                    + " but got " + value.getClass().getName());
        }
    }

    // ThreadDeath is marked for removal on recent JDKs but still exists on Android and older JDKs;
    // the contract requires it to bypass ordinary failure handling, so the removal lint is scoped
    // to these two methods instead of disabling -Werror globally.
    @SuppressWarnings("removal")
    static boolean isFatal(Throwable throwable) {
        return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
    }

    /**
     * Rethrows a fatal VM event without declaring {@code throws Throwable}. Fatal events are never
     * converted into ordinary outcomes, diagnostics or retries.
     */
    @SuppressWarnings("removal")
    static RuntimeException fatal(Throwable throwable) {
        if (throwable instanceof VirtualMachineError) {
            throw (VirtualMachineError) throwable;
        }
        if (throwable instanceof ThreadDeath) {
            throw (ThreadDeath) throwable;
        }
        return new IllegalStateException("not a fatal VM event", throwable);
    }
}
