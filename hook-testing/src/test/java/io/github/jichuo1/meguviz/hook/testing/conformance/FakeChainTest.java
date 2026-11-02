package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Fake-only multi-hook ordering and snapshot semantics; not real framework qualification. */
public class FakeChainTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void prioritiesAndIndependentClosePreserveBothRegistrations() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        List<String> order = new ArrayList<String>();
        backend.onTarget(ADD, (receiver, args) -> { order.add("original"); return 3; });
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            HookRegistration low = TestSupport.installOrFail(environment,
                    spec("chain.low", Integer.MIN_VALUE, i -> order.add("low.before"),
                            (i, result) -> order.add("low.after")));
            HookRegistration high = TestSupport.installOrFail(environment,
                    spec("chain.high", Integer.MAX_VALUE, i -> order.add("high.before"),
                            (i, result) -> order.add("high.after")));
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("high.before", "low.before", "original", "low.after", "high.after"), order);
            assertEquals(1, backend.originalCallCount(ADD));
            high.close();
            assertTrue(backend.isHooked(ADD));
            order.clear();
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("low.before", "original", "low.after"), order);
            low.close();
            assertFalse(backend.isHooked(ADD));
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(3, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void equalPriorityReplacementKeepsItsFakeOrderAndOtherHandles() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        List<String> order = new ArrayList<String>();
        backend.onTarget(ADD, (receiver, args) -> { order.add("original"); return 3; });
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            HookRegistration first = TestSupport.installOrFail(environment,
                    spec("chain.tie.first", 50, i -> order.add("first"), null));
            HookRegistration old = TestSupport.installOrFail(environment,
                    spec("chain.tie.middle", 50, i -> order.add("old"), null));
            HookRegistration last = TestSupport.installOrFail(environment,
                    spec("chain.tie.last", 50, i -> order.add("last"), null));
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("first", "old", "last", "original"), order);
            RegistrationReceipt replacement = environment.replace(
                    spec("chain.tie.middle", 50, i -> order.add("new"), null));
            assertTrue(replacement.toString(), replacement.isInstalled());
            old.close();
            order.clear();
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("first", "new", "last", "original"), order);
            first.close();
            order.clear();
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("new", "last", "original"), order);
            replacement.registration().close();
            assertTrue(backend.isHooked(ADD));
            last.close();
            assertFalse(backend.isHooked(ADD));
            assertEquals(3, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void sameIdAcrossEnvironmentsDoesNotOverwriteOrUnhookSibling() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        List<String> order = new ArrayList<String>();
        backend.onTarget(ADD, (receiver, args) -> { order.add("original"); return 3; });
        HookEnvironment firstEnvironment = HookEnvironment.create(backend);
        HookEnvironment secondEnvironment = HookEnvironment.create(backend);
        try {
            HookRegistration old = TestSupport.installOrFail(firstEnvironment,
                    spec("chain.shared-id", 100, i -> order.add("first.old"), null));
            HookRegistration sibling = TestSupport.installOrFail(secondEnvironment,
                    spec("chain.shared-id", 50, i -> order.add("second"), null));
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("first.old", "second", "original"), order);

            RegistrationReceipt replacement = firstEnvironment.replace(
                    spec("chain.shared-id", 100, i -> order.add("first.new"), null));
            assertTrue(replacement.toString(), replacement.isInstalled());
            old.close();
            order.clear();
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("first.new", "second", "original"), order);

            sibling.close();
            assertTrue(backend.isHooked(ADD));
            order.clear();
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("first.new", "original"), order);
            replacement.registration().close();
            assertFalse(backend.isHooked(ADD));
            assertEquals(3, backend.originalCallCount(ADD));
        } finally {
            firstEnvironment.close();
            secondEnvironment.close();
        }
    }

    @Test
    public void argumentWritesFlowDownstreamButEachFrameKeepsItsOwnSlots() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment environment = HookEnvironment.create(backend);
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("chain.args.high", ADD).priority(100)
                    .access(ParameterAccess.MUTABLE_ARGS)
                    .before(i -> ((MutableArgsInvocation) i).setArgument(0, 10))
                    .after((i, result) -> {
                        ReadArgsInvocation args = (ReadArgsInvocation) i;
                        assertEquals(10, args.getArgument(0));
                        assertEquals("downstream write must not mutate the outer frame's slot", 2, args.getArgument(1));
                    }).build());
            TestSupport.installOrFail(environment, TestSupport.spec("chain.args.low", ADD).priority(10)
                    .access(ParameterAccess.MUTABLE_ARGS)
                    .before(i -> {
                        MutableArgsInvocation args = (MutableArgsInvocation) i;
                        assertEquals(10, args.getArgument(0));
                        args.setArgument(1, 20);
                    }).build());
            assertEquals(30, backend.call(ADD, null, 1, 2));
            assertEquals(1, backend.originalCallCount(ADD));
            assertEquals(2, backend.argumentMaterializationCount());
        } finally {
            environment.close();
        }
    }

    @Test
    public void earlyReturnStopsTheRestOfTheSnapshot() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger downstream = new AtomicInteger();
        try {
            TestSupport.installOrFail(environment, spec("chain.short.low", 0, i -> downstream.incrementAndGet(), null));
            TestSupport.installOrFail(environment, spec("chain.short.high", 100, i -> i.setEarlyResult(77), null));
            assertEquals(77, backend.call(ADD, null, 1, 2));
            assertEquals(0, downstream.get());
            assertEquals(0, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void failingBeforeStopsLowerHooksAndOriginal() {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        HookEnvironment environment = HookEnvironment.create(backend);
        RuntimeException failure = new RuntimeException("before");
        AtomicInteger downstream = new AtomicInteger();
        AtomicInteger afters = new AtomicInteger();
        try {
            TestSupport.installOrFail(environment, spec("chain.fail.low", 0, i -> downstream.incrementAndGet(),
                    (i, result) -> afters.incrementAndGet()));
            TestSupport.installOrFail(environment, spec("chain.fail.high", 100, i -> { throw failure; },
                    (i, result) -> afters.incrementAndGet()));
            assertSame(failure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(0, downstream.get());
            assertEquals(0, afters.get());
            assertEquals(0, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void retiredUnenteredHookPassesToRemainingSnapshotAndNewInstallWaitsForNextCall() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        backend.onTarget(ADD, (receiver, args) -> { order.add("original"); return 3; });
        HookEnvironment environment = HookEnvironment.create(backend);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        AtomicReference<Object> result = new AtomicReference<Object>();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread call = new Thread(() -> {
            try { result.set(backend.call(ADD, null, 1, 2)); } catch (Throwable failure) { error.set(failure); }
        });
        try {
            TestSupport.installOrFail(environment, spec("chain.snapshot.high", 100, i -> {
                order.add("high");
                if (blockOnce.compareAndSet(true, false)) { entered.countDown(); await(release); }
            }, null));
            HookRegistration middle = TestSupport.installOrFail(environment,
                    spec("chain.snapshot.middle", 50, i -> order.add("middle"), null));
            TestSupport.installOrFail(environment, spec("chain.snapshot.low", 20, i -> order.add("low"), null));
            call.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            middle.close();
            TestSupport.installOrFail(environment, spec("chain.snapshot.new", 10, i -> order.add("new"), null));
            release.countDown();
            call.join(5000L);
            assertFalse(call.isAlive());
            assertNull(error.get());
            assertEquals(3, result.get());
            assertEquals(Arrays.asList("high", "low", "original"), order);
            order.clear();
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("high", "low", "new", "original"), order);
            assertEquals(2, backend.originalCallCount(ADD));
        } finally {
            release.countDown();
            call.join(5000L);
            environment.close();
        }
    }

    @Test
    public void inFlightReplacedHookCompletesOldPlanAndNextCallUsesNewPlan() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        backend.onTarget(ADD, (receiver, args) -> { order.add("original"); return 3; });
        HookEnvironment environment = HookEnvironment.create(backend);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread call = new Thread(() -> {
            try { backend.call(ADD, null, 1, 2); } catch (Throwable failure) { error.set(failure); }
        });
        try {
            TestSupport.installOrFail(environment, spec("chain.replace.high", 100, i -> order.add("high"), null));
            HookRegistration old = TestSupport.installOrFail(environment, spec("chain.replace.middle", 50, i -> {
                order.add("old"); entered.countDown(); await(release);
            }, null));
            TestSupport.installOrFail(environment, spec("chain.replace.low", 0, i -> order.add("low"), null));
            call.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            RegistrationReceipt replacement = environment.replace(spec("chain.replace.middle", 50, i -> order.add("new"), null));
            assertTrue(replacement.toString(), replacement.isInstalled());
            old.close();
            release.countDown();
            call.join(5000L);
            assertFalse(call.isAlive());
            assertNull(error.get());
            assertEquals(Arrays.asList("high", "old", "low", "original"), order);
            order.clear();
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(Arrays.asList("high", "new", "low", "original"), order);
            assertEquals(2, backend.originalCallCount(ADD));
        } finally {
            release.countDown();
            call.join(5000L);
            environment.close();
        }
    }

    @Test
    public void originalThrowableKeepsIdentityThroughEveryHook() {
        FakeBackend backend = FakeBackend.create();
        RuntimeException original = new RuntimeException("original");
        backend.onTarget(ADD, (receiver, args) -> { throw original; });
        HookEnvironment environment = HookEnvironment.create(backend);
        AtomicInteger afters = new AtomicInteger();
        try {
            TestSupport.installOrFail(environment, spec("chain.throw.first", 100, null, (i, result) -> {
                assertSame(original, result.getThrowable()); afters.incrementAndGet();
            }));
            TestSupport.installOrFail(environment, spec("chain.throw.second", 10, null, (i, result) -> {
                assertSame(original, result.getThrowable()); afters.incrementAndGet();
            }));
            assertSame(original, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(2, afters.get());
            assertEquals(1, backend.originalCallCount(ADD));
        } finally {
            environment.close();
        }
    }

    @Test
    public void concurrentEnvironmentsInstallAndCloseWithoutLosingOtherHooks() throws Throwable {
        final int count = 12;
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        List<HookEnvironment> environments = new ArrayList<HookEnvironment>();
        List<HookRegistration> registrations = new ArrayList<HookRegistration>(Collections.nCopies(count, null));
        List<Thread> threads = new ArrayList<Thread>();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int index = 0; index < count; index++) {
                final int position = index;
                HookEnvironment environment = HookEnvironment.create(backend);
                environments.add(environment);
                Thread worker = new Thread(() -> {
                    try {
                        await(start);
                        registrations.set(position, TestSupport.installOrFail(environment,
                                spec("chain.concurrent." + position, 50, i -> callbacks.incrementAndGet(), null)));
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                });
                threads.add(worker);
                worker.start();
            }
            start.countDown();
            joinAll(threads);
            assertNull(failure.get());
            assertEquals(count, backend.installCount());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(count, callbacks.get());
            threads.clear();
            CountDownLatch closeStart = new CountDownLatch(1);
            for (int index = 0; index < count; index += 2) {
                HookRegistration registration = registrations.get(index);
                Thread worker = new Thread(() -> {
                    try { await(closeStart); registration.close(); }
                    catch (Throwable error) { failure.compareAndSet(null, error); }
                });
                threads.add(worker);
                worker.start();
            }
            closeStart.countDown();
            joinAll(threads);
            assertNull(failure.get());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(count + count / 2, callbacks.get());
            assertEquals(2, backend.originalCallCount(ADD));
        } finally {
            start.countDown();
            joinAll(threads);
            for (HookEnvironment environment : environments) environment.close();
        }
        assertFalse(backend.isHooked(ADD));
    }

    private static HookSpec spec(String id, int priority, BeforeCallback before, AfterCallback after) {
        HookSpec.Builder builder = TestSupport.spec(id, ADD).priority(priority);
        if (before != null) builder.before(before);
        if (after != null) builder.after(after);
        return builder.build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test latch");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(5000L);
            assertFalse("worker did not finish", thread.isAlive());
        }
    }
}
