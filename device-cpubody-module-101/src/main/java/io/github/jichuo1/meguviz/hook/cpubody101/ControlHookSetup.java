package io.github.jichuo1.meguviz.hook.cpubody101;

/** Publish both control hooks together; never guess the outcome of an unreturned handle. */
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
        T configure = null, finish = null;
        try {
            configure = steps.installConfigure();
            if (configure == null) throw new IllegalStateException("configure handle missing; outcome unknown");
            finish = steps.installFinish();
            if (finish == null) throw new IllegalStateException("finish handle missing; outcome unknown");
            steps.ready();
        } catch (Throwable failure) {
            if (fatal(failure)) throw failure;
            try { steps.disarm(); }
            catch (Throwable cleanup) { preserve(failure, cleanup); }
            if (finish != null) {
                try { steps.unhook(finish); }
                catch (Throwable cleanup) { preserve(failure, cleanup); }
            }
            if (configure != null) {
                try { steps.unhook(configure); }
                catch (Throwable cleanup) { preserve(failure, cleanup); }
            }
            throw failure;
        }
    }

    private static void preserve(Throwable original, Throwable cleanup) throws Throwable {
        if (cleanup == original) return;
        if (fatal(cleanup)) {
            cleanup.addSuppressed(original);
            throw cleanup;
        }
        original.addSuppressed(cleanup);
    }

    private static boolean fatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }
}
