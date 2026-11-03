package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import io.github.jichuo1.meguviz.hook.api.EntrySnapshotInvocation;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;

/**
 * Registration shapes measured per invocation. Callbacks do the minimum observable work (a field
 * write into {@link Sink}) so that the measured cost is dispatch, not business logic. The target is
 * {@link DispatchTargets#add(int, int)} called with (3, 4); boxed values stay in the Integer cache,
 * so neither the terminal nor the callbacks allocate.
 */
public enum DispatchShape {
    BEFORE_NO_ARGS(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.before(invocation -> Sink.SINK.calls++);
        }
    },
    BEFORE_READ_ARGS_VIEW(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.access(ParameterAccess.READ_ARGS_VIEW)
                    .before(invocation -> Sink.SINK.value = ((ReadArgsInvocation) invocation).getArgument(0));
        }
    },
    BEFORE_READ_ARGS(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.access(ParameterAccess.READ_ARGS)
                    .before(invocation -> Sink.SINK.value = ((ReadArgsInvocation) invocation).getArgument(0));
        }
    },
    BEFORE_ENTRY_SNAPSHOT(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.access(ParameterAccess.ENTRY_SNAPSHOT)
                    .before(invocation -> Sink.SINK.value = ((EntrySnapshotInvocation) invocation).getEntryArgument(0));
        }
    },
    BEFORE_MUTABLE_SET(9) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.access(ParameterAccess.MUTABLE_ARGS)
                    .before(invocation -> ((MutableArgsInvocation) invocation).setArgument(0, FIVE));
        }
    },
    AFTER_SINGLE(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.after((invocation, result) -> Sink.SINK.value = result.getResult());
        }
    },
    BEFORE_AFTER(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.before(invocation -> Sink.SINK.calls++)
                    .after((invocation, result) -> Sink.SINK.value = result.getResult());
        }
    },
    BEFORE_AFTER_READ_ARGS(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.access(ParameterAccess.READ_ARGS)
                    .before(invocation -> Sink.SINK.value = ((ReadArgsInvocation) invocation).getArgument(0))
                    .after((invocation, result) -> Sink.SINK.value = result.getResult());
        }
    },
    AROUND_PROCEED(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.around(invocation -> invocation.proceed());
        }
    },
    /** Same work as BEFORE_READ_ARGS_VIEW through the typed builder method (one narrowing adapter). */
    TYPED_BEFORE_ARGS(7) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.beforeArgs(invocation -> Sink.SINK.value = invocation.getArgument(0));
        }
    },
    /** Same work as BEFORE_MUTABLE_SET through the typed builder method. */
    TYPED_BEFORE_MUTABLE_SET(9) {
        @Override HookSpec.Builder configure(HookSpec.Builder builder) {
            return builder.beforeMutableArgs(invocation -> invocation.setArgument(0, FIVE));
        }
    };

    static final Integer FIVE = 5;

    private final int expected;

    DispatchShape(int expected) {
        this.expected = expected;
    }

    abstract HookSpec.Builder configure(HookSpec.Builder builder);

    int expected() {
        return expected;
    }

    /** Escape target for callback side effects. */
    public static final class Sink {
        static final Sink SINK = new Sink();
        public long calls;
        public Object value;
    }
}
