package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;

/** One-shot installation transaction. The adapter and clock calls are included in the raw interval. */
final class B07Runner {
    interface Clock { long wall(); long cpu(); }
    interface Steps {
        void prepare() throws Throwable;
        RegistrationReceipt install() throws Throwable;
        void retain(RegistrationReceipt receipt) throws Throwable;
        void validate(RegistrationReceipt receipt) throws Throwable;
        void hookCheck() throws Throwable;
        void checkFramework() throws Throwable;
        CloseReport closeRegistration() throws Throwable;
        void closeEnvironment() throws Throwable;
        void postClose() throws Throwable;
    }
    interface Events {
        void installed(RegistrationReceipt receipt, long wallStart, long cpuStart, long cpuEnd, long wallEnd);
        void hookChecked();
        void configured();
        void cleaned(CloseReport report);
        void postClosed();
        void failed(String stage, Throwable failure, long wallStart, long cpuStart, long cpuEnd, long wallEnd,
                    boolean wallStartRead, boolean cpuStartRead, boolean cpuEndRead, boolean wallEndRead,
                    boolean outcomeUnknown);
    }

    private final Clock clock;
    private final Steps steps;
    private final Events events;
    private long wallStart = -1L, cpuStart = -1L, cpuEnd = -1L, wallEnd = -1L;
    private boolean wallStartRead, cpuStartRead, cpuEndRead, wallEndRead;
    private boolean configureCalled, attempted, unknown, configured, finished;
    private RegistrationReceipt receipt;

    B07Runner(Clock clock, Steps steps, Events events) {
        this.clock = clock; this.steps = steps; this.events = events;
    }

    boolean configure() {
        if (configureCalled || finished) return false;
        configureCalled = true;
        String stage = "install";
        try {
            steps.prepare();
            wallStart = clock.wall(); wallStartRead = true;
            cpuStart = clock.cpu(); cpuStartRead = true;
            if (wallStart < 0 || cpuStart < 0) throw new IllegalStateException("clock unavailable");
            Throwable pending = null;
            attempted = true;
            try { receipt = steps.install(); }
            catch (Throwable failure) { unknown = true; pending = failure; }
            try { cpuEnd = clock.cpu(); cpuEndRead = true; }
            catch (Throwable failure) { pending = combine(pending, failure); }
            try { wallEnd = clock.wall(); wallEndRead = true; }
            catch (Throwable failure) { pending = combine(pending, failure); }
            if (receipt != null) {
                try { steps.retain(receipt); }
                catch (Throwable failure) { unknown = true; pending = combine(pending, failure); }
            }
            if (pending != null) throw pending;
            if (receipt == null || !BenchmarkEntry.b07ValidClocks(wallStart, cpuStart, cpuEnd, wallEnd)) {
                throw new IllegalStateException("invalid installation receipt or clock");
            }
            steps.validate(receipt);
            events.installed(receipt, wallStart, cpuStart, cpuEnd, wallEnd);
            stage = "hook_check";
            steps.hookCheck();
            events.hookChecked();
            stage = "configured";
            steps.checkFramework();
            events.configured();
            configured = true;
            return true;
        } catch (Throwable failure) {
            events.failed(stage, failure, wallStart, cpuStart, cpuEnd, wallEnd,
                    wallStartRead, cpuStartRead, cpuEndRead, wallEndRead, unknown);
            if (failure instanceof Error) throw (Error) failure;
            return false;
        }
    }

    boolean finish() {
        if (finished) return false;
        finished = true;
        CloseReport report = null;
        Throwable failure = null;
        try { report = steps.closeRegistration(); }
        catch (Throwable error) { failure = error; }
        try { steps.closeEnvironment(); }
        catch (Throwable error) { failure = combine(failure, error); }
        if (failure != null || unknown || (attempted && receipt == null) || !BenchmarkEntry.b07CloseRestored(report)) {
            Throwable detail = failure == null ? new IllegalStateException("cleanup_unconfirmed") : failure;
            events.failed("cleanup", detail, wallStart, cpuStart, cpuEnd, wallEnd,
                    wallStartRead, cpuStartRead, cpuEndRead, wallEndRead, unknown);
            if (failure instanceof Error) throw (Error) failure;
            return false;
        }
        events.cleaned(report);
        if (!configured) return false;
        try {
            steps.postClose();
            events.postClosed();
            return true;
        } catch (Throwable error) {
            events.failed("post_close", error, wallStart, cpuStart, cpuEnd, wallEnd,
                    wallStartRead, cpuStartRead, cpuEndRead, wallEndRead, unknown);
            if (error instanceof Error) throw (Error) error;
            return false;
        }
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        if (next instanceof Error && (!(first instanceof Error) || (isFatal(next) && !isFatal(first)))) {
            next.addSuppressed(first); return next;
        }
        first.addSuppressed(next); return first;
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable value) {
        return value instanceof VirtualMachineError || value instanceof ThreadDeath;
    }
}
