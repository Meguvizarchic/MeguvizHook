package io.github.jichuo1.meguviz.hook.core.spi;

import io.github.jichuo1.meguviz.hook.api.TargetSpec;

import java.lang.reflect.Executable;
import java.util.List;

/**
 * Optional resolver capability for {@code HookEnvironment.installAll}: one {@link Batch} serves the
 * descriptor targets of one call that share a loader and code epoch, so a resolver can reuse work
 * (for example one declared-member table per class) across those targets.
 *
 * <p>The environment opens a batch lazily, only for a descriptor target that reaches resolution,
 * bound to the loader and code epoch it observed for that target. It closes the batch and opens a
 * new one when a later target observes a different loader or epoch, and always closes the last one
 * before {@code installAll} returns or throws. No batch outlives one {@code installAll} call. A
 * batch must return exactly what {@link MemberResolver#resolve} would return for the same target
 * and loader at that moment (same members in the same stable order); it may only skip repeated
 * work. It must never remember a missing target: a negative result is recomputed for every target.
 * Reuse inside a batch relies on {@link BackendContext#codeEpoch()} never returning to an earlier
 * value. Ordinary {@code install} never opens a batch.
 *
 * <p>Each {@link #openBatch} call must return a new, independent batch. Several batches of one
 * resolver can be open at the same time: a nested {@code installAll} from a resolver, backend or
 * receipt callback, or two environments sharing this resolver on different threads. One batch is
 * used by one thread at a time.
 */
public interface BatchMemberResolver extends MemberResolver {
    /**
     * Opens a batch for {@code loader}; may return null to resolve the targets of this loader and
     * epoch through {@link #resolve} instead. A {@link BackendInstallException} is reported to the
     * environment's diagnostics sink and has the same effect as returning null.
     */
    Batch openBatch(ClassLoader loader) throws BackendInstallException;

    /** A resolver scoped to one loader and code epoch within one {@code installAll} call. */
    interface Batch extends AutoCloseable {
        /** Same contract as {@link MemberResolver#resolve} for the batch's loader. */
        List<Executable> resolve(TargetSpec.DescriptorTarget descriptor) throws BackendInstallException;

        /**
         * Releases what the batch retained; it is called once. It should not throw: a non-fatal
         * throwable is reported to the environment's diagnostics sink and ignored.
         */
        @Override
        void close();
    }
}
