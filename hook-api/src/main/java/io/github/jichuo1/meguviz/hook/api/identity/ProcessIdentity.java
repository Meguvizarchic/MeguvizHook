package io.github.jichuo1.meguviz.hook.api.identity;

import java.util.Objects;

/**
 * Process identity. A pid alone is not a long-term identity (it is recycled), so the process name
 * and an optional start hint travel together. This type is a value snapshot, not a live handle.
 */
public final class ProcessIdentity {
    private final String processName;
    private final long pid;
    private final long startHintMillis;

    public ProcessIdentity(String processName, long pid, long startHintMillis) {
        this.processName = Objects.requireNonNull(processName, "processName");
        this.pid = pid;
        this.startHintMillis = startHintMillis;
    }

    public String processName() {
        return processName;
    }

    public long pid() {
        return pid;
    }

    public long startHintMillis() {
        return startHintMillis;
    }

    @Override
    public String toString() {
        return "ProcessIdentity[" + processName + " pid=" + pid + " startHint=" + startHintMillis + "]";
    }
}
