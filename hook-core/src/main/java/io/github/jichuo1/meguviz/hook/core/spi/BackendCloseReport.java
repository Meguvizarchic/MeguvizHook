package io.github.jichuo1.meguviz.hook.core.spi;

import io.github.jichuo1.meguviz.hook.api.CloseReport;

/** Backend facts about one uninstall; physical release is normally not claimed. */
public final class BackendCloseReport {
    private final CloseReport.EntryRestoration entryRestoration;
    private final CloseReport.PhysicalRelease physicalRelease;
    private final boolean resident;
    private final String message;

    public BackendCloseReport(CloseReport.EntryRestoration entryRestoration,
                              CloseReport.PhysicalRelease physicalRelease, boolean resident, String message) {
        this.entryRestoration = entryRestoration;
        this.physicalRelease = physicalRelease;
        this.resident = resident;
        this.message = message;
    }

    public CloseReport.EntryRestoration entryRestoration() {
        return entryRestoration;
    }

    public CloseReport.PhysicalRelease physicalRelease() {
        return physicalRelease;
    }

    /** True when code or native artifacts stay resident and must be budgeted as RetiredResident. */
    public boolean resident() {
        return resident;
    }

    public String message() {
        return message;
    }
}
