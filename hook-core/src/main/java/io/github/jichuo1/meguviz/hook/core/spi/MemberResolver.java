package io.github.jichuo1.meguviz.hook.core.spi;

import io.github.jichuo1.meguviz.hook.api.TargetSpec;

import java.lang.reflect.Executable;
import java.util.List;

/**
 * Optional resolver SPI. The core ships a small reflective resolver for exact descriptors; it does
 * not try to be a general purpose member finder, and resolution never runs inside a hot callback.
 */
public interface MemberResolver {
    /**
     * Resolves candidates in a stable order for the given loader. An empty list means the target is
     * missing; ambiguity is decided by the caller using the spec's match policy.
     */
    List<Executable> resolve(TargetSpec.DescriptorTarget descriptor, ClassLoader loader) throws BackendInstallException;
}
