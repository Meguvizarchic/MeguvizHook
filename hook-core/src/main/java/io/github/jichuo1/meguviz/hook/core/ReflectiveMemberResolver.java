package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BatchMemberResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small exact-descriptor resolver used at install time only.
 *
 * <p>Scope of this implementation: declared methods/constructors of one class loaded from the
 * actual backend loader, with optional parameter and return type checks. Synthetic and bridge
 * methods are excluded. Candidates are sorted by a stable signature key so {@code FIRST} does not
 * depend on VM reflection order. {@link #resolve} keeps no cache, so no result can outlive a code
 * epoch. Inherited members and dex-wide search are out of scope and belong to a consumer-side
 * resolver SPI implementation.
 *
 * <p>For {@code HookEnvironment.installAll} a {@link Batch} keeps, for the duration of that call
 * and one loader and code epoch, the loaded classes and one copy of each class's declared methods
 * or constructors. Only successful lookups are kept; a missing class or member is looked up again
 * for every target. Results equal those of {@link #resolve}; members of one class can be the same
 * reflective instances across targets of one batch instead of equal copies.
 */
public final class ReflectiveMemberResolver implements BatchMemberResolver {
    @Override
    public List<Executable> resolve(TargetSpec.DescriptorTarget descriptor, ClassLoader loader) throws BackendInstallException {
        return resolve(descriptor, loader, null);
    }

    @Override
    public Batch openBatch(ClassLoader loader) {
        return new MemberBatch(loader);
    }

    /** Positive per-call snapshot; see the class comment. Not thread-safe: one installAll call uses it. */
    private static final class MemberBatch implements Batch {
        private final ClassLoader loader;
        // Created on first use, so a batch that serves a single target costs no more than resolve().
        private Map<String, Class<?>> loaded;
        private Map<String, Class<?>> initialized;
        private Map<Class<?>, Method[]> methods;
        private Map<Class<?>, Constructor<?>[]> constructors;
        private boolean closed;

        MemberBatch(ClassLoader loader) {
            this.loader = loader;
        }

        @Override
        public List<Executable> resolve(TargetSpec.DescriptorTarget descriptor) throws BackendInstallException {
            if (closed) throw new IllegalStateException("member batch is closed");
            return ReflectiveMemberResolver.resolve(descriptor, loader, this);
        }

        Class<?> load(String name, boolean initialize) throws ClassNotFoundException {
            Class<?> type = initialized == null ? null : initialized.get(name);
            if (type == null && !initialize && loaded != null) type = loaded.get(name);
            if (type != null) return type;
            type = Class.forName(name, initialize, loader);
            if (initialize) {
                if (initialized == null) initialized = new HashMap<String, Class<?>>();
                initialized.put(name, type);
            } else {
                if (loaded == null) loaded = new HashMap<String, Class<?>>();
                loaded.put(name, type);
            }
            return type;
        }

        Method[] declaredMethods(Class<?> type) {
            Method[] table = methods == null ? null : methods.get(type);
            if (table == null) {
                table = type.getDeclaredMethods();
                if (methods == null) methods = new HashMap<Class<?>, Method[]>();
                methods.put(type, table);
            }
            return table;
        }

        Constructor<?>[] declaredConstructors(Class<?> type) {
            Constructor<?>[] table = constructors == null ? null : constructors.get(type);
            if (table == null) {
                table = type.getDeclaredConstructors();
                if (constructors == null) constructors = new HashMap<Class<?>, Constructor<?>[]>();
                constructors.put(type, table);
            }
            return table;
        }

        @Override
        public void close() {
            closed = true;
            loaded = null;
            initialized = null;
            methods = null;
            constructors = null;
        }
    }

    private static List<Executable> resolve(TargetSpec.DescriptorTarget descriptor, ClassLoader loader, MemberBatch batch)
            throws BackendInstallException {
        Objects.requireNonNull(descriptor, "descriptor");
        Class<?> type;
        try {
            type = batch == null
                    ? Class.forName(descriptor.className(), descriptor.initialize(), loader)
                    : batch.load(descriptor.className(), descriptor.initialize());
        } catch (ClassNotFoundException missing) {
            return Collections.emptyList();
        } catch (LinkageError error) {
            throw new BackendInstallException("cannot load " + descriptor.className() + ": " + error, error);
        }

        List<Executable> matches = new ArrayList<Executable>();
        try {
            if (descriptor.kind() == TargetKind.CONSTRUCTOR) {
                Constructor<?>[] declared = batch == null ? type.getDeclaredConstructors() : batch.declaredConstructors(type);
                for (Constructor<?> constructor : declared) {
                    if (parametersMatch(constructor, descriptor)
                            && typeNameMatches(void.class, descriptor.returnTypeName())) {
                        matches.add(constructor);
                    }
                }
            } else {
                Method[] declared = batch == null ? type.getDeclaredMethods() : batch.declaredMethods(type);
                for (Method method : declared) {
                    if (method.isBridge() || method.isSynthetic()) {
                        continue;
                    }
                    if (!method.getName().equals(descriptor.memberName())) {
                        continue;
                    }
                    if (!parametersMatch(method, descriptor) || !returnMatches(method, descriptor)) {
                        continue;
                    }
                    matches.add(method);
                }
            }
        } catch (SecurityException error) {
            throw new BackendInstallException("cannot inspect " + descriptor.className() + ": " + error, error);
        } catch (LinkageError error) {
            throw new BackendInstallException("cannot inspect " + descriptor.className() + ": " + error, error);
        }

        return sortedBySignature(matches);
    }

    /**
     * Stable signature order. Each key is built once per candidate instead of twice per comparison,
     * and zero or one candidate needs no key at all; the resulting order is unchanged.
     */
    static List<Executable> sortedBySignature(List<Executable> matches) {
        int count = matches.size();
        if (count < 2) {
            return matches;
        }
        final String[] keys = new String[count];
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            keys[i] = signatureKey(matches.get(i));
            order[i] = i;
        }
        // Arrays.sort on objects is a stable merge sort, like Collections.sort before.
        java.util.Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer left, Integer right) {
                return keys[left].compareTo(keys[right]);
            }
        });
        List<Executable> sorted = new ArrayList<Executable>(count);
        for (Integer index : order) {
            sorted.add(matches.get(index));
        }
        return sorted;
    }

    private static boolean parametersMatch(Executable executable, TargetSpec.DescriptorTarget descriptor) {
        List<String> expected = descriptor.parameterTypeNames();
        if (expected == null) {
            return true;
        }
        // The arity check needs no parameter array copy; only same-arity candidates pay for one.
        if (executable.getParameterCount() != expected.size()) {
            return false;
        }
        Class<?>[] actual = executable.getParameterTypes();
        if (actual.length != expected.size()) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (!typeNameMatches(actual[i], expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean returnMatches(Method method, TargetSpec.DescriptorTarget descriptor) {
        return descriptor.returnTypeName() == null || typeNameMatches(method.getReturnType(), descriptor.returnTypeName());
    }

    private static boolean typeNameMatches(Class<?> type, String expected) {
        if (expected == null) {
            return true;
        }
        return type.getName().equals(expected) || expected.equals(type.getCanonicalName());
    }

    private static String signatureKey(Executable executable) {
        StringBuilder key = new StringBuilder(executable.getName()).append('(');
        for (Class<?> parameter : executable.getParameterTypes()) {
            key.append(parameter.getName()).append(';');
        }
        key.append(')');
        if (executable instanceof Method) {
            key.append(((Method) executable).getReturnType().getName());
        }
        return key.toString();
    }
}
