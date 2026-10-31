package io.github.jichuo1.meguviz.hook.testing;

/**
 * Reflection targets for the contract fixtures. Method bodies are not executed by the fake backend
 * (tests supply original behaviours); the class exists to provide real Member identities, overloads,
 * primitive/void/constructor shapes and a bridge-method case.
 */
public final class Fixtures {
    private Fixtures() {
    }

    public static int add(int left, int right) {
        return left + right;
    }

    public static String concat(String left, String right) {
        return left + right;
    }

    public static void append(StringBuilder sink, String value) {
        sink.append(value);
    }

    public static int overloaded(int value) {
        return value;
    }

    public static String overloaded(String value) {
        return value;
    }

    public static Object nullableResult(boolean value) {
        return value ? null : new Object();
    }

    public static long noArguments() {
        return 42L;
    }

    /** Constructor target shape. */
    public static final class Constructed {
        private final int value;

        public Constructed(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    /** Generic interface used to produce a compiler-generated bridge method. */
    public interface GenericSource<T> {
        T value();
    }

    public static final class StringSource implements GenericSource<String> {
        @Override
        public String value() {
            return "fixture";
        }
    }
}
