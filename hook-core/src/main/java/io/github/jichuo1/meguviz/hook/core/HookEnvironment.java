package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.api.StringArgumentCondition;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer entry point. Install order is fixed: validate descriptor, prune by process, check
 * capabilities, bind against the actual loader, apply match policy and static return checks, build
 * immutable plans, install, then publish the receipt. Resolution, pruning and capability checks
 * never run inside a hot callback.
 *
 * <p>{@link MatchPolicy#ALL} creates one logical registration with one plan and receipt per target.
 * Ordinary per-target failure does not roll back successful siblings: the result is explicitly
 * PARTIALLY_INSTALLED and no cross-target atomicity is claimed. An identity change is different: every
 * entry installed by the unpublished attempt is closed and no stale registration is published.
 * There is no resolution cache, so no stale negative lookup can be reused.
 */
public final class HookEnvironment implements AutoCloseable {
    private final HookBackend backend;
    private final MemberResolver resolver;
    private final DiagnosticsSink diagnostics;
    private final Map<HookId, CoreRegistration> registrations = new LinkedHashMap<HookId, CoreRegistration>();
    private final Set<HookId> interruptedInstalls = new HashSet<HookId>();
    // Independent of registrations: it remains reachable if publishing there fails before/after put.
    private final Map<HookId, CoreRegistration> interruptedInstallPublications = new LinkedHashMap<HookId, CoreRegistration>();
    // Anchors both generations until publication; retained for an indeterminate replacement.
    private final Map<HookId, CoreRegistration> interruptedReplacements = new LinkedHashMap<HookId, CoreRegistration>();
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
        return installInternal(spec, null);
    }

    /**
     * Adopts one framework-delivered old hook into this new module generation. The caller supplies
     * a backend-owned bridge valid only for the current framework reload callback, with an explicit
     * and trustworthy mapping between that old handle and {@code spec}; neither old SDK objects nor
     * guessed matches are accepted as a migration mechanism. The module remains responsible for
     * retiring old-generation resources and for handling every old hook not adopted here.
     *
     * <p>Only a single resolved target is supported; this does not promise atomicity across hooks or
     * class loaders. On an indeterminate backend outcome the new channel is retired and this logical
     * id is quarantined. The old framework entry may or may not have been replaced.
     */
    public synchronized RegistrationReceipt adopt(HookSpec spec, BackendAdoption adoption) {
        return installInternal(spec, Objects.requireNonNull(adoption, "adoption"));
    }

    private RegistrationReceipt installInternal(HookSpec spec, BackendAdoption adoption) {
        Objects.requireNonNull(spec, "spec");
        HookId id = spec.id();
        String backendId = backend.id();
        if (closed) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, -1L, "environment is closed");
        }
        if (adoption != null && adoption.backend() != backend) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, -1L, "adoption bridge belongs to a different backend instance");
        }
        if (adoption != null && spec.target().matchPolicy() == MatchPolicy.ALL) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, -1L, "hot-reload adoption requires one target; ALL is not supported");
        }
        CoreRegistration interruptedPublication = interruptedInstallPublications.get(id);
        if (interruptedPublication != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedPublication.generation(),
                    "previous install publication was interrupted; id is quarantined; "
                            + "bound-handle cleanup: " + interruptedPublication.closeReport());
        }
        CoreRegistration interruptedReplacement = interruptedReplacements.get(id);
        if (interruptedReplacement != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedReplacement.generation(), "replacement publication is indeterminate; id is quarantined");
        }
        if (interruptedInstalls.contains(id)) {
            CoreRegistration interrupted = registrations.get(id);
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interrupted.generation(),
                    "previous install was interrupted; backend effects are unknown and this id "
                            + "is quarantined in this environment; "
                            + "bound-handle cleanup: " + interrupted.closeReport());
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
        if (spec.errorPolicy() == ErrorPolicy.FRAMEWORK_PROTECTIVE) {
            CapabilityResult result = backend.capabilities().lookup(Capability.FRAMEWORK_PROTECTIVE);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "framework protective policy is not supported: " + result);
            }
        }
        if (backend.capabilities().chainSemantics() != ChainSemantics.FRAMEWORK_CHAIN) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "this slice dispatches framework chains only; backend declares "
                            + backend.capabilities().chainSemantics());
        }

        long epochBefore = backend.context().codeEpoch();
        ClassLoader loaderBefore = backend.context().classLoader();
        CoreRegistration previous = registrations.get(id);
        if (previous != null && retirementBlocksReuse(previous)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, previous.generation(), "previous registration entry restoration is not confirmed");
        }
        if (previous != null && previous.state() == RegistrationState.INSTALLED
                && (previous.plan().loader() != loaderBefore || previous.plan().codeEpoch() != epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, previous.generation(), "previous registration has a different loader or code epoch; close before retry");
        }
        List<Executable> candidates;
        if (spec.target() instanceof TargetSpec.MemberTarget) {
            candidates = Collections.singletonList(((TargetSpec.MemberTarget) spec.target()).executable());
        } else {
            try {
                candidates = resolver.resolve((TargetSpec.DescriptorTarget) spec.target(), loaderBefore);
            } catch (BackendInstallException failure) {
                if (identityChanged(loaderBefore, epochBefore)) {
                    return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                            backendId, -1L, "loader or code epoch changed during resolution; plan discarded");
                }
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED, backendId, -1L, failure.getMessage());
            }
        }
        if (identityChanged(loaderBefore, epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "loader or code epoch changed during resolution; plan discarded");
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

        String invalidConstant = invalidConstantReplacement(spec, targets);
        if (spec.stringArgumentCondition() != null && identityChanged(loaderBefore, epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "loader or code epoch changed during conditional validation; plan discarded");
        }
        if (invalidConstant != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED,
                    backendId, -1L, invalidConstant);
        }

        CoreRegistration existing = registrations.get(id);
        if (existing != null && existing.state() == RegistrationState.RETIRING) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, existing.generation(), "previous generation is still retiring; retry after close completes");
        }
        if (existing != null && existing.state() != RegistrationState.INSTALLED) {
            if (retirementBlocksReuse(existing)) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                        backendId, existing.generation(), "previous registration entry restoration is not confirmed");
            }
            // A fully closed generation must not block a fresh logical registration forever. The old
            // immutable plan may still be draining in flight, but it never receives new SDK work.
            registrations.remove(id);
            existing = null;
        }
        if (existing != null) {
            if (adoption != null) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                        backendId, existing.generation(), "adoption requires an unused logical id in the new environment");
            }
            if (sameTargets(existing, targets) && existing.plan().spec().hasSameSemantics(spec)
                    && existing.isComplete()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.ALREADY_INSTALLED,
                        backendId, existing.generation(), "identical registration already installed");
            }
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "logical id is already used; close a partial/old registration before retry");
        }

        if (identityChanged(loaderBefore, epochBefore)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, -1L, "loader or code epoch changed before install; plan discarded");
        }

        long generation = generations.getAndIncrement();
        List<BoundPlan> plans = new ArrayList<BoundPlan>(targets.size());
        for (Executable target : targets) {
            plans.add(new BoundPlan(spec, target, generation, loaderBefore, epochBefore));
        }
        CoreRegistration registration = new CoreRegistration(plans, diagnostics);
        List<TargetInstallReceipt> targetReceipts = new ArrayList<TargetInstallReceipt>(plans.size());
        int installed = 0;
        if (adoption != null) {
            // Anchor the new generation before the external operation may commit.
            interruptedInstallPublications.put(id, registration);
        }
        for (BoundPlan plan : plans) {
            try {
                BackendHandle handle = Objects.requireNonNull(adoption == null
                                ? backend.install(plan, registration.channel(plan))
                                : adoption.replace(plan, registration.channel(plan)),
                        "backend returned a null install handle; installation outcome is unknown");
                if (adoption == null) {
                    registration.bind(plan, handle);
                } else {
                    registration.bindAdopted(plan, handle);
                }
                targetReceipts.add(TargetInstallReceipt.installed(plan.target()));
                installed++;
            } catch (BackendInstallException failure) {
                targetReceipts.add(TargetInstallReceipt.failed(plan.target(), failure.getMessage()));
            } catch (RuntimeException failure) {
                if (adoption == null) quarantineInterruptedInstall(id, registration, failure, false);
                else registration.retireUnpublishedAdoption();
                throw failure;
            } catch (Error failure) {
                if (adoption == null) quarantineInterruptedInstall(id, registration, failure, false);
                else registration.retireUnpublishedAdoption();
                throw failure;
            }
        }

        if (adoption != null && installed == 0) {
            // A checked failure is guaranteed uncommitted by the bridge contract.
            interruptedInstallPublications.remove(id);
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.FAILED,
                    backendId, generation, "adoption was not committed; old hook was not changed by this attempt", null, targetReceipts);
        }

        final boolean identityChangedAfterInstall;
        try {
            identityChangedAfterInstall = identityChanged(loaderBefore, epochBefore);
        } catch (RuntimeException failure) {
            if (adoption == null) quarantineInterruptedInstall(id, registration, failure, false);
            else registration.retireUnpublishedAdoption();
            throw failure;
        } catch (Error failure) {
            if (adoption == null) quarantineInterruptedInstall(id, registration, failure, false);
            else registration.retireUnpublishedAdoption();
            throw failure;
        }
        if (identityChangedAfterInstall) {
            if (adoption != null) {
                registration.retireUnpublishedAdoption();
                return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.STALE_IDENTITY,
                        backendId, generation, "identity changed after adoption committed; old hook cannot be restored; "
                                + "new channel retired and handle retained; id quarantined", null, targetReceipts);
            }
            final CloseReport closeReport;
            try {
                closeReport = registration.close();
            } catch (RuntimeException failure) {
                quarantineInterruptedInstall(id, registration, failure, true);
                throw failure;
            } catch (Error failure) {
                quarantineInterruptedInstall(id, registration, failure, true);
                throw failure;
            }
            if (installed > 0 && closeReport.entryRestoration() != CloseReport.EntryRestoration.RESTORED) {
                // Keep ownership of the closed handles and forbid reuse of this id in this environment.
                // close() is idempotent, so this does not imply that uninstall can be retried.
                registrations.put(id, registration);
            }
            List<TargetInstallReceipt> rolledBack = new ArrayList<TargetInstallReceipt>(targetReceipts.size());
            for (TargetInstallReceipt targetReceipt : targetReceipts) {
                rolledBack.add(targetReceipt.status() == TargetInstallReceipt.Status.INSTALLED
                        ? TargetInstallReceipt.rolledBack(targetReceipt.target(),
                                "loader or code epoch changed before publication; aggregate close: " + closeReport)
                        : targetReceipt);
            }
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, generation, "loader or code epoch changed during install; " + closeReport, null, rolledBack);
        }
        if (installed == 0) {
            return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.FAILED,
                    backendId, generation, "no target could be installed", null, targetReceipts);
        }

        try {
            // Establish an independent anchor before building the receipt or touching registrations.
            // It survives both a write-before-throw and a throw-before-write from publication.
            interruptedInstallPublications.put(id, registration);
            RegistrationReceipt.Status status = installed == plans.size()
                    ? RegistrationReceipt.Status.INSTALLED : RegistrationReceipt.Status.PARTIALLY_INSTALLED;
            String message = (adoption == null ? "installed " : "adopted ") + installed + "/" + plans.size() + " targets"
                    + (status == RegistrationReceipt.Status.PARTIALLY_INSTALLED
                            ? "; successful entries remain registered; no cross-target atomicity" : "");
            RegistrationReceipt receipt = RegistrationReceipt.withTargets(id, status, backendId, generation,
                    message, registration, targetReceipts);
            registrations.put(id, registration);
            interruptedInstallPublications.remove(id);
            return receipt;
        } catch (RuntimeException failure) {
            if (adoption == null) quarantineInstallPublication(id, registration, failure);
            else registration.retireUnpublishedAdoption();
            throw failure;
        } catch (Error failure) {
            if (adoption == null) quarantineInstallPublication(id, registration, failure);
            else registration.retireUnpublishedAdoption();
            throw failure;
        }
    }

    private boolean identityChanged(ClassLoader loader, long epoch) {
        return backend.context().classLoader() != loader || backend.context().codeEpoch() != epoch;
    }

    private void quarantineInstallPublication(HookId id, CoreRegistration registration, Throwable failure) {
        // Re-establish the anchor if an asynchronous failure arrived after its successful removal.
        // If anchoring or cleanup itself is interrupted by resource exhaustion, no absolute atomicity
        // is claimed; a fatal cleanup failure has priority over a nonfatal publication failure.
        try {
            interruptedInstallPublications.put(id, registration);
        } catch (Throwable anchorFailure) {
            if (TypeChecks.isFatal(anchorFailure) && !TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(anchorFailure, failure);
                failure = anchorFailure;
            } else {
                suppressPreservingFatal(failure, anchorFailure);
            }
        }
        try {
            CloseReport report = registration.close();
            if (!TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(failure, new IllegalStateException(
                        "install publication failed; bound-handle cleanup: " + report));
            }
        } catch (Throwable cleanupFailure) {
            if (TypeChecks.isFatal(cleanupFailure) && !TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(cleanupFailure, failure);
                throw TypeChecks.fatal(cleanupFailure);
            }
            suppressPreservingFatal(failure, cleanupFailure);
        }
        if (TypeChecks.isFatal(failure)) {
            throw TypeChecks.fatal(failure);
        }
    }

    private void quarantineInterruptedInstall(HookId id, CoreRegistration registration, Throwable failure,
                                               boolean cleanupAlreadyAttempted) {
        // The interrupted call may have installed an entry without returning a handle. Closing only
        // bound handles cannot prove that unknown effects were removed; never turn it into a receipt.
        registrations.put(id, registration);
        interruptedInstalls.add(id);
        if (cleanupAlreadyAttempted) {
            // close() is one-shot. A second call cannot retry an interrupted uninstall or prove
            // restoration, and may return no report. Keep the registration quarantined instead.
            if (!TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(failure, new IllegalStateException(
                        "install rollback was interrupted; backend effects remain unknown; "
                                + "cleanup report: " + registration.closeReport()));
            }
            return;
        }
        try {
            CloseReport cleanup = registration.close();
            if (!TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(failure, new IllegalStateException(
                        "install aborted; unreturned backend handles and their effects are unknown; "
                                + "bound-handle cleanup: " + cleanup));
            }
        } catch (Throwable cleanupFailure) {
            if (TypeChecks.isFatal(cleanupFailure) && !TypeChecks.isFatal(failure)) {
                suppressPreservingFatal(cleanupFailure, failure);
                throw TypeChecks.fatal(cleanupFailure);
            }
            suppressPreservingFatal(failure, cleanupFailure);
        }
    }

    private static void suppressPreservingFatal(Throwable primary, Throwable secondary) {
        if (primary == secondary) {
            return;
        }
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable suppressionFailure) {
            if (!TypeChecks.isFatal(primary) && TypeChecks.isFatal(suppressionFailure)) {
                throw TypeChecks.fatal(suppressionFailure);
            }
            // Preserve a fatal primary even when attaching suppressed diagnostics is not possible.
        }
    }

    private static boolean retirementBlocksReuse(CoreRegistration registration) {
        RegistrationState state = registration.state();
        if (state == RegistrationState.INSTALLED) {
            return false;
        }
        CloseReport report = registration.closeReport();
        return report == null || report.entryRestoration() != CloseReport.EntryRestoration.RESTORED;
    }

    /**
     * Atomically replaces one complete single-target registration with the same logical id.
     * Owner, priority, resolved executable, loader and code epoch must match. MatchPolicy.ALL,
     * partial registrations and implicit close+install are deliberately not supported.
     */
    public synchronized RegistrationReceipt replace(HookSpec spec) {
        Objects.requireNonNull(spec, "spec");
        HookId id = spec.id();
        String backendId = backend.id();
        if (closed) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, -1L, "environment is closed");
        }
        CoreRegistration interruptedPublication = interruptedInstallPublications.get(id);
        if (interruptedPublication != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedPublication.generation(), "install publication is indeterminate; id is quarantined");
        }
        CoreRegistration interruptedReplacement = interruptedReplacements.get(id);
        if (interruptedReplacement != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, interruptedReplacement.generation(), "replacement publication is indeterminate; id is quarantined");
        }
        CapabilityResult replacementCapability = backend.capabilities().lookup(Capability.ATOMIC_REPLACE);
        if (!replacementCapability.isSupportedInDeclaredCell()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "ATOMIC_REPLACE is not supported: " + replacementCapability);
        }
        if (!spec.processFilter().matches(backend.context().processName())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FILTERED,
                    backendId, -1L, "process " + backend.context().processName() + " pruned before replacement");
        }
        for (Capability capability : spec.requiredCapabilities()) {
            CapabilityResult result = backend.capabilities().lookup(capability);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "required " + capability + ": " + result);
            }
        }
        if (spec.errorPolicy() == ErrorPolicy.FRAMEWORK_PROTECTIVE) {
            CapabilityResult result = backend.capabilities().lookup(Capability.FRAMEWORK_PROTECTIVE);
            if (!result.isSupportedInDeclaredCell()) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                        backendId, -1L, "framework protective policy is not supported: " + result);
            }
        }
        if (backend.capabilities().chainSemantics() != ChainSemantics.FRAMEWORK_CHAIN) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                    backendId, -1L, "replacement requires a framework-chain backend");
        }

        CoreRegistration existing = registrations.get(id);
        if (existing == null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, -1L, "no installed registration with this logical id");
        }
        if (existing.replacementOutcomeUnknown()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, existing.generation(), "previous replacement outcome is unknown; id is quarantined");
        }
        if (existing.state() == RegistrationState.RETIRING) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    backendId, existing.generation(), "previous generation is still retiring");
        }
        if (existing.state() != RegistrationState.INSTALLED || !existing.isComplete() || existing.plans().size() != 1) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "only a complete single-target registration can be replaced");
        }
        BoundPlan previous = existing.plan();
        if (previous.spec().target().matchPolicy() == MatchPolicy.ALL || spec.target().matchPolicy() == MatchPolicy.ALL) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "MatchPolicy.ALL is not an atomic single-target replacement");
        }
        if (!previous.spec().owner().equals(spec.owner()) || previous.spec().priority() != spec.priority()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "owner and priority must be preserved by replacement");
        }
        if (previous.spec().errorPolicy() != spec.errorPolicy()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "error policy must be preserved by replacement");
        }
        if (previous.loader() != backend.context().classLoader()
                || previous.codeEpoch() != backend.context().codeEpoch()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, existing.generation(), "loader or code epoch changed before replacement");
        }

        final List<Executable> candidates;
        if (spec.target() instanceof TargetSpec.MemberTarget) {
            candidates = Collections.singletonList(((TargetSpec.MemberTarget) spec.target()).executable());
        } else {
            try {
                candidates = resolver.resolve((TargetSpec.DescriptorTarget) spec.target(), previous.loader());
            } catch (BackendInstallException failure) {
                if (identityChanged(previous.loader(), previous.codeEpoch())) {
                    return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                            backendId, existing.generation(), "loader or code epoch changed during resolution");
                }
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED,
                        backendId, existing.generation(), "target resolution failed: " + failure.getMessage());
            }
        }
        if (identityChanged(previous.loader(), previous.codeEpoch())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, existing.generation(), "loader or code epoch changed during resolution");
        }
        if (candidates.isEmpty()) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.TARGET_MISSING,
                    backendId, existing.generation(), "no target matched " + spec.target());
        }
        if (candidates.size() > 1 && spec.target().matchPolicy() == MatchPolicy.UNIQUE) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.AMBIGUOUS_TARGET,
                    backendId, existing.generation(), candidates.size() + " targets matched under UNIQUE");
        }
        Executable target = candidates.get(0);
        if (!previous.target().equals(target)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.CONFLICT,
                    backendId, existing.generation(), "replacement must keep the resolved executable");
        }
        String invalidConstant = invalidConstantReplacement(spec, Collections.singletonList(target));
        if (spec.stringArgumentCondition() != null && identityChanged(previous.loader(), previous.codeEpoch())) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.STALE_IDENTITY,
                    backendId, existing.generation(), "loader or code epoch changed during conditional validation");
        }
        if (invalidConstant != null) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.FAILED,
                    backendId, existing.generation(), invalidConstant);
        }
        if (previous.spec().hasSameSemantics(spec)) {
            return RegistrationReceipt.of(id, RegistrationReceipt.Status.ALREADY_INSTALLED,
                    backendId, existing.generation(), "identical replacement registration already installed");
        }

        long generation = generations.getAndIncrement();
        BoundPlan plan = new BoundPlan(spec, target, generation, previous.loader(), previous.codeEpoch());
        CoreRegistration registration = new CoreRegistration(plan, diagnostics);
        // Keep direct old.close() serialized through publication, not only through the backend swap.
        synchronized (existing) {
            if (!existing.prepareReplacement(registration)) {
                return RegistrationReceipt.of(id, RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                        backendId, existing.generation(), "previous registration is no longer installed");
            }
            // This guard is recorded before the backend may commit and remains on unknown outcomes.
            interruptedReplacements.put(id, existing);
            try {
                existing.replaceWith(plan, registration);
                RegistrationReceipt receipt = RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.INSTALLED,
                        backendId, generation, "atomically replaced 1 target", registration,
                        Collections.singletonList(TargetInstallReceipt.installed(target)));
                registrations.put(id, registration);
                interruptedReplacements.remove(id);
                existing.finishPreparedReplacement();
                return receipt;
            } catch (BackendInstallException failure) {
                // This checked failure guarantees that the backend did not commit the replacement.
                registration.close();
                existing.finishPreparedReplacement();
                interruptedReplacements.remove(id);
                return RegistrationReceipt.withTargets(id, RegistrationReceipt.Status.FAILED,
                        backendId, existing.generation(), "backend replacement failed: " + failure.getMessage(), null,
                        Collections.singletonList(TargetInstallReceipt.failed(target, failure.getMessage())));
            } catch (RuntimeException failure) {
                existing.retireInterruptedPublication(registration, failure);
                throw failure;
            } catch (Error failure) {
                existing.retireInterruptedPublication(registration, failure);
                throw failure;
            }
        }
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

    private static String invalidConstantReplacement(HookSpec spec, List<Executable> targets) {
        StringArgumentCondition condition = spec.stringArgumentCondition();
        if (!spec.hasConstantReplacement() && !spec.hasSuccessReplacement() && condition == null) return null;
        for (Executable target : targets) {
            if (condition != null) {
                final Class<?>[] parameters;
                try {
                    parameters = target.getParameterTypes();
                } catch (SecurityException | LinkageError failure) {
                    return "cannot inspect conditional replacement parameter types: " + failure;
                }
                int index = condition.argumentIndex();
                if (index >= parameters.length || parameters[index] != String.class) {
                    return "string argument replacement requires a String parameter at index " + index + ": " + target;
                }
            }
            try {
                Object result = condition != null ? condition.result()
                        : spec.hasSuccessReplacement() ? spec.successResult() : spec.constantResult();
                TypeChecks.checkResultValue(target, result);
            } catch (IllegalArgumentException invalid) {
                return "invalid constant replacement: " + invalid.getMessage();
            } catch (SecurityException | LinkageError failure) {
                if (condition == null) throw failure;
                return "cannot inspect conditional replacement return type: " + failure;
            }
        }
        return null;
    }

    public synchronized int activeRegistrationCount() {
        return registrations.size();
    }

    /** Closes every registration; non-fatal failures do not skip other registrations. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<Throwable> failures = new ArrayList<Throwable>();
        List<HookId> restored = new ArrayList<HookId>();
        Error firstError = null;
        for (Map.Entry<HookId, CoreRegistration> entry : registrations.entrySet()) {
            try {
                CloseReport report = entry.getValue().close();
                if (report != null && report.entryRestoration() == CloseReport.EntryRestoration.RESTORED) {
                    restored.add(entry.getKey());
                }
            } catch (RuntimeException failure) {
                failures.add(failure);
            } catch (Error failure) {
                if (TypeChecks.isFatal(failure)) {
                    throw TypeChecks.fatal(failure);
                }
                failures.add(failure);
                if (firstError == null) {
                    firstError = failure;
                }
            }
        }
        for (HookId id : restored) {
            registrations.remove(id);
        }
        if (!failures.isEmpty()) {
            Throwable primary = firstError == null ? failures.get(0) : firstError;
            for (Throwable failure : failures) {
                suppressPreservingFatal(primary, failure);
            }
            // Retain the registrations and handles when a close did not finish cleanly. close()
            // remains one-shot; it does not silently retry an uncertain backend uninstall.
            if (firstError != null) {
                throw firstError;
            }
            throw (RuntimeException) primary;
        }
        if (registrations.isEmpty()) {
            interruptedInstalls.clear();
        }
        // Retain any quarantined replacement handles while this environment remains reachable.
    }
}
