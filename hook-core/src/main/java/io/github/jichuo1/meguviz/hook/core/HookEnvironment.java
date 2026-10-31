package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer entry point. Install order is fixed: validate descriptor, prune by process, check
 * capabilities, bind against the actual loader, apply match policy and static return checks, build
 * immutable plans, install, then publish the receipt. Resolution, pruning and capability checks
 * never run inside a hot callback.
 *
 * <p>{@link MatchPolicy#ALL} creates one logical registration with one plan and receipt per target.
 * Ordinary per-target failure does not roll back successful siblings: the result is explicitly
 * PARTIALLY_INSTALLED and no cross-target atomicity is claimed. An epoch change is different: every
 * entry installed by the unpublished attempt is closed and no stale registration is published.
 * There is no resolution cache, so no stale negative lookup can be reused.
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
        List<Executable> targets = spec.target().matchPolicy() == MatchPolicy.ALL
                ? candidates : Collections.singletonList(candidates.get(0));

        CoreRegistration existing = registrations.get(id);
        if (existing != null && existing.state() == RegistrationState.RETIRING) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, existing.generation(), "previous generation is still retiring; retry after close completes");
        }
        if (existing != null && existing.state() != RegistrationState.INSTALLED) {
            // A fully closed generation must not block a fresh logical registration forever. The old
            // immutable plan may still be draining in flight, but it never receives new SDK work.
            registrations.remove(id);
            existing = null;
        }
        if (existing != null) {
            if (sameTargets(existing, targets) && existing.plan().spec().hasSameSemantics(spec)
                    && existing.isComplete()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.ALREADY_INSTALLED,
                        backendId, existing.generation(), "identical registration already installed");
            }
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "logical id is already used; close a partial/old registration before retry");
        }

        if (backend.context().codeEpoch() != epochBefore) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "code epoch changed during resolution; plan discarded");
        }

        long generation = generations.getAndIncrement();
        List<BoundPlan> plans = new ArrayList<BoundPlan>(targets.size());
        for (Executable target : targets) {
            plans.add(new BoundPlan(spec, target, generation, backend.context().classLoader(), epochBefore));
        }
        CoreRegistration registration = new CoreRegistration(plans, diagnostics);
        List<TargetInstallReceipt> targetReceipts = new ArrayList<TargetInstallReceipt>(plans.size());
        int installed = 0;
        for (BoundPlan plan : plans) {
            try {
                BackendHandle handle = backend.install(plan, registration.channel(plan));
                registration.bind(plan, handle);
                targetReceipts.add(TargetInstallReceipt.installed(plan.target()));
                installed++;
            } catch (BackendInstallException failure) {
                targetReceipts.add(TargetInstallReceipt.failed(plan.target(), failure.getMessage()));
            } catch (RuntimeException failure) {
                targetReceipts.add(TargetInstallReceipt.failed(plan.target(), failure.toString()));
            }
        }

        if (backend.context().codeEpoch() != epochBefore) {
            registration.close();
            List<TargetInstallReceipt> rolledBack = new ArrayList<TargetInstallReceipt>(targetReceipts.size());
            for (TargetInstallReceipt targetReceipt : targetReceipts) {
                rolledBack.add(targetReceipt.status() == TargetInstallReceipt.Status.INSTALLED
                        ? TargetInstallReceipt.rolledBack(targetReceipt.target(), "code epoch changed before publication")
                        : targetReceipt);
            }
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, generation, "code epoch changed during install; installed entries closed", null, rolledBack);
        }
        if (installed == 0) {
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.FAILED,
                    backendId, generation, "no target could be installed", null, targetReceipts);
        }

        registrations.put(id, registration);
        RegistrationReceipt.Status status = installed == plans.size()
                ? RegistrationReceipt.Status.INSTALLED : RegistrationReceipt.Status.PARTIALLY_INSTALLED;
        String message = "installed " + installed + "/" + plans.size() + " targets"
                + (status == RegistrationReceipt.Status.PARTIALLY_INSTALLED
                        ? "; successful entries remain registered; no cross-target atomicity" : "");
        return RegistrationReceipt.withTargets(id, status, backendId, generation, message,
                registration, targetReceipts);
    }

    private static boolean sameTargets(CoreRegistration registration, List<Executable> targets) {
        if (registration.plans().size() != targets.size()) {
            return false;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (!registration.plans().get(i).target().equals(targets.get(i))) {
                return false;
            }
        }
        return true;
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
