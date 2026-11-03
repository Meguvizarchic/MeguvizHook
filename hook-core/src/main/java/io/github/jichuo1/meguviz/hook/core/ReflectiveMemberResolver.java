package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Small exact-descriptor resolver used at install time only.
 *
 * <p>Scope of this implementation: declared methods/constructors of one class loaded from the
 * actual backend loader, with optional parameter and return type checks. Synthetic and bridge
 * methods are excluded. Candidates are sorted by a stable signature key so {@code FIRST} does not
 * depend on VM reflection order. No resolution cache is kept, so no negative result can outlive a
 * code epoch. Inherited members and dex-wide search are out of scope and belong to a consumer-side
 * resolver SPI implementation.
 */
public final class ReflectiveMemberResolver implements MemberResolver {
    @Override
    public List<Executable> resolve(TargetSpec.DescriptorTarget descriptor, ClassLoader loader) throws BackendInstallException {
        Class<?> type;
        try {
            type = Class.forName(descriptor.className(), descriptor.initialize(), loader);
        } catch (ClassNotFoundException missing) {
            return Collections.emptyList();
        } catch (LinkageError error) {
            throw new BackendInstallException("cannot load " + descriptor.className() + ": " + error, error);
        }

        List<Executable> matches = new ArrayList<Executable>();
        try {
            if (descriptor.kind() == TargetKind.CONSTRUCTOR) {
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (parametersMatch(constructor, descriptor)
                            && typeNameMatches(void.class, descriptor.returnTypeName())) {
                        matches.add(constructor);
                    }
                }
            } else {
                for (Method method : type.getDeclaredMethods()) {
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
