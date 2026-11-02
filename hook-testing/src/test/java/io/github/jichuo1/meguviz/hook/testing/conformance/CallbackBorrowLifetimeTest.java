package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.AroundInvocation;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Every callback gets its own borrowed view while the invocation state remains shared. */
public class CallbackBorrowLifetimeTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void beforeAndAfterViewsExpireIndependentlyWhileStateAndOrderArePreserved() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<HookInvocation> firstBefore = new AtomicReference<HookInvocation>();
        AtomicReference<HookInvocation> firstAfter = new AtomicReference<HookInvocation>();
        AtomicReference<InvocationResult> firstResult = new AtomicReference<InvocationResult>();
        List<String> order = new ArrayList<String>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("borrow.stages", ADD)
                    .access(ParameterAccess.MUTABLE_ARGS)
                    .before(invocation -> {
                        order.add("before-1");
                        firstBefore.set(invocation);
                        ((MutableArgsInvocation) invocation).setArgument(0, 4);
                    })
                    .before(invocation -> {
                        order.add("before-2");
                        assertNotSame(firstBefore.get(), invocation);
                        assertExpired(() -> firstBefore.get().id());
                        assertExpired(() -> firstBefore.get().access());
                        assertExpired(() -> firstBefore.get().ownerThread());
                        assertExpired(() -> ((MutableArgsInvocation) firstBefore.get()).setArgument(0, 99));
                        assertEquals(4, ((ReadArgsInvocation) invocation).getArgument(0));
                        assertSame(Thread.currentThread(), invocation.ownerThread());
                        assertEquals(ParameterAccess.MUTABLE_ARGS, invocation.access());
                    })
                    .after((invocation, result) -> {
                        order.add("after-1");
                        assertExpired(() -> firstBefore.get().id());
                        assertNotSame(firstAfter.get(), invocation);
                        assertNotSame(firstResult.get(), result);
                        assertExpired(() -> firstAfter.get().access());
                        assertExpired(() -> firstAfter.get().ownerThread());
                        assertExpired(() -> firstResult.get().hasReturned());
                        assertExpired(() -> firstResult.get().getResult());
                        assertExpired(() -> firstResult.get().setResult(99));
                        assertEquals(30, result.getResult());
                        result.setResult(31);
                    })
                    .after((invocation, result) -> {
                        order.add("after-2");
                        firstAfter.set(invocation);
                        firstResult.set(result);
                        assertEquals(6, result.getResult());
                        result.setResult(30);
                    })
                    .build());
            assertEquals(31, backend.call(ADD, null, 1, 2));
            assertEquals(1, backend.originalCallCount(ADD));
            assertEquals(Arrays.asList("before-1", "before-2", "after-2", "after-1"), order);
            assertExpired(() -> firstAfter.get().target());
            assertExpired(() -> firstResult.get().getResult());
        } finally {
            environment.close();
        }
    }

    @Test
    public void expiredBeforeViewStaysExpiredDuringReentryWhileActiveOuterViewRemainsValid() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<HookInvocation> expiredOuter = new AtomicReference<HookInvocation>();
        AtomicReference<HookInvocation> activeOuter = new AtomicReference<HookInvocation>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("borrow.reentry", ADD)
                    .access(ParameterAccess.READ_ARGS)
                    .before(invocation -> {
                        int depth = (Integer) ((ReadArgsInvocation) invocation).getArgument(0);
                        if (depth == 1) {
                            expiredOuter.set(invocation);
                        } else {
                            assertExpired(() -> expiredOuter.get().id());
                            assertEquals(ParameterAccess.READ_ARGS, activeOuter.get().access());
                        }
                    })
                    .before(invocation -> {
                        int depth = (Integer) ((ReadArgsInvocation) invocation).getArgument(0);
                        assertExpired(() -> expiredOuter.get().ownerThread());
                        if (depth == 1) {
                            activeOuter.set(invocation);
                            assertEquals(2, backend.call(ADD, null, 0, 2));
                            assertEquals(1, ((ReadArgsInvocation) invocation).getArgument(0));
                        }
                    })
                    .build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(2, backend.originalCallCount(ADD));
            assertExpired(() -> activeOuter.get().access());
        } finally {
            environment.close();
        }
    }

    @Test
    public void nestedAroundViewsHaveIndependentLifetimesAndProceedState() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> (Integer) args[0] == 0
                ? 0 : (Integer) backend.call(ADD, null, 0, 0) + 1);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicReference<AroundInvocation> outer = new AtomicReference<AroundInvocation>();
        AtomicReference<AroundInvocation> inner = new AtomicReference<AroundInvocation>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("borrow.around", ADD)
                    .around(invocation -> {
                        boolean nested = (Integer) invocation.getArgument(0) == 0;
                        if (nested) {
                            inner.set(invocation);
                            assertEquals(CallOutcome.State.RUNNING, outer.get().proceedState());
                        } else {
                            outer.set(invocation);
                        }
                        assertEquals(CallOutcome.State.NOT_CALLED, invocation.proceedState());
                        invocation.proceed();
                        assertEquals(CallOutcome.State.RETURNED, invocation.proceedState());
                        if (!nested) {
                            assertExpired(() -> inner.get().proceedState());
                            assertExpired(() -> inner.get().proceed());
                            assertSame(Thread.currentThread(), invocation.ownerThread());
                        }
                    })
                    .build());
            assertEquals(1, backend.call(ADD, null, 1, 0));
            assertEquals(2, backend.originalCallCount(ADD));
            assertExpired(() -> outer.get().access());
            assertExpired(() -> outer.get().ownerThread());
            assertExpired(() -> outer.get().proceed());
        } finally {
            environment.close();
        }
    }

    @Test
    public void accessOwnerThreadAndAfterResultRejectCrossThreadUseDuringCallback() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("borrow.thread", ADD)
                    .after((invocation, result) -> {
                        AtomicReference<Throwable> accessFailure = new AtomicReference<Throwable>();
                        AtomicReference<Throwable> ownerFailure = new AtomicReference<Throwable>();
                        AtomicReference<Throwable> resultFailure = new AtomicReference<Throwable>();
                        Thread other = new Thread(() -> {
                            accessFailure.set(TestSupport.capture(() -> invocation.access()));
                            ownerFailure.set(TestSupport.capture(() -> invocation.ownerThread()));
                            resultFailure.set(TestSupport.capture(() -> result.getResult()));
                        });
                        other.start();
                        other.join();
                        assertTrue(accessFailure.get() instanceof HookContractException);
                        assertTrue(ownerFailure.get() instanceof HookContractException);
                        assertTrue(resultFailure.get() instanceof HookContractException);
                        assertEquals(3, result.getResult());
                    }).build());
            assertEquals(3, backend.call(ADD, null, 1, 2));
        } finally {
            environment.close();
        }
    }

    private static void assertExpired(TestSupport.ThrowingCall call) {
        Throwable failure = TestSupport.capture(call);
        assertTrue("expected HookContractException but got " + failure, failure instanceof HookContractException);
    }
}
