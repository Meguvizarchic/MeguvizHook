package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.InvocationArguments;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class InvocationArgumentsTest {
    private static final Executable APPEND = TestSupport.method("append", StringBuilder.class, String.class);

    @Test
    public void aroundCopiesSlotsAtCallTimeAndRetainsSharedObjects() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        StringBuilder shared = new StringBuilder("entry");
        AtomicReference<Object[]> copied = new AtomicReference<Object[]>();
        backend.onTarget(APPEND, (receiver, args) -> {
            args[1] = "downstream";
            ((StringBuilder) args[0]).append("-changed");
            return null;
        });
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("arguments.copy", APPEND)
                    .around(invocation -> {
                        copied.set(InvocationArguments.copyOf(invocation));
                        invocation.setArgument(1, "written");
                        invocation.proceed();
                        assertEquals("downstream", invocation.getArgument(1));
                        assertEquals("before", copied.get()[1]);
                        copied.get()[1] = "independent";
                        assertEquals("downstream", invocation.getArgument(1));
                    }).build());
            backend.call(APPEND, null, shared, "before");
            assertSame(shared, copied.get()[0]);
            assertEquals("entry-changed", copied.get()[0].toString());
            assertEquals("independent", copied.get()[1]);
        } finally {
            environment.close();
        }
    }

    @Test
    public void copyChecksThreadAndLifetimeEvenForZeroArguments() throws Throwable {
        Executable noArguments = TestSupport.method("noArguments");
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<AroundInvocation> borrowed = new AtomicReference<AroundInvocation>();
        AtomicReference<Object[]> copied = new AtomicReference<Object[]>();
        AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
        backend.onTarget(noArguments, (receiver, args) -> 42L);
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("arguments.lifetime", noArguments)
                    .around(invocation -> {
                        borrowed.set(invocation);
                        copied.set(InvocationArguments.copyOf(invocation));
                        Thread other = new Thread(() -> wrongThread.set(
                                TestSupport.capture(() -> InvocationArguments.copyOf(invocation))));
                        other.start();
                        other.join();
                        invocation.proceed();
                    }).build());
            backend.call(noArguments, null);
            assertEquals(0, copied.get().length);
            assertTrue(wrongThread.get() instanceof HookContractException);
            assertTrue(TestSupport.capture(() -> InvocationArguments.copyOf(borrowed.get()))
                    instanceof HookContractException);
        } finally {
            environment.close();
        }
    }
}
