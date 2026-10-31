package io.github.jichuo1.meguviz.hook.api;

/** Target selection policy applied at install time, including its ambiguity behaviour. */
public enum MatchPolicy {
    /** Stable traversal order, first match wins. Only for explicitly requested compatibility behaviour. */
    FIRST,
    /** Default for the new API: exactly one match, otherwise an ambiguity/missing receipt. */
    UNIQUE,
    /** Resolve every match; each target gets its own receipt item. No cross-target atomicity is claimed. */
    ALL
}
