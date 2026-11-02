package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;

import static org.junit.Assert.*;

/** Unknown protective support must fail closed; the fake does not model a real framework. */
public final class ProtectiveCapabilityTest {
    @Test public void unknownCapabilityRejectsBeforeAnyFakeInstallation() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            Executable target = TestSupport.method("add", int.class, int.class);
            HookSpec spec = new HookSpec.Builder(HookId.of("protect.unknown"), HookOwner.of("test"),
                    TargetSpec.ofMember(target)).errorPolicy(ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .before(invocation -> { }).build();
            assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, env.install(spec).status());
            assertEquals(0, backend.installCount());
            assertFalse(backend.isHooked(target));
        } finally { env.close(); }
    }

    @Test public void fakeStillRefusesWhenTestDeclaresCapabilityWithoutImplementingIt() {
        FakeBackend backend = FakeBackend.create().withCapability(Capability.FRAMEWORK_PROTECTIVE,
                CapabilityResult.of(CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL,
                        "fake-chain", "test", "jvm-fake", "deliberately incorrect declaration"));
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            Executable target = TestSupport.method("add", int.class, int.class);
            HookSpec spec = new HookSpec.Builder(HookId.of("protect.forged"), HookOwner.of("test"),
                    TargetSpec.ofMember(target)).errorPolicy(ErrorPolicy.FRAMEWORK_PROTECTIVE)
                    .before(invocation -> { }).build();
            assertEquals(RegistrationReceipt.Status.FAILED, env.install(spec).status());
            assertEquals(0, backend.installCount());
            assertFalse(backend.isHooked(target));
        } finally { env.close(); }
    }
}
