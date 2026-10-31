package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.HookId;

/**
 * Bounded, opt-in diagnostics. Disabled by default: no service connection, thread pool, stack
 * capture or deep snapshot is created for it. Used on cold failure paths only, never to turn an
 * ordinary callback failure into a hidden retry.
 */
public interface DiagnosticsSink {
    DiagnosticsSink NONE = new DiagnosticsSink() {
        @Override
        public void record(Event event) {
            // Disabled.
        }
    };

    void record(Event event);

    /** One diagnostic record. Fatal VM events are never routed here instead of being rethrown. */
    final class Event {
        public enum Kind {
            AFTER_CALLBACK_FAILED_WHILE_ORIGINAL_FAILED,
            BACKEND_FAILURE
        }

        private final Kind kind;
        private final HookId id;
        private final long generation;
        private final Throwable failure;
        private final String message;

        public Event(Kind kind, HookId id, long generation, Throwable failure, String message) {
            this.kind = kind;
            this.id = id;
            this.generation = generation;
            this.failure = failure;
            this.message = message;
        }

        public Kind kind() {
            return kind;
        }

        public HookId id() {
            return id;
        }

        public long generation() {
            return generation;
        }

        public Throwable failure() {
            return failure;
        }

        public String message() {
            return message;
        }
    }
}
