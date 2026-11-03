package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HookSpecFactoryTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void descriptorFactoryPreservesBuilderIdentityAndOptions() {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, "example.Target", "work")
                .parameters("int").returning("int").build();
        BeforeCallback callback = invocation -> invocation.setEarlyResult(3);
        HookSpec old = new HookSpec.Builder(HookId.of("factory.target"), HookOwner.of("owner"), target)
                .before(callback).priority(70).build();
        HookSpec concise = HookSpec.builder("factory.target", "owner", target)
                .before(callback).priority(70).build();
        assertSame(target, concise.target());
        assertSame(callback, concise.beforeCallbacks().get(0));
        assertEquals(old.id(), concise.id());
        assertEquals(old.owner(), concise.owner());
        assertTrue(old.hasSameSemantics(concise));
    }

    @Test public void memberFactorySupportsMethodsAndConstructorsWithoutChangingExecution() throws Throwable {
        Constructor<StringBuilder> constructor = StringBuilder.class.getConstructor();
        HookSpec constructorSpec = HookSpec.forMember("factory.constructor", "owner", constructor)
                .before(invocation -> { }).build();
        assertSame(constructor, ((TargetSpec.MemberTarget) constructorSpec.target()).executable());
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        try (HookEnvironment environment = HookEnvironment.create(backend)) {
            RegistrationReceipt receipt = environment.install(HookSpec.forMember("factory.constant", "owner", ADD)
                    .replaceConstant(42).build());
            assertTrue(receipt.isInstalled());
            assertEquals(42, backend.call(ADD, null, 1, 2));
            assertEquals(0, backend.originalCallCount(ADD));
            assertEquals(0, backend.argumentMaterializationCount());
            assertTrue(receipt.registration().close().newCallbacksRejected());
            assertEquals(3, backend.call(ADD, null, 1, 2));
        }
    }

    @Test public void factoryPreservesExistingValidation() {
        TargetSpec target = TargetSpec.ofMember(ADD);
        assertThrows(NullPointerException.class, () -> HookSpec.builder(null, "owner", target));
        assertThrows(IllegalArgumentException.class, () -> HookSpec.builder("bad id", "owner", target));
        assertThrows(IllegalArgumentException.class, () -> HookSpec.builder("valid.id", "  ", target));
        assertThrows(NullPointerException.class, () -> HookSpec.builder("valid.id", "owner", null));
        assertThrows(NullPointerException.class, () -> HookSpec.forMember("valid.id", "owner", null));
        assertThrows(IllegalArgumentException.class, () -> HookSpec.forMember("valid.id", "owner", ADD).build());
    }
}
