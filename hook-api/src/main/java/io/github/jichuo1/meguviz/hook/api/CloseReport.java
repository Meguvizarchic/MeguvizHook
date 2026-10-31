package io.github.jichuo1.meguviz.hook.api;

/**
 * What a close actually achieved. Entry restoration, in-flight drainage and physical release are
 * separate claims. A zero SDK lease count only covers the calls counted by this SDK; it never
 * proves that arbitrary machine-code return addresses left the hooked region.
 */
public final class CloseReport {
    public enum EntryRestoration {
        RESTORED,
        NOT_RESTORED,
        UNKNOWN
    }

    public enum PhysicalRelease {
        RELEASED,
        NOT_CLAIMED,
        UNKNOWN
    }

    private final RegistrationState state;
    private final boolean newCallbacksRejected;
    private final EntryRestoration entryRestoration;
    private final int inFlightAtClose;
    private final PhysicalRelease physicalRelease;
    private final String message;

    public CloseReport(RegistrationState state, boolean newCallbacksRejected, EntryRestoration entryRestoration,
                       int inFlightAtClose, PhysicalRelease physicalRelease, String message) {
        this.state = state;
        this.newCallbacksRejected = newCallbacksRejected;
        this.entryRestoration = entryRestoration;
        this.inFlightAtClose = inFlightAtClose;
        this.physicalRelease = physicalRelease;
        this.message = message;
    }

    public RegistrationState state() {
        return state;
    }

    public boolean newCallbacksRejected() {
        return newCallbacksRejected;
    }

    public EntryRestoration entryRestoration() {
        return entryRestoration;
    }

    public int inFlightAtClose() {
        return inFlightAtClose;
    }

    public PhysicalRelease physicalRelease() {
        return physicalRelease;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "CloseReport[" + state + ", rejected=" + newCallbacksRejected + ", entry=" + entryRestoration
                + ", inFlight=" + inFlightAtClose + ", physical=" + physicalRelease
                + (message == null ? "" : ", " + message) + "]";
    }
}
