package io.github.jichuo1.meguviz.hook.api;

/**
 * Raised when a consumer violates the invocation contract: duplicate proceed, cross-thread use,
 * access after the callback returned, or an around callback that left the outcome undecided.
 * It is a programming error, not a backend failure, and never triggers a replay of the original.
 */
public class HookContractException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public HookContractException(String message) {
        super(message);
    }
}
