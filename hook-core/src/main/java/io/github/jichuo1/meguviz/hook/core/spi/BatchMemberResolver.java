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
 * Ordinary {@code install} never opens a batch.
 */
public interface BatchMemberResolver extends MemberResolver {
    /**
     * Opens a batch for {@code loader}; may return null to resolve this call's targets through
     * {@link #resolve} instead. A {@link BackendInstallException} fails only the target that caused
     * the batch to be opened, like a resolution failure of that target.
     */
    Batch openBatch(ClassLoader loader) throws BackendInstallException;

    /** A resolver scoped to one loader and code epoch within one {@code installAll} call. */
    interface Batch extends AutoCloseable {
        /** Same contract as {@link MemberResolver#resolve} for the batch's loader. */
        List<Executable> resolve(TargetSpec.DescriptorTarget descriptor) throws BackendInstallException;

        /** Releases what the batch retained. Must not throw; it is called once. */
        @Override
        void close();
    }
}
