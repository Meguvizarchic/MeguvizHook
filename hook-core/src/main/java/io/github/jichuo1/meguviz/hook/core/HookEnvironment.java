package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;

import java.lang.reflect.Executable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer entry point. Install order is fixed: validate descriptor, prune by process, check
 * capabilities, bind against the actual loader, apply match policy and static return checks, build
 * the immutable plan, install, then publish the receipt. Resolution, pruning and capability checks
 * never run inside a hot callback.
 *
 * <p>Known limits of this slice, reported as explicit refusals instead of silent degradation:
 * <ul>
 *   <li>only framework-chain backends are dispatched; classic-callback and local-only adapters are
 *       rejected with {@link RegistrationReceipt.Status#UNSUPPORTED_CAPABILITY};</li>
 *   <li>{@link MatchPolicy#ALL} is not implemented yet and is refused the same way;</li>
 *   <li>no resolution cache exists, so no stale negative lookup can be reused.</li>
 * </ul>
 */
public final class HookEnvironment implements AutoCloseable {
    private final HookBackend backend;
    private final MemberResolver resolver;
    private final DiagnosticsSink diagnostics;
    private final Map<HookId, CoreRegistration> registrations = new LinkedHashMap<HookId, CoreRegistration>();
    private final AtomicLong generations = new AtomicLong(1L);
    private boolean closed;

    private HookEnvironment(HookBackend backend, MemberResolver resolver, DiagnosticsSink diagnostics) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static HookEnvironment create(HookBackend backend) {
        return new HookEnvironment(backend, new ReflectiveMemberResolver(), DiagnosticsSink.NONE);
    }

    public static HookEnvironment create(HookBackend backend, MemberResolver resolver, DiagnosticsSink diagnostics) {
        return new HookEnvironment(backend, resolver, diagnostics);
    }

    public BackendCapabilities capabilities() {
        return backend.capabilities();
    }

    public synchronized RegistrationReceipt install(HookSpec spec) {
        Objects.requireNonNull(spec, "spec");
        HookId id = spec.id();
        String backendId = backend.id();
        if (closed) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, -1L, "environment is closed");
        }
        if (!spec.processFilter().matches(backend.context().processName())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FILTERED,
                    backendId, -1L, "process " + backend.context().processName() + " pruned at install time");
        }
        for (Capability capability : spec.requiredCapabilities()) {
            CapabilityResult result = backend.capabilities().lookup(capability);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "required " + capability + ": " + result);
            }
        }
        if (backend.capabilities().chainSemantics() != ChainSemantics.FRAMEWORK_CHAIN) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "this slice dispatches framework chains only; backend declares "
                            + backend.capabilities().chainSemantics());
        }
        if (spec.target().matchPolicy() == MatchPolicy.ALL) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "MatchPolicy.ALL is not implemented in this slice");
        }

        long epochBefore = backend.context().codeEpoch();
        List<Executable> candidates;
        if (spec.target() instanceof TargetSpec.MemberTarget) {
            candidates = Collections.singletonList(((TargetSpec.MemberTarget) spec.target()).executable());
        } else {
            try {
                candidates = resolver.resolve((TargetSpec.DescriptorTarget) spec.target(), backend.context().classLoader());
            } catch (BackendInstallException failure) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED, backendId, -1L, failure.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TARGET_MISSING,
                    backendId, -1L, "no target matched " + spec.target());
        }
        if (candidates.size() > 1 && spec.target().matchPolicy() == MatchPolicy.UNIQUE) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.AMBIGUOUS_TARGET,
                    backendId, -1L, candidates.size() + " targets matched " + spec.target() + " under UNIQUE");
        }
        Executable target = candidates.get(0);

        CoreRegistration existing = registrations.get(id);
        if (existing != null) {
            if (existing.plan().target().equals(target) && existing.plan().spec().hasSameSemantics(spec)) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.ALREADY_INSTALLED,
                        backendId, existing.generation(), "identical registration already installed");
            }
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "logical id is already used with different semantics; not overwritten");
        }

        if (backend.context().codeEpoch() != epochBefore) {
            // Never publish a plan bound to an outdated epoch.
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "code epoch changed during install; plan discarded");
        }

        BoundPlan plan = new BoundPlan(spec, target, generations.getAndIncrement(), backend.context().classLoader(), epochBefore);
        CoreRegistration registration = new CoreRegistration(plan, diagnostics);
        try {
            BackendHandle handle = backend.install(plan, registration);
            registration.bind(handle);
        } catch (BackendInstallException failure) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED, backendId, -1L, failure.getMessage());
        } catch (RuntimeException failure) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED, backendId, -1L, failure.toString());
        }
        registrations.put(id, registration);
        return RegistrationReceipt.installed(registration, backendId, "installed on " + target);
    }

    public synchronized int activeRegistrationCount() {
        return registrations.size();
    }

    /** Closes every registration; each close reports its own restoration and in-flight facts. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (CoreRegistration registration : registrations.values()) {
            registration.close();
        }
        registrations.clear();
    }
}
