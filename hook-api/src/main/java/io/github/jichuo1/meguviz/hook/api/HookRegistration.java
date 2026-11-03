package io.github.jichuo1.meguviz.hook.api;

/**
 * Handle of one installed registration. Closing rejects new SDK work and reports what was actually
 * retired. It does not revoke exported reflective Members, wait for in-flight callbacks to drain,
 * or replay side effects. A backend uninstall can itself block: it must not synchronously wait for
 * another thread to finish closing this same registration, because that close awaits its report.
 */
public interface HookRegistration {
    HookId id();

    long generation();

    RegistrationState state();

    /**
     * Deliberately not {@code AutoCloseable}: close returns a report of what was actually retired,
     * which is incompatible with the void signature of {@code AutoCloseable.close()}.
     * A synchronous reentrant call from this registration's backend replacement or uninstall is
     * rejected with {@link IllegalStateException}; it does not claim a completed close report.
     */
    CloseReport close();
}
