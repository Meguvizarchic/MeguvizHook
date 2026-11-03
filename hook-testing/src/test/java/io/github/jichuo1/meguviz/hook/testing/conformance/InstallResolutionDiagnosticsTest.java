package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Resolution diagnostics keep the original failure without changing the installation receipt. */
public final class InstallResolutionDiagnosticsTest {
    private static HookSpec descriptor(String id, String method) {
        return HookSpec.builder(id, "resolution-test",
                TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), method).build())
                .before(invocation -> { }).build();
    }

    @Test public void checkedFailureKeepsIdentityCauseAndSuppressed() {
        FakeBackend backend = FakeBackend.create();
        Throwable cause = new IllegalArgumentException("cause");
        Throwable suppressed = new IllegalStateException("close failure");
        BackendInstallException failure = new BackendInstallException("resolve failed", cause);
        failure.addSuppressed(suppressed);
        HookEnvironment env = HookEnvironment.create(backend, (target, loader) -> { throw failure; }, DiagnosticsSink.NONE);
        try {
            HookEnvironment.InstallResolutionResult result = env.installWithResolutionDiagnostics(descriptor("diagnostic", "missing"));
            assertEquals(RegistrationReceipt.Status.FAILED, result.receipt().status());
            assertSame(failure, result.resolutionFailure());
            assertSame(cause, result.resolutionFailure().getCause());
            assertArrayEquals(new Throwable[] { suppressed }, result.resolutionFailure().getSuppressed());
            assertEquals(0, backend.installCount());
        } finally { env.close(); }
    }

    @Test public void nestedDifferentIdsKeepIndependentFailures() {
        FakeBackend backend = FakeBackend.create();
        BackendInstallException outerFailure = new BackendInstallException("outer");
        BackendInstallException innerFailure = new BackendInstallException("inner");
        HookEnvironment[] environment = new HookEnvironment[1];
        AtomicReference<HookEnvironment.InstallResolutionResult> nested = new AtomicReference<>();
        environment[0] = HookEnvironment.create(backend, (target, loader) -> {
            if ("outer".equals(target.memberName())) {
                nested.set(environment[0].installWithResolutionDiagnostics(descriptor("inner-id", "inner")));
                throw outerFailure;
            }
            throw innerFailure;
        }, DiagnosticsSink.NONE);
        try {
            HookEnvironment.InstallResolutionResult result = environment[0]
                    .installWithResolutionDiagnostics(descriptor("outer-id", "outer"));
            assertEquals(RegistrationReceipt.Status.FAILED, result.receipt().status());
            assertSame(outerFailure, result.resolutionFailure());
            assertNotNull(nested.get());
            assertEquals(RegistrationReceipt.Status.FAILED, nested.get().receipt().status());
            assertSame(innerFailure, nested.get().resolutionFailure());
            assertEquals(0, backend.installCount());
        } finally { environment[0].close(); }
    }

    @Test public void staleIdentityRetainsResolutionFailure() {
        FakeBackend backend = FakeBackend.create();
        BackendInstallException failure = new BackendInstallException("resolution during epoch change");
        HookEnvironment env = HookEnvironment.create(backend, (target, loader) -> {
            backend.bumpCodeEpoch();
            throw failure;
        }, DiagnosticsSink.NONE);
        try {
            HookEnvironment.InstallResolutionResult result = env.installWithResolutionDiagnostics(descriptor("stale", "missing"));
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, result.receipt().status());
            assertSame(failure, result.resolutionFailure());
            assertEquals(0, backend.installCount());
        } finally { env.close(); }
    }

    @Test public void directMemberSucceedsWithoutResolutionFailure() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend, (target, loader) -> {
            throw new AssertionError("direct member must not invoke resolver");
        }, DiagnosticsSink.NONE);
        try {
            HookSpec spec = TestSupport.spec("member", TestSupport.method("add", int.class, int.class))
                    .before(invocation -> { }).build();
            HookEnvironment.InstallResolutionResult result = env.installWithResolutionDiagnostics(spec);
            assertEquals(RegistrationReceipt.Status.INSTALLED, result.receipt().status());
            assertNull(result.resolutionFailure());
            assertNotNull(result.receipt().registration());
        } finally { env.close(); }
    }

    @Test public void fatalResolutionFailurePropagatesUnchanged() {
        FakeBackend backend = FakeBackend.create();
        SyntheticFatal fatal = new SyntheticFatal();
        HookEnvironment env = HookEnvironment.create(backend, (target, loader) -> { throw fatal; }, DiagnosticsSink.NONE);
        try {
            assertSame(fatal, assertThrows(SyntheticFatal.class,
                    () -> env.installWithResolutionDiagnostics(descriptor("fatal", "missing"))));
            assertEquals(0, backend.installCount());
        } finally { env.close(); }
    }

    private static final class SyntheticFatal extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
