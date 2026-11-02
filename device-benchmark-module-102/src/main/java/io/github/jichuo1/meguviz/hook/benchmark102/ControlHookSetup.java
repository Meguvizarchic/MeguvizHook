package io.github.jichuo1.meguviz.hook.benchmark102;

/** Install both control hooks or disarm and clean up every handle actually obtained. */
final class ControlHookSetup {
    interface Steps<T> {
        T installConfigure() throws Throwable;
        T installFinish() throws Throwable;
        void ready() throws Throwable;
        void disarm() throws Throwable;
        void unhook(T handle) throws Throwable;
    }

    private ControlHookSetup() { }

    static <T> void install(Steps<T> steps) throws Throwable {
        T configure = null;
        T finish = null;
        try {
            configure = steps.installConfigure();
            if (configure == null) {
                throw new IllegalStateException("framework returned no configure handle; outcome is unknown");
            }
            finish = steps.installFinish();
            if (finish == null) {
                throw new IllegalStateException("framework returned no finish handle; outcome is unknown");
            }
            steps.ready();
        } catch (Throwable failure) {
            try {
                steps.disarm();
            } catch (Throwable cleanup) {
                failure = preserveFailure(failure, cleanup);
            }
            if (finish != null) {
                try {
                    steps.unhook(finish);
                } catch (Throwable cleanup) {
                    failure = preserveFailure(failure, cleanup);
                }
            }
            if (configure != null) {
                try {
                    steps.unhook(configure);
                } catch (Throwable cleanup) {
                    failure = preserveFailure(failure, cleanup);
                }
            }
            throw failure;
        }
    }

    private static Throwable preserveFailure(Throwable first, Throwable next) {
        if (next instanceof Error && (!(first instanceof Error) || (isFatal(next) && !isFatal(first)))) {
            if (next != first) next.addSuppressed(first);
            return next;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }
}
