package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Core policy-gate tests only; the fake does not simulate a real framework's protection. */
public final class FrameworkProtectiveGateTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    private static HookSpec spec(String id, ErrorPolicy policy, int value) {
        return TestSupport.spec(id, ADD).errorPolicy(policy)
                .before(invocation -> invocation.setEarlyResult(value)).build();
    }

    private static CapabilityResult supported() {
        return CapabilityResult.of(CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL,
                "fake-chain", "test", "jvm-fixture", "deliberate test declaration");
    }

    private static FakeBackend backendWithAnswer(int answer) {
        FakeBackend backend = FakeBackend.create();
        if (answer == 1) {
            backend.withCapability(Capability.FRAMEWORK_PROTECTIVE,
                    CapabilityResult.unknown(backend.id(), "not probed"));
        } else if (answer == 2) {
            backend.withCapability(Capability.FRAMEWORK_PROTECTIVE,
                    CapabilityResult.unsupported(backend.id(), "not implemented"));
        }
        return backend;
    }

    /** Counts SPI attempts even when the delegate refuses before its own install counter. */
    private static final class CountingBackend implements HookBackend {
        private final FakeBackend delegate;
        private final AtomicInteger installs = new AtomicInteger();
        private final AtomicInteger replacements = new AtomicInteger();

        private CountingBackend(FakeBackend delegate) { this.delegate = delegate; }
        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            installs.incrementAndGet();
            return wrap(delegate.install(plan, channel));
        }
        private BackendHandle wrap(BackendHandle handle) {
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() { return handle.uninstall(); }
                @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                    replacements.incrementAndGet();
                    return wrap(handle.replace(plan, channel));
                }
            };
        }
    }

    @Test public void missingUnknownAndUnsupportedRefuseInstallAndAdoptionBeforeBackend() {
        for (int answer = 0; answer < 3; answer++) {
            FakeBackend backend = backendWithAnswer(answer);
            CountingBackend counted = new CountingBackend(backend);
            HookEnvironment environment = HookEnvironment.create(counted);
            AtomicInteger adoptionCalls = new AtomicInteger();
            BackendAdoption adoption = new BackendAdoption() {
                @Override public HookBackend backend() { return counted; }
                @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) {
                    adoptionCalls.incrementAndGet();
                    throw new AssertionError("policy gate must reject before adoption");
                }
            };
            try {
                HookSpec requested = spec("protect.install." + answer, ErrorPolicy.FRAMEWORK_PROTECTIVE, 42);
                assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, environment.install(requested).status());
                assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, environment.adopt(requested, adoption).status());
                assertEquals(0, backend.installCount());
                assertEquals(0, counted.installs.get());
                assertEquals(0, adoptionCalls.get());
                assertEquals(0, environment.activeRegistrationCount());
            } finally {
                environment.close();
            }
        }
    }

    @Test public void unavailableProtectiveCapabilityRefusesReplacementAndLeavesOldHookActive() throws Throwable {
        for (int answer = 0; answer < 3; answer++) {
            FakeBackend backend = backendWithAnswer(answer);
            backend.onTarget(ADD, (self, arguments) -> (Integer) arguments[0] + (Integer) arguments[1]);
            CountingBackend counted = new CountingBackend(backend);
            HookEnvironment environment = HookEnvironment.create(counted);
            try {
                String id = "protect.replace.unavailable." + answer;
                HookRegistration old = TestSupport.installOrFail(environment, spec(id, ErrorPolicy.PROPAGATE, 42));
                RegistrationReceipt refused = environment.replace(spec(id, ErrorPolicy.FRAMEWORK_PROTECTIVE, 99));
                assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, refused.status());
                assertEquals(0, backend.replaceCount());
                assertEquals(0, counted.replacements.get());
                assertEquals(42, backend.call(ADD, null, 1, 2));
                assertEquals(0, backend.originalCallCount(ADD));
                old.close();
                assertEquals(3, backend.call(ADD, null, 1, 2));
            } finally {
                environment.close();
            }
        }
    }

    @Test public void modeChangeIsConflictBeforeReplacementEvenWhenCapabilityAdvertised() throws Throwable {
        FakeBackend backend = FakeBackend.create().withCapability(Capability.FRAMEWORK_PROTECTIVE, supported());
        backend.onTarget(ADD, (self, arguments) -> (Integer) arguments[0] + (Integer) arguments[1]);
        CountingBackend counted = new CountingBackend(backend);
        HookEnvironment environment = HookEnvironment.create(counted);
        try {
            HookRegistration old = TestSupport.installOrFail(environment,
                    spec("protect.replace.conflict", ErrorPolicy.PROPAGATE, 42));
            RegistrationReceipt refused = environment.replace(
                    spec("protect.replace.conflict", ErrorPolicy.FRAMEWORK_PROTECTIVE, 99));
            assertEquals(RegistrationReceipt.Status.CONFLICT, refused.status());
            assertEquals(0, backend.replaceCount());
            assertEquals(0, counted.replacements.get());
            assertEquals(42, backend.call(ADD, null, 1, 2));
            old.close();
            assertEquals(3, backend.call(ADD, null, 1, 2));
        } finally {
            environment.close();
        }
    }

    @Test public void fakeBackendFailsClosedEvenIfCapabilityIsIncorrectlyAdvertised() {
        FakeBackend backend = FakeBackend.create().withCapability(Capability.FRAMEWORK_PROTECTIVE, supported());
        CountingBackend counted = new CountingBackend(backend);
        HookEnvironment environment = HookEnvironment.create(counted);
        try {
            RegistrationReceipt refused = environment.install(
                    spec("protect.fake.reject", ErrorPolicy.FRAMEWORK_PROTECTIVE, 42));
            assertEquals(RegistrationReceipt.Status.FAILED, refused.status());
            assertFalse(backend.isHooked(ADD));
            assertEquals(0, backend.installCount());
            assertEquals(1, counted.installs.get());
            assertEquals(0, environment.activeRegistrationCount());
        } finally {
            environment.close();
        }
    }
}
