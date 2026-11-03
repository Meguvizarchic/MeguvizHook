package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ModuleEntryRouter;
import io.github.jichuo1.meguviz.hook.core.ModuleLifecycle;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ModuleLifecycleContractTest {
    @Test public void quiescenceIsExplicitNonblockingAndReversibleOnlyBeforeCleanup() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(4);
        List<String> order = new ArrayList<String>();
        lifecycle.own("first", () -> order.add("first"));
        lifecycle.own("second", () -> order.add("second"));
        ModuleLifecycle.Lease lease = lifecycle.tryEnter();
        assertNotNull(lease);
        lifecycle.requestQuiescence();
        assertNull(lifecycle.tryEnter());
        assertEquals(ModuleLifecycle.CleanupStatus.BUSY, lifecycle.tryCleanup().status());
        assertTrue(lifecycle.resumeAdmissions());
        ModuleLifecycle.Lease another = lifecycle.tryEnter();
        assertNotNull(another);
        lifecycle.requestQuiescence();
        lease.close(); lease.close();
        assertEquals(1, lifecycle.inFlight());
        another.close();
        ModuleLifecycle.CleanupReport report = lifecycle.tryCleanup();
        assertEquals(ModuleLifecycle.CleanupStatus.QUIESCED, report.status());
        assertEquals(Arrays.asList("second", "first"), order);
        assertSame(report, lifecycle.tryCleanup());
        assertFalse(lifecycle.resumeAdmissions());
    }

    @Test public void cleanupFailureContinuesAndIsNeverSilentlyRetried() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(4);
        List<String> order = new ArrayList<String>();
        IllegalStateException failure = new IllegalStateException("side effect may have happened");
        lifecycle.own("first", () -> order.add("first"));
        lifecycle.own("bad", () -> { order.add("bad"); throw failure; });
        lifecycle.own("last", () -> {
            order.add("last");
            assertEquals(ModuleLifecycle.CleanupStatus.IN_PROGRESS, lifecycle.tryCleanup().status());
        });
        lifecycle.requestQuiescence();
        ModuleLifecycle.CleanupReport report = lifecycle.tryCleanup();
        assertEquals(ModuleLifecycle.CleanupStatus.FAILED, report.status());
        assertEquals(Arrays.asList("last", "bad", "first"), order);
        assertEquals("bad", report.failures().get(0).resource());
        assertSame(failure, report.failures().get(0).cause());
        assertSame(report, lifecycle.tryCleanup());
        assertFalse(lifecycle.resumeAdmissions());
        assertEquals(3, order.size());
    }

    @SuppressWarnings("removal")
    @Test public void fatalCleanupPropagatesAndDoesNotClaimSuccess() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(2);
        ThreadDeath death = new ThreadDeath();
        lifecycle.own("fatal", () -> { throw death; });
        lifecycle.requestQuiescence();
        assertSame(death, assertThrows(ThreadDeath.class, lifecycle::tryCleanup));
        assertEquals(ModuleLifecycle.State.FAILED, lifecycle.state());
        assertEquals(ModuleLifecycle.CleanupStatus.FAILED, lifecycle.tryCleanup().status());
    }

    @Test public void interruptedCleanupContinuesAndRestoresInterruptEvenIfLaterCleared() throws Exception {
        ModuleLifecycle lifecycle = new ModuleLifecycle(2);
        AtomicBoolean subsequentSawInterrupt = new AtomicBoolean();
        AtomicBoolean finalInterrupt = new AtomicBoolean();
        AtomicReference<ModuleLifecycle.CleanupReport> result = new AtomicReference<ModuleLifecycle.CleanupReport>();
        InterruptedException interrupted = new InterruptedException("cleanup interrupted");
        // Reverse order: the failing close runs first; the following close consumes the flag.
        lifecycle.own("subsequent", () -> subsequentSawInterrupt.set(Thread.interrupted()));
        lifecycle.own("interrupted", () -> { throw interrupted; });
        lifecycle.requestQuiescence();
        Thread worker = new Thread(() -> {
            result.set(lifecycle.tryCleanup());
            finalInterrupt.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertTrue(subsequentSawInterrupt.get());
        assertTrue(finalInterrupt.get());
        assertEquals(ModuleLifecycle.CleanupStatus.FAILED, result.get().status());
        assertSame(interrupted, result.get().failures().get(0).cause());
    }

    @Test public void boundedResourcesRejectDuplicateIdentity() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(1);
        AutoCloseable one = () -> { };
        lifecycle.own("one", one);
        assertThrows(IllegalStateException.class, () -> lifecycle.own("two", () -> { }));
        ModuleLifecycle other = new ModuleLifecycle(2);
        other.own("one", one);
        assertThrows(IllegalArgumentException.class, () -> other.own("duplicate", one));
    }

    @Test public void routingUsesLoaderIdentityAndNeverRetriesFailedEntry() throws Exception {
        ModuleLifecycle lifecycle = new ModuleLifecycle(4);
        AtomicInteger created = new AtomicInteger();
        AtomicReference<ModuleEntryRouter> holder = new AtomicReference<ModuleEntryRouter>();
        ClassLoader first = new EqualLoader();
        ClassLoader second = new EqualLoader();
        RuntimeException partial = new RuntimeException("handler side effects unknown");
        ModuleEntryRouter router = new ModuleEntryRouter("fake.process", 1, 3, lifecycle,
                info -> !info.packageName().equals("filtered"),
                info -> { created.incrementAndGet(); return HookEnvironment.create(FakeBackend.create()); },
                context -> {
                    assertEquals(ModuleEntryRouter.Status.IN_PROGRESS,
                            holder.get().onPackageReady(context.info().packageName(), context.info().classLoader()).status());
                    if (context.info().packageName().equals("bad")) throw partial;
                });
        holder.set(router);
        assertEquals(ModuleEntryRouter.Status.FILTERED, router.onPackageReady("filtered", first).status());
        assertEquals(0, router.routeCount());
        assertEquals(ModuleEntryRouter.Status.READY, router.onPackageReady("host", first).status());
        assertEquals(ModuleEntryRouter.Status.ALREADY_READY, router.onPackageReady("host", first).status());
        assertEquals(ModuleEntryRouter.Status.READY, router.onPackageReady("host", second).status());
        assertSame(partial, assertThrows(RuntimeException.class, () -> router.onPackageReady("bad", first)));
        ModuleEntryRouter.Result failed = router.onPackageReady("bad", first);
        assertEquals(ModuleEntryRouter.Status.FAILED, failed.status());
        assertSame(partial, failed.failure());
        assertNotNull(failed.context());
        assertEquals(3, created.get());
        assertEquals(ModuleEntryRouter.Status.CAPACITY_REACHED, router.onPackageReady("other", first).status());
        assertThrows(IllegalStateException.class, router::detachContexts);
        lifecycle.requestQuiescence();
        assertEquals(ModuleLifecycle.CleanupStatus.QUIESCED, lifecycle.tryCleanup().status());
        List<ModuleEntryRouter.Result> detached = router.detachContexts();
        assertEquals(3, detached.size());
        assertEquals(0, router.routeCount());
        assertThrows(UnsupportedOperationException.class, detached::clear);
        assertEquals(ModuleEntryRouter.Status.QUIESCING, router.onPackageReady("host", first).status());
        // Explicit caller ownership: detach does not close any retained HookEnvironment.
        for (ModuleEntryRouter.Result item : detached) if (item.context() != null) item.context().environment().close();
    }

    @Test public void backendFactoryChecksProcessLoaderAndEpoch() throws Exception {
        FakeBackend backend = FakeBackend.create();
        ModuleLifecycle lifecycle = new ModuleLifecycle(2);
        ModuleEntryRouter.EnvironmentFactory factory = ModuleEntryRouter.fromBackend(
                info -> backend, new ReflectiveMemberResolver(), DiagnosticsSink.NONE);
        ClassLoader loader = FakeBackend.class.getClassLoader();
        ModuleEntryRouter valid = new ModuleEntryRouter("fake.process", 1, 1, lifecycle,
                info -> true, factory, context -> { });
        assertEquals(ModuleEntryRouter.Status.READY, valid.onPackageReady("host", loader).status());
        ModuleEntryRouter wrong = new ModuleEntryRouter("wrong.process", 1, 1, new ModuleLifecycle(1),
                info -> true, factory, context -> fail("must reject mismatched process"));
        assertThrows(IllegalArgumentException.class, () -> wrong.onPackageReady("host", loader));
        assertEquals(ModuleEntryRouter.Status.FAILED, wrong.onPackageReady("host", loader).status());
        valid.snapshotContexts().get(0).context().environment().close();
    }

    @Test public void inFlightEntryPreventsCleanupWithoutHoldingRouterLock() throws Exception {
        ModuleLifecycle lifecycle = new ModuleLifecycle(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        ModuleEntryRouter router = new ModuleEntryRouter("fake.process", 1, 1, lifecycle,
                info -> true, info -> HookEnvironment.create(FakeBackend.create()), context -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                });
        ClassLoader loader = FakeBackend.class.getClassLoader();
        Thread worker = new Thread(() -> {
            try { router.onPackageReady("host", loader); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(ModuleEntryRouter.Status.IN_PROGRESS, router.onPackageReady("host", loader).status());
        lifecycle.requestQuiescence();
        assertEquals(ModuleLifecycle.CleanupStatus.BUSY, lifecycle.tryCleanup().status());
        release.countDown();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(ModuleLifecycle.CleanupStatus.QUIESCED, lifecycle.tryCleanup().status());
        List<ModuleEntryRouter.Result> contexts = router.detachContexts();
        assertEquals(1, contexts.size());
        contexts.get(0).context().environment().close();
    }

    private static final class EqualLoader extends ClassLoader {
        @Override public boolean equals(Object other) { return other instanceof EqualLoader; }
        @Override public int hashCode() { return 1; }
    }
}
