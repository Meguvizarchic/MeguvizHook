package io.github.jichuo1.meguviz.hook.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Install-time process pruning. Filtering happens before binding and never inside a hot callback. */
public final class ProcessFilter {
    private static final ProcessFilter ALL = new ProcessFilter(null);

    private final Set<String> processNames;

    private ProcessFilter(Set<String> processNames) {
        this.processNames = processNames;
    }

    public static ProcessFilter all() {
        return ALL;
    }

    public static ProcessFilter only(String... names) {
        Objects.requireNonNull(names, "names");
        if (names.length == 0) {
            throw new IllegalArgumentException("at least one process name is required");
        }
        Set<String> copy = new HashSet<String>();
        for (String name : names) {
            Objects.requireNonNull(name, "process name");
            copy.add(name);
        }
        return new ProcessFilter(Collections.unmodifiableSet(copy));
    }

    public boolean matches(String processName) {
        return processNames == null || processNames.contains(processName);
    }

    /** {@code null} means every process; the set is otherwise immutable. */
    public Set<String> processNames() {
        return processNames;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProcessFilter
                && Objects.equals(processNames, ((ProcessFilter) other).processNames);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(processNames);
    }

    @Override
    public String toString() {
        return processNames == null ? "ProcessFilter[all]" : "ProcessFilter" + Arrays.toString(processNames.toArray());
    }
}
