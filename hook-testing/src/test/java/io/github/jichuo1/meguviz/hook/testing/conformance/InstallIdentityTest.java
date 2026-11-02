package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Installation identity checks use backend loader identity and code epoch, not display names. */
public class InstallIdentityTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void epochChangeRejectsOldRegistrationWithoutClosingIt() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("identity.epoch", ADD).before(invocation -> { }).build();
        try {
            HookRegistration old = TestSupport.installOrFail(environment, spec);
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, environment.install(spec).status());
            backend.bumpCodeEpoch();
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, environment.install(spec).status());
            assertTrue(backend.isHooked(ADD));
            assertEquals(1, backend.installCount());
            old.close();
            assertTrue(environment.install(spec).isInstalled());
        } finally {
            environment.close();
        }
    }

    @Test
    public void loaderChangeRejectsSameMemberWithoutClosingOldRegistration() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        HookSpec spec = TestSupport.spec("identity.loader", ADD).before(invocation -> { }).build();
        try {
            HookRegistration old = TestSupport.installOrFail(environment, spec);
            backend.setClassLoader(new ClassLoader(backend.context().classLoader()) { });
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, environment.install(spec).status());
            assertTrue(backend.isHooked(ADD));
            assertEquals(1, backend.installCount());
            old.close();
            assertTrue(environment.install(spec).isInstalled());
        } finally {
            environment.close();
        }
    }

    @Test
    public void identityChangesDuringReresolutionDoNotReturnAlreadyInstalled() {
        verifyResolutionIdentityChange(false);
        verifyResolutionIdentityChange(true);
    }

    private static void verifyResolutionIdentityChange(boolean changeLoader) {
        FakeBackend backend = FakeBackend.create();
        AtomicInteger resolutions = new AtomicInteger();
        HookEnvironment environment = HookEnvironment.create(backend, (descriptor, loader) -> {
            List<Executable> result = new ReflectiveMemberResolver().resolve(descriptor, loader);
            if (resolutions.incrementAndGet() == 2) {
                if (changeLoader) {
                    backend.setClassLoader(new ClassLoader(loader) { });
                } else {
                    backend.bumpCodeEpoch();
                }
            }
            return result;
        }, DiagnosticsSink.NONE);
        HookSpec spec = new HookSpec.Builder(HookId.of("identity.resolve"), HookOwner.of("conformance"),
                TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "add")
                        .parameters("int", "int").build())
                .before(invocation -> { }).build();
        try {
            HookRegistration old = TestSupport.installOrFail(environment, spec);
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, environment.install(spec).status());
            assertTrue(backend.isHooked(ADD));
            assertEquals(1, backend.installCount());
            old.close();
        } finally {
            environment.close();
        }
    }

    @Test
    public void loaderChangeDuringBackendInstallRollsBackUsingOriginalLoader() {
        FakeBackend delegate = FakeBackend.create();
        ClassLoader originalLoader = delegate.context().classLoader();
        HookBackend changing = new HookBackend() {
            @Override public String id() { return delegate.id(); }
            @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
            @Override public BackendContext context() { return delegate.context(); }

            @Override
            public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                assertSame(originalLoader, plan.loader());
                BackendHandle handle = delegate.install(plan, channel);
                delegate.setClassLoader(new ClassLoader(originalLoader) { });
                return handle;
            }
        };
        HookEnvironment environment = HookEnvironment.create(changing);
        try {
            RegistrationReceipt receipt = environment.install(TestSupport.spec("identity.rollback", ADD)
                    .before(invocation -> { }).build());
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, receipt.status());
            assertNull(receipt.registration());
            assertEquals(TargetInstallReceipt.Status.ROLLED_BACK, receipt.targets().get(0).status());
            assertFalse(delegate.isHooked(ADD));
            assertEquals(0, environment.activeRegistrationCount());
        } finally {
            environment.close();
        }
    }
}
