package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class CallbackExceptionBatchTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void failedBatchStaysInOrderAndValidPrefixAloneIsRemoved() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch", 8);
        RuntimeException[] failures = { new RuntimeException("one"), new RuntimeException("two"),
                new RuntimeException("three") };
        AtomicInteger next = new AtomicInteger();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.batch", ADD)
                    .before(tracker.before(invocation -> { throw failures[next.getAndIncrement()]; })).build());
            for (RuntimeException failure : failures) {
                assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            }
            Exception unknown = new Exception("receiver outcome unknown");
            assertSame(unknown, TestSupport.capture(() -> tracker.deliverBatch(events -> {
                assertEquals(3, events.size());
                assertSame(failures[0], events.get(0).failure());
                throw unknown;
            }, 3)));
            assertEquals(3, tracker.pendingCount());
            assertEquals(2, tracker.deliverBatch(events -> {
                assertEquals(3, events.size());
                assertSame(failures[0], events.get(0).failure());
                assertTrue(TestSupport.capture(() -> events.clear()) instanceof UnsupportedOperationException);
                return 2;
            }, 3));
            assertEquals(1, tracker.pendingCount());
            List<CallbackExceptionTracker.Event> remaining = new ArrayList<CallbackExceptionTracker.Event>();
            assertEquals(1, tracker.deliver(remaining::add, 1));
            assertSame(failures[2], remaining.get(0).failure());
            assertEquals(0, tracker.deliverBatch(events -> { fail("empty queue was sent"); return 1; }, 1));
        } finally { env.close(); }
    }

    @Test public void invalidAcknowledgementsAndRecursiveRemovalLeaveQueueIntact() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch-invalid", 4);
        RuntimeException failure = new RuntimeException("head");
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.batch.invalid", ADD)
                    .before(tracker.before(invocation -> { throw failure; })).build());
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            for (int invalid : new int[]{0, -1, 2}) {
                assertTrue(TestSupport.capture(() -> tracker.deliverBatch(events -> invalid, 1))
                        instanceof IllegalStateException);
                assertEquals(1, tracker.pendingCount());
            }
            assertEquals(1, tracker.deliverBatch(events -> {
                assertTrue(TestSupport.capture(tracker::discardNext) instanceof IllegalStateException);
                assertTrue(TestSupport.capture(() -> tracker.deliver(event -> { }, 1)) instanceof IllegalStateException);
                assertTrue(TestSupport.capture(() -> tracker.deliverBatch(inner -> 1, 1)) instanceof IllegalStateException);
                return 1;
            }, 1));
            assertEquals(0, tracker.pendingCount());
            assertEquals(0L, tracker.discardedCount());
        } finally { env.close(); }
    }

    @Test public void captureDuringSendDoesNotJoinOrChangeSelectedPrefix() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("batch-append", 3);
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        AtomicInteger calls = new AtomicInteger();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("trace.batch.append", ADD)
                    .before(tracker.before(invocation -> { throw calls.getAndIncrement() == 0 ? first : second; })).build());
            assertSame(first, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(1, tracker.deliverBatch(events -> {
                assertEquals(1, events.size());
                assertSame(second, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                assertEquals(1, events.size());
                return 1;
            }, 3));
            assertEquals(1, tracker.pendingCount());
            assertSame(second, tracker.discardNext().failure());
        } finally { env.close(); }
    }
}
