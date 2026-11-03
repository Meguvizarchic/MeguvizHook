package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** Keeps the public construction chain in the README executable against the fake backend. */
public final class RefHookExampleTest {
    public static final class Target {
        public String title(String name) { return name; }
    }

    public static HookEnvironment environment(HookBackend backend) {
        return HookEnvironment.create(backend, new MeguvizRefMemberResolver());
    }

    public static RegistrationReceipt install(HookEnvironment environment, String targetClassName) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, targetClassName, "title")
                .parameters("java.lang.String")
                .returning("java.lang.String")
                .match(MatchPolicy.UNIQUE)
                .build();
        HookSpec spec = HookSpec.builder("feature.title", "my-module", target)
                .replaceConstant("replacement")
                .build();
        return environment.install(spec);
    }

    @Test public void documentedConstructionInstallsAndClosesTheHook() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(Target.class.getClassLoader());
        Method method = Target.class.getDeclaredMethod("title", String.class);
        backend.onTarget(method, (receiver, args) -> args[0]);
        try (HookEnvironment environment = environment(backend)) {
            RegistrationReceipt receipt = install(environment, Target.class.getName());
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipt.status());
            assertNotNull(receipt.registration());
            assertEquals("replacement", backend.call(method, new Target(), "original"));
            assertNotNull(receipt.registration().close());
            assertEquals("original", backend.call(method, new Target(), "original"));
        }
    }

    @Test public void customResolverShortcutRejectsNullAndKeepsFailureSemantics() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        assertThrows(NullPointerException.class, () -> HookEnvironment.create(backend, null));
        assertThrows(NullPointerException.class, () -> HookEnvironment.create(null, new MeguvizRefMemberResolver()));

        backend.setClassLoader(Target.class.getClassLoader());
        Method method = Target.class.getDeclaredMethod("title", String.class);
        RuntimeException original = new RuntimeException("original failure");
        RuntimeException after = new RuntimeException("after failure");
        backend.onTarget(method, (receiver, args) -> { throw original; });
        try (HookEnvironment environment = environment(backend)) {
            TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, Target.class.getName(), "title")
                    .parameters("java.lang.String").returning("java.lang.String").build();
            RegistrationReceipt receipt = environment.install(HookSpec.builder("ref.shortcut.failure", "test", target)
                    .after((invocation, result) -> { throw after; }).build());
            assertTrue(receipt.isInstalled());
            assertSame(original, assertThrows(RuntimeException.class,
                    () -> backend.call(method, new Target(), "original")));
        }
    }
}
