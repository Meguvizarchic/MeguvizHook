package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Resolution must report unavailable signature types without installing a backend entry. */
public class MissingSignatureDependencyTest {
    public static final class MissingDependency { }

    public static final class MethodTarget {
        public void run(MissingDependency dependency) { }
    }

    public static final class ConstructorTarget {
        public ConstructorTarget(MissingDependency dependency) { }
    }

    @Test
    public void missingMethodSignatureDependencyIsAResolutionFailure() throws Exception {
        verifyMissingSignature(MethodTarget.class, TargetKind.METHOD, "run");
    }

    @Test
    public void missingConstructorSignatureDependencyIsAResolutionFailure() throws Exception {
        verifyMissingSignature(ConstructorTarget.class, TargetKind.CONSTRUCTOR, "<init>");
    }

    private static void verifyMissingSignature(Class<?> target, TargetKind kind, String member) throws Exception {
        TargetSpec.DescriptorTarget descriptor = TargetSpec.descriptor(kind, target.getName(), member).build();
        ClassLoader loader = new HidingLoader(target);
        assertEquals("target itself loads before signature inspection", loader,
                Class.forName(target.getName(), false, loader).getClassLoader());

        try {
            new ReflectiveMemberResolver().resolve(descriptor, loader);
            fail("signature inspection should fail");
        } catch (BackendInstallException expected) {
            assertTrue(expected.getMessage().contains("cannot inspect " + target.getName()));
            assertTrue(expected.getCause() instanceof NoClassDefFoundError);
        }

        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(loader);
        HookEnvironment environment = HookEnvironment.create(backend);
        RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                HookId.of("signature.missing"), HookOwner.of("conformance"), descriptor)
                .before(invocation -> { }).build());
        assertEquals(RegistrationReceipt.Status.FAILED, receipt.status());
        assertTrue(receipt.message().contains("cannot inspect " + target.getName()));
        assertNull(receipt.registration());
        assertTrue(receipt.targets().isEmpty());
        assertEquals(0, backend.installCount());
    }

    private static final class HidingLoader extends ClassLoader {
        private final String targetName;
        private final byte[] targetBytes;

        HidingLoader(Class<?> target) throws IOException {
            super(target.getClassLoader());
            targetName = target.getName();
            String resource = targetName.replace('.', '/') + ".class";
            InputStream stream = target.getClassLoader().getResourceAsStream(resource);
            if (stream == null) throw new IOException("missing " + resource);
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                targetBytes = output.toByteArray();
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(MissingDependency.class.getName())) throw new ClassNotFoundException(name);
            if (name.equals(targetName)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = defineClass(name, targetBytes, 0, targetBytes.length);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }
}
