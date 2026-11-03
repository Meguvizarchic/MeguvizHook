package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.FusedConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * In-flight accounting of one registration hit by many threads. The count is only reported: it is
 * exact when nothing enters or exits while close reads it, never negative, and while calls keep
 * entering and leaving it lies between the leases held throughout and those plus one per caller.
 * Admission stays decided by the registration state.
 */
public final class InFlightContentionTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable NO_ARGUMENTS = TestSupport.method("noArguments");
    private static final int CALLERS = 8;

    /** Records channels so the test can drive the optional fused and split-lease paths directly. */
    private static final class CapturingBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        final List<DispatchChannel> channels = new CopyOnWriteArrayList<DispatchChannel>();

        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }

        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) {
            channels.add(channel);
            return () -> new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                    CloseReport.PhysicalRelease.NOT_CLAIMED, false, "captured");
        }
    }

    @Test public void quiescentCloseAfterContentionReportsExactlyTheHeldLeases() throws Throwable {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            HookRegistration registration = TestSupport.installOrFail(env,
                    TestSupport.spec("inflight.fused", NO_ARGUMENTS).replaceConstant(42L).build());
            FusedConstantDispatchChannel channel = (FusedConstantDispatchChannel) backend.channels.get(0);
            Object constant = channel.constantValue();
            runConcurrently(CALLERS, caller -> {
                for (int i = 0; i < 50_000; i++) {
                    if ((i & 1) == 0) {
                        assertSame(constant, channel.dispatchValueOrNotEntered());
                    } else {
                        assertTrue(channel.tryEnter());
                        if ((i & 15) == 1) Thread.yield();
                        channel.exit();
                    }
                }
            });
            int held = 3;
            CountDownLatch holding = new CountDownLatch(held);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            List<Thread> holders = new ArrayList<Thread>();
            for (int h = 0; h < held; h++) {
                holders.add(start("holder-" + h, failure, () -> {
                    assertTrue(channel.tryEnter());
                    try {
                        holding.countDown();
                        assertTrue(release.await(30, TimeUnit.SECONDS));
                    } finally {
                        channel.exit();
                    }
                }));
            }
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            CloseReport report = registration.close();
            assertTrue(report.newCallbacksRejected());
            assertEquals("no lease enters or exits while close reads the count", held, report.inFlightAtClose());
            assertFalse(channel.tryEnter());
            assertSame(FusedConstantDispatchChannel.NOT_ENTERED, channel.dispatchValueOrNotEntered());
            release.countDown();
            for (Thread holder : holders) holder.join(10_000);
            if (failure.get() != null) throw failure.get();
        } finally {
            env.close();
        }
    }

    @Test public void closeDuringChurnCountsHeldCallsAndAtMostOneLeasePerCaller() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        int held = 2;
        int churners = 4;
        CountDownLatch holding = new CountDownLatch(held);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        backend.onTarget(ADD, (self, args) -> {
            if ((Integer) args[0] == 1) {
                holding.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            }
            return (Integer) args[0] + (Integer) args[1];
        });
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        List<Thread> threads = new ArrayList<Thread>();
        try {
            HookRegistration registration = TestSupport.installOrFail(env, TestSupport.spec("inflight.churn", ADD)
                    .before(invocation -> callbacks.incrementAndGet()).build());
            AtomicReference<Object> heldResult = new AtomicReference<Object>();
            for (int h = 0; h < held; h++) {
                threads.add(start("held-" + h, failure, () -> heldResult.set(backend.call(ADD, null, 1, 6))));
            }
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            CyclicBarrier churning = new CyclicBarrier(churners + 1);
            for (int c = 0; c < churners; c++) {
                threads.add(start("churn-" + c, failure, () -> {
                    churning.await(10, TimeUnit.SECONDS);
                    while (!stop.get()) assertEquals(5, backend.call(ADD, null, 2, 3));
                }));
            }
            churning.await(10, TimeUnit.SECONDS);
            Thread.sleep(20);
            CloseReport report = registration.close();
            int observed = report.inFlightAtClose();
            assertTrue("held calls are always counted: " + observed, observed >= held);
            assertTrue("each other caller adds at most its own lease: " + observed, observed <= held + churners);
            // A caller admitted just before close may still finish its callback; then none run.
            Thread.sleep(20);
            int afterClose = callbacks.get();
            Thread.sleep(20);
            assertEquals("closed registration runs no callbacks", afterClose, callbacks.get());
            stop.set(true);
            release.countDown();
            for (Thread thread : threads) thread.join(10_000);
            if (failure.get() != null) throw failure.get();
            assertEquals("admitted calls finish on their plan", 7, heldResult.get());
        } finally {
            stop.set(true);
            release.countDown();
            env.close();
        }
    }

    @Test public void contendedRegistrationReportsZeroOnceQuiescent() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        try {
            HookRegistration before = TestSupport.installOrFail(env, TestSupport.spec("inflight.zero", ADD)
                    .before(invocation -> { }).after((invocation, result) -> { }).build());
            runConcurrently(CALLERS, caller -> {
                for (int i = 0; i < 20_000; i++) assertEquals(caller + i % 3, backend.call(ADD, null, caller, i % 3));
            });
            assertEquals(0, before.close().inFlightAtClose());
        } finally {
            env.close();
        }
    }

    private interface CallerBody {
        void run(int caller) throws Throwable;
    }

    private interface Body {
        void run() throws Throwable;
    }

    private static void runConcurrently(int callers, CallerBody body) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<Thread> threads = new ArrayList<Thread>();
        for (int c = 0; c < callers; c++) {
            int caller = c;
            threads.add(start("caller-" + c, failure, () -> {
                barrier.await(10, TimeUnit.SECONDS);
                body.run(caller);
            }));
        }
        for (Thread thread : threads) thread.join(60_000);
        for (Thread thread : threads) assertFalse(thread.getName() + " still running", thread.isAlive());
        if (failure.get() != null) throw failure.get();
    }

    private static Thread start(String name, AtomicReference<Throwable> failure, Body body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
