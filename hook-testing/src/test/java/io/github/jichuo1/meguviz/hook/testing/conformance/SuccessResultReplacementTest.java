package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;
import org.junit.Test;
import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SuccessResultReplacementTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void originalRunsOnceWithOriginalArgumentsAndExactReplacement() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment env = HookEnvironment.create(backend);
        Integer replacement = Integer.valueOf(93);
        try {
            HookRegistration registration = TestSupport.installOrFail(env,
                    TestSupport.spec("success.normal", ADD).replaceResultOnSuccess(replacement).build());
            assertSame(replacement, backend.call(ADD, null, 3, 4));
            assertEquals(1, backend.originalCallCount(ADD));
            registration.close();
            assertEquals(7, backend.call(ADD, null, 3, 4));
            assertEquals(2, backend.originalCallCount(ADD));
        } finally { env.close(); }
    }

    @SuppressWarnings("removal")
    @Test public void originalFailureAndFatalKeepIdentityWithoutReplay() {
        for (Throwable original : new Throwable[]{new IllegalStateException("original"), new ThreadDeath()}) {
            FakeBackend backend = FakeBackend.create();
            backend.onTarget(ADD, (self, args) -> { throw original; });
            HookEnvironment env = HookEnvironment.create(backend);
            try {
                TestSupport.installOrFail(env, TestSupport.spec("success.failure", ADD).replaceResultOnSuccess(4).build());
                assertSame(original, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                assertEquals(1, backend.originalCallCount(ADD));
            } finally { env.close(); }
        }
    }

    @Test public void exclusiveShapeAndTypesAreCheckedBeforeInstallation() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TestSupport.spec("success.before", ADD)
                .before(i -> { }).replaceResultOnSuccess(1).build());
        assertThrows(IllegalArgumentException.class, () -> TestSupport.spec("success.const", ADD)
                .replaceConstant(1).replaceResultOnSuccess(1).build());
        assertThrows(IllegalArgumentException.class, () -> TestSupport.spec("success.args", ADD)
                .access(ParameterAccess.READ_ARGS).replaceResultOnSuccess(1).build());
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            RegistrationReceipt invalid = env.install(TestSupport.spec("success.type", ADD)
                    .replaceResultOnSuccess("bad").build());
            assertEquals(RegistrationReceipt.Status.FAILED, invalid.status());
            assertEquals(0, backend.installCount());
            Executable constructor = Fixtures.Constructed.class.getConstructor(int.class);
            assertEquals(RegistrationReceipt.Status.FAILED, env.install(TestSupport.spec("success.ctor.bad", constructor)
                    .replaceResultOnSuccess("bad").build()).status());
            assertTrue(env.install(TestSupport.spec("success.ctor.null", constructor)
                    .replaceResultOnSuccess(null).build()).isInstalled());
        } finally { env.close(); }
    }

    @Test public void closeWhileOriginalRunsKeepsLeaseAndOldResult() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        backend.onTarget(ADD, (self, args) -> {
            if ((Integer) args[0] == 1) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
            }
            return 3;
        });
        HookEnvironment env = HookEnvironment.create(backend);
        HookRegistration registration = TestSupport.installOrFail(env,
                TestSupport.spec("success.close", ADD).replaceResultOnSuccess(9).build());
        AtomicReference<Object> result = new AtomicReference<Object>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { result.set(backend.call(ADD, null, 1, 2)); } catch (Throwable t) { failure.set(t); }
        });
        thread.setDaemon(true);
        try {
            thread.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, registration.close().inFlightAtClose());
            assertEquals(3, backend.call(ADD, null, 2, 1));
            release.countDown();
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertNull(failure.get());
            assertEquals(9, result.get());
        } finally { release.countDown(); thread.join(2000); if (!thread.isAlive()) env.close(); }
    }

    @Test public void replacementAcrossOrdinaryAndSuccessShapesKeepsOwnership() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 3);
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            HookRegistration old = TestSupport.installOrFail(env,
                    TestSupport.spec("success.replace", ADD).before(i -> { }).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            RegistrationReceipt next = env.replace(TestSupport.spec("success.replace", ADD)
                    .replaceResultOnSuccess(9).build());
            assertTrue(next.toString(), next.isInstalled());
            old.close();
            assertEquals(9, backend.call(ADD, null, 1, 2));
            RegistrationReceipt third = env.replace(TestSupport.spec("success.replace", ADD)
                    .after((invocation, result) -> result.setResult(8)).build());
            assertTrue(third.toString(), third.isInstalled());
            next.registration().close();
            assertEquals(8, backend.call(ADD, null, 1, 2));
            assertEquals(3, backend.originalCallCount(ADD));
        } finally { env.close(); }
    }
}
