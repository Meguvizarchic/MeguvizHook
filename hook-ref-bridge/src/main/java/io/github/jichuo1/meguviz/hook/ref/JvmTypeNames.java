package io.github.jichuo1.meguviz.hook.ref;

/**
 * Conservative comparison between a JVM field descriptor (as held by a MeguvizRef token) and a
 * hook descriptor type name, which the bridge later matches exactly against
 * {@code Class.getName()} or {@code Class.getCanonicalName()}.
 *
 * <p>{@link #mayMatch} never rejects a pair that the exact check could accept. A class's canonical
 * name depends on nesting metadata that obfuscators may rewrite, so object type names are not
 * compared here. Only mismatches that hold for any metadata are rejected: a different primitive,
 * an object where a primitive is expected (or the reverse), or a different array depth. Unqualified
 * names cannot contain {@code [} (JVMS §4.2.2), so no class or canonical name ends with {@code []}.
 */
final class JvmTypeNames {
    private JvmTypeNames() {
    }

    static boolean mayMatch(String descriptor, String expected) {
        if (expected == null) return true;
        String binary = binaryName(descriptor);
        // An unrecognized descriptor or an exact binary-name match: decide after export.
        if (binary == null || binary.equals(expected)) return true;
        // Class.getName() of an array is fully determined by its descriptor, and no other type's
        // name or canonical name starts with '['.
        if (expected.startsWith("[")) return false;
        int dimensions = 0;
        while (descriptor.charAt(dimensions) == '[') dimensions++;
        // A canonical array name is the component's canonical name plus one "[]" per dimension.
        String element = expected;
        for (int i = 0; i < dimensions; i++) {
            if (!element.endsWith("[]")) return false;
            element = element.substring(0, element.length() - 2);
        }
        if (element.endsWith("[]")) return false;
        char code = descriptor.charAt(dimensions);
        if (code != 'L') return element.equals(primitive(code));
        return !isPrimitiveName(element);
    }

    /** {@code Class.getName()} for a field descriptor, or null when the descriptor is not recognized. */
    static String binaryName(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return null;
        char first = descriptor.charAt(0);
        if (first == '[') {
            int dimensions = 0;
            while (dimensions < descriptor.length() && descriptor.charAt(dimensions) == '[') dimensions++;
            String component = descriptor.substring(dimensions);
            if (component.isEmpty() || component.charAt(0) == 'V' || binaryName(component) == null) return null;
            return descriptor.replace('/', '.');
        }
        if (first == 'L') {
            if (descriptor.length() < 3 || descriptor.charAt(descriptor.length() - 1) != ';') return null;
            String name = descriptor.substring(1, descriptor.length() - 1);
            if (name.indexOf('.') >= 0 || name.indexOf(';') >= 0 || name.indexOf('[') >= 0) return null;
            return name.replace('/', '.');
        }
        return descriptor.length() == 1 ? primitive(first) : null;
    }

    private static boolean isPrimitiveName(String name) {
        switch (name) {
            case "boolean": case "byte": case "char": case "short": case "int":
            case "long": case "float": case "double": case "void":
                return true;
            default:
                return false;
        }
    }

    private static String primitive(char code) {
        switch (code) {
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'C': return "char";
            case 'S': return "short";
            case 'I': return "int";
            case 'J': return "long";
            case 'F': return "float";
            case 'D': return "double";
            case 'V': return "void";
            default: return null;
        }
    }
}
