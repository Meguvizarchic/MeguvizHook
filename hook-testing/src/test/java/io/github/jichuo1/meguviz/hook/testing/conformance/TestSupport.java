package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import java.lang.reflect.Executable;

import static org.junit.Assert.assertTrue;

/** Shared fixture helpers. Nothing here computes expected semantics; that is the oracle's job. */
final class TestSupport {
    private TestSupport() {
    }

    static Executable method(String name, Class<?>... parameters) {
        try {
            return Fixtures.class.getMethod(name, parameters);
        } catch (NoSuchMethodException error) {
            throw new IllegalArgumentException(error);
        }
    }

    static HookSpec.Builder spec(String id, Executable target) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("conformance"), TargetSpec.ofMember(target));
    }

    static HookRegistration installOrFail(io.github.jichuo1.meguviz.hook.core.HookEnvironment environment, HookSpec spec) {
        RegistrationReceipt receipt = environment.install(spec);
        assertTrue("expected INSTALLED but got " + receipt, receipt.isInstalled());
        return receipt.registration();
    }

    static Throwable capture(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    interface ThrowingCall {
        void run() throws Throwable;
    }
}
