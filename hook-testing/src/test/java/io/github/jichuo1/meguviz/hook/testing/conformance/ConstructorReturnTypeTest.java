package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Constructor descriptors use the JVM void return type, distinct from java.lang.Void. */
public class ConstructorReturnTypeTest {
    @Test
    public void constructorReturnTypeControlsResolutionAndInstallation() throws Exception {
        Executable expected = Fixtures.Constructed.class.getConstructor(int.class);
        String[] returnTypes = {null, "void", "int", "java.lang.Void"};
        for (String returnType : returnTypes) {
            boolean matches = returnType == null || "void".equals(returnType);
            TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(
                            TargetKind.CONSTRUCTOR, Fixtures.Constructed.class.getName(), "<init>")
                    .parameters("int").returning(returnType).build();
            List<Executable> resolved = new ReflectiveMemberResolver().resolve(
                    descriptor, Fixtures.Constructed.class.getClassLoader());
            assertEquals("resolver for " + returnType, matches ? 1 : 0, resolved.size());
            if (matches) {
                assertEquals(expected, resolved.get(0));
            }

            FakeBackend backend = FakeBackend.create();
            HookEnvironment environment = HookEnvironment.create(backend);
            try {
                RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                                HookId.of("constructor.return"), HookOwner.of("conformance"), descriptor)
                        .before(invocation -> { }).build());
                assertEquals("install for " + returnType,
                        matches ? RegistrationReceipt.Status.INSTALLED : RegistrationReceipt.Status.TARGET_MISSING,
                        receipt.status());
                assertEquals(matches ? 1 : 0, backend.installCount());
                if (matches) {
                    assertEquals(expected, receipt.targets().get(0).target());
                } else {
                    assertTrue(receipt.targets().isEmpty());
                }
            } finally {
                environment.close();
            }
        }
    }
}
