package io.github.jichuo1.meguviz.hook.api;

/**
 * Registration lifecycle. {@link #RETIRED_RESIDENT} means new SDK work is rejected while backend
 * or native artifacts may still be resident; it is a budgeted state, not a promise of unloading.
 */
public enum RegistrationState {
    PREPARED,
    INSTALLED,
    RETIRING,
    CLOSED,
    RETIRED_RESIDENT
}
