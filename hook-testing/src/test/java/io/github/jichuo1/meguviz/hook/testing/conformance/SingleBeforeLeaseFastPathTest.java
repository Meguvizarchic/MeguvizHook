package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Consumer-visible lifetime and continuation contract for the one-before registration shape. */
public class SingleBeforeLeaseFastPathTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void leaseExpiresBeforeOriginalAndNestedCallsGetSeparateFrames() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<HookInvocation> outer = new AtomicReference<HookInvocation>();
        AtomicReference<HookInvocation> inner = new AtomicReference<HookInvocation>();
        backend.onTarget(ADD, (self, args) -> {
            if ((Integer) args[0] == 1) {
                assertThrows(HookContractException.class, () -> outer.get().target());
                assertEquals(2, backend.call(ADD, null, 0, 2));
                assertThrows(HookContractException.class, () -> inner.get().target());
            }
            return (Integer) args[0] + (Integer) args[1];
        });
        try {
            TestSupport.installOrFail(env, TestSupport.spec("single.before.lease", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW)
                    .before(invocation -> {
                        ReadArgsInvocation args = (ReadArgsInvocation) invocation;
                        assertSame(Thread.currentThread(), invocation.ownerThread());
                        if ((Integer) args.getArgument(0) == 1) outer.set(invocation);
                        else {
                            inner.set(invocation);
                            assertNotSame(outer.get(), invocation);
                            assertThrows(HookContractException.class, () -> outer.get().ownerThread());
                        }
                    }).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(2, backend.originalCallCount(ADD));
            assertThrows(HookContractException.class, () -> outer.get().access());
            assertThrows(HookContractException.class, () -> inner.get().access());
        } finally { env.close(); }
    }

    @Test public void mutableArgumentsAndEarlyResultPreserveOriginalCounts() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        try {
            TestSupport.installOrFail(env, TestSupport.spec("single.before.mutable", ADD)
                    .access(ParameterAccess.MUTABLE_ARGS).before(invocation -> {
                        MutableArgsInvocation args = (MutableArgsInvocation) invocation;
                        if ((Integer) args.getArgument(0) == 0) invocation.setEarlyResult(42);
                        else args.setArgument(0, 5);
                    }).build());
            assertEquals(7, backend.call(ADD, null, 1, 2));
            assertEquals(42, backend.call(ADD, null, 0, 2));
            assertEquals(1, backend.originalCallCount(ADD));
        } finally { env.close(); }
    }

    @Test public void beforeFailureAndCaughtFatalNeverRunOriginal() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        RuntimeException ordinary = new RuntimeException("before");
        ThreadDeath fatal = new ThreadDeath();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("single.before.errors", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW).before(invocation -> {
                        if ((Integer) ((ReadArgsInvocation) invocation).getArgument(0) == 0) throw ordinary;
                        try { invocation.setEarlyThrowable(fatal); } catch (ThreadDeath deliberatelyCaught) { }
                    }).build());
            assertSame(ordinary, TestSupport.capture(() -> backend.call(ADD, null, 0, 0)));
            assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 0)));
            assertEquals(0, backend.originalCallCount(ADD));
        } finally { env.close(); }
    }
}
