package io.github.jichuo1.meguviz.hook.api;

/**
 * Distinct chain capabilities. A backend must never present one of them as a silent substitute for
 * another; unsupported shapes are rejected at install time or downgraded only on explicit request.
 */
public enum ChainSemantics {
    /** The backend exposes a real framework interceptor chain with a native continuation. */
    FRAMEWORK_CHAIN,
    /** The SDK composes callbacks only inside its own registration, on top of one external hook. */
    MODULE_LOCAL_CHAIN,
    /** The backend exposes separate classic before/after callbacks. */
    CLASSIC_CALLBACKS,
    /** No usable chain; only used to report absence. */
    UNAVAILABLE
}
