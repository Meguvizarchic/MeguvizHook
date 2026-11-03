package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.ref.api.descriptor.TypeDescriptor;
import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** The pre-export filter must be a superset of the bridge's exact name check. */
public class JvmTypeNamesTest {
    static final class Nested { static final class Deeper { } }
    @SuppressWarnings("unused") private final Object anonymous = new Object() { };
    @SuppressWarnings("unused") private final Runnable lambda = () -> { };

    @Test public void neverRejectsWhatTheExactCheckAccepts() {
        class Local { }
        List<Class<?>> types = new ArrayList<>();
        Class<?>[] bases = {
            boolean.class, byte.class, char.class, short.class, int.class, long.class, float.class, double.class,
            void.class, Object.class, String.class, Integer.class, Map.Entry.class, Serializable.class,
            Nested.class, Nested.Deeper.class, Local.class, anonymous.getClass(), lambda.getClass(),
            JvmTypeNamesTest.class,
        };
        for (Class<?> base : bases) {
            types.add(base);
            if (base == void.class || base.isHidden()) continue;
            Class<?> array = base;
            for (int dimension = 0; dimension < 3; dimension++) {
                array = Array.newInstance(array, 0).getClass();
                types.add(array);
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> type : types) {
            names.add(type.getName());
            if (type.getCanonicalName() != null) names.add(type.getCanonicalName());
        }
        names.add("java.lang.Integer[]");
        names.add("int[][][][]");
        names.add("[I");
        names.add("Nested");
        int checked = 0;
        for (Class<?> type : types) {
            String descriptor = TypeDescriptor.fromClass(type).value();
            for (String expected : names) {
                boolean exact = type.getName().equals(expected) || expected.equals(type.getCanonicalName());
                if (exact) {
                    assertTrue(type + " / " + expected, JvmTypeNames.mayMatch(descriptor, expected));
                    checked++;
                }
            }
        }
        assertTrue("every type matches at least its own name", checked >= types.size());
    }

    @Test public void rejectsMetadataIndependentMismatches() {
        assertFalse(JvmTypeNames.mayMatch("I", "long"));
        assertFalse(JvmTypeNames.mayMatch("I", "java.lang.Integer"));
        assertFalse(JvmTypeNames.mayMatch("Ljava/lang/Integer;", "int"));
        assertFalse(JvmTypeNames.mayMatch("[Ljava/lang/String;", "java.lang.String"));
        assertFalse(JvmTypeNames.mayMatch("Ljava/lang/String;", "java.lang.String[]"));
        assertFalse(JvmTypeNames.mayMatch("[[I", "int[]"));
        assertFalse(JvmTypeNames.mayMatch("[I", "[J"));
        assertFalse(JvmTypeNames.mayMatch("V", "java.lang.Void"));
    }

    @Test public void leavesObjectNamesToTheExactCheck() {
        assertTrue(JvmTypeNames.mayMatch("Ljava/lang/String;", "java.lang.String"));
        assertTrue(JvmTypeNames.mayMatch("Ljava/util/Map$Entry;", "java.util.Map.Entry"));
        // Obfuscated nesting metadata may give any dotted canonical name; only the exact check knows.
        assertTrue(JvmTypeNames.mayMatch("La/b;", "a.Renamed"));
        assertTrue(JvmTypeNames.mayMatch("[[La/b;", "a.Renamed[][]"));
        assertTrue(JvmTypeNames.mayMatch("I", "int"));
        assertTrue(JvmTypeNames.mayMatch("[I", "int[]"));
        assertTrue(JvmTypeNames.mayMatch("V", "void"));
        assertTrue(JvmTypeNames.mayMatch("I", null));
        assertTrue("unrecognized descriptors are not filtered", JvmTypeNames.mayMatch("Q", "anything"));
    }
}
