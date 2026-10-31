package io.github.jichuo1.meguviz.hook.api;

/**
 * Handle of one installed registration. Closing rejects new SDK work and reports what was actually
 * retired; it never revokes raw reflective Members the caller exported, never waits indefinitely
 * while holding a lock, and never replays side effects.
 */
public interface HookRegistration {
    HookId id();

    long generation();

    RegistrationState state();

    /**
     * Deliberately not {@code AutoCloseable}: close returns a report of what was actually retired,
     * which is incompatible with the void signature of {@code AutoCloseable.close()}.
     */
    CloseReport close();
}
