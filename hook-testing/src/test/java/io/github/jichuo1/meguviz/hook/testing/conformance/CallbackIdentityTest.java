package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.AroundCallback;
import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Equality supplied by callback implementations must not replace instance identity. */
public class CallbackIdentityTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void beforeCallbacksUseInstanceIdentity() throws Throwable {
        EqualBefore first = new EqualBefore(10);
        EqualBefore second = new EqualBefore(20);
        assertTrue(first.equals(second));
        verifyInstallAndReplacement(TestSupport.spec("identity.before", ADD).before(first).build(),
                TestSupport.spec("identity.before", ADD).before(second).build());
    }

    @Test
    public void afterCallbacksUseInstanceIdentity() throws Throwable {
        EqualAfter first = new EqualAfter(10);
        EqualAfter second = new EqualAfter(20);
        assertTrue(first.equals(second));
        verifyInstallAndReplacement(TestSupport.spec("identity.after", ADD).after(first).build(),
                TestSupport.spec("identity.after", ADD).after(second).build());
    }

    @Test
    public void aroundCallbacksUseInstanceIdentity() throws Throwable {
        EqualAround first = new EqualAround(10);
        EqualAround second = new EqualAround(20);
        assertTrue(first.equals(second));
        verifyInstallAndReplacement(TestSupport.spec("identity.around", ADD).around(first).build(),
                TestSupport.spec("identity.around", ADD).around(second).build());
    }

    private static void verifyInstallAndReplacement(HookSpec first, HookSpec second) throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (thisObject, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            TestSupport.installOrFail(environment, first);
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, environment.install(first).status());
            assertEquals(RegistrationReceipt.Status.CONFLICT, environment.install(second).status());
            assertEquals(10, backend.call(ADD, null, 1, 2));
            RegistrationReceipt replaced = environment.replace(second);
            assertTrue(replaced.toString(), replaced.isInstalled());
            assertEquals(1, backend.installCount());
            assertEquals(1, backend.replaceCount());
            assertEquals(20, backend.call(ADD, null, 1, 2));
        } finally {
            environment.close();
        }
    }

    private static final class EqualBefore implements BeforeCallback {
        private final int result;

        private EqualBefore(int result) {
            this.result = result;
        }

        @Override
        public void before(HookInvocation invocation) { invocation.setEarlyResult(result); }

        @Override
        public boolean equals(Object other) { return other instanceof EqualBefore; }

        @Override
        public int hashCode() { return 1; }
    }

    private static final class EqualAfter implements AfterCallback {
        private final int result;

        private EqualAfter(int result) {
            this.result = result;
        }

        @Override
        public void after(HookInvocation invocation, InvocationResult outcome) { outcome.setResult(result); }

        @Override
        public boolean equals(Object other) { return other instanceof EqualAfter; }

        @Override
        public int hashCode() { return 1; }
    }

    private static final class EqualAround implements AroundCallback {
        private final int result;

        private EqualAround(int result) {
            this.result = result;
        }

        @Override
        public void around(AroundInvocation invocation) { invocation.setEarlyResult(result); }

        @Override
        public boolean equals(Object other) { return other instanceof EqualAround; }

        @Override
        public int hashCode() { return 1; }
    }
}
