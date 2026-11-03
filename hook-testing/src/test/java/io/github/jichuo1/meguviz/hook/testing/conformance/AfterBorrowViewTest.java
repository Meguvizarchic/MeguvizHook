package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * In multi-callback plans one lease per after callback serves as both its invocation and result
 * view. Before, around and frame-backed views never implement InvocationResult, so no cast widens
 * a shape, and every view still expires when its own callback returns.
 */
public final class AfterBorrowViewTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void afterLeaseIsAlsoItsResultViewAndExpiresWithIt() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment env = HookEnvironment.create(backend);
        List<Object> afterViews = new ArrayList<Object>();
        AtomicReference<HookInvocation> before = new AtomicReference<HookInvocation>();
        AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("after.borrow.merged", ADD)
                    .before(invocation -> {
                        before.set(invocation);
                        assertFalse("before views never expose the result slot", invocation instanceof InvocationResult);
                    })
                    .after((invocation, result) -> {
                        assertSame(invocation, result);
                        afterViews.add(result);
                        assertEquals(7, result.getResult());
                        result.setResult(8);
                    })
                    .after((invocation, result) -> {
                        assertSame(invocation, result);
                        assertTrue("earlier view of the same invocation expired",
                                TestSupport.capture(before.get()::id) instanceof HookContractException);
                        Thread other = new Thread(() -> wrongThread.set(TestSupport.capture(result::getResult)));
                        other.start();
                        other.join(5000L);
                        assertFalse(other.isAlive());
                        afterViews.add(result);
                        assertEquals(7, result.getResult());
                    }).build());
            assertEquals(8, backend.call(ADD, null, 3, 4));
            assertEquals(2, afterViews.size());
            assertNotSame("each after callback gets its own lease", afterViews.get(0), afterViews.get(1));
            assertTrue(wrongThread.get() instanceof HookContractException);
            for (Object view : afterViews) {
                assertTrue(TestSupport.capture(((InvocationResult) view)::getResult) instanceof HookContractException);
                assertTrue(TestSupport.capture(((HookInvocation) view)::id) instanceof HookContractException);
            }
        } finally {
            env.close();
        }
    }

    @Test public void mergedViewKeepsTheRegisteredShape() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 7);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<Throwable> argumentRead = new AtomicReference<Throwable>();
        AtomicReference<Throwable> earlyResult = new AtomicReference<Throwable>();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("after.borrow.shape", ADD)
                    .before(invocation -> { })
                    .after((invocation, result) -> {
                        argumentRead.set(TestSupport.capture(() -> ((ReadArgsInvocation) invocation).getArgument(0)));
                        earlyResult.set(TestSupport.capture(() -> ((InvocationResult) invocation).setResult("not an int")));
                    }).build());
            assertEquals(7, backend.call(ADD, null, 3, 4));
            assertTrue("NO_ARGS stays NO_ARGS through the merged view", argumentRead.get() instanceof HookContractException);
            assertTrue("result writes stay type-checked", earlyResult.get() instanceof IllegalArgumentException);
        } finally {
            env.close();
        }
    }

    @Test public void frameBackedViewsNeverImplementTheResultView() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 7);
        HookEnvironment env = HookEnvironment.create(backend);
        List<Boolean> resultViews = new ArrayList<Boolean>();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("after.borrow.single.before", ADD).priority(30)
                    .access(ParameterAccess.READ_ARGS)
                    .before(invocation -> resultViews.add(invocation instanceof InvocationResult)).build());
            TestSupport.installOrFail(env, TestSupport.spec("after.borrow.around", ADD).priority(20)
                    .around(invocation -> {
                        resultViews.add(invocation instanceof InvocationResult);
                        invocation.proceed();
                    }).build());
            TestSupport.installOrFail(env, TestSupport.spec("after.borrow.single.after", ADD).priority(10)
                    .after((invocation, result) -> {
                        assertNotSame(invocation, result);
                        resultViews.add(invocation instanceof InvocationResult);
                    }).build());
            assertEquals(7, backend.call(ADD, null, 3, 4));
            assertEquals(3, resultViews.size());
            assertFalse(resultViews.contains(Boolean.TRUE));
        } finally {
            env.close();
        }
    }
}
