package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Real core dispatch with a fake entry: new calls bypass a closed registration while an old call drains. */
public class CloseHeldOriginalTest {
    private static final Executable TARGET = TestSupport.method("add", int.class, int.class);

    @Test
    public void heldOriginalDrainsOnOldPlanWhileNewCallBypassesSdk() throws Throwable {
        for (boolean resident : new boolean[]{false, true}) {
            FakeBackend backend = FakeBackend.create().withResidentOnClose(resident);
            HookEnvironment environment = HookEnvironment.create(backend);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicInteger before = new AtomicInteger();
            AtomicInteger after = new AtomicInteger();
            AtomicInteger oldOriginal = new AtomicInteger();
            AtomicInteger newOriginal = new AtomicInteger();
            AtomicReference<Object> oldResult = new AtomicReference<Object>();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            AtomicReference<CloseReport> report = new AtomicReference<CloseReport>();
            backend.onTarget(TARGET, (receiver, args) -> {
                if ((Integer) args[0] == 1) {
                    oldOriginal.incrementAndGet();
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held original was not released");
                    }
                } else {
                    newOriginal.incrementAndGet();
                }
                return 7;
            });
            HookRegistration registration = TestSupport.installOrFail(environment,
                    TestSupport.spec("close.held.original", TARGET)
                            .access(ParameterAccess.NO_ARGS)
                            .before(invocation -> before.incrementAndGet())
                            .after((invocation, result) -> after.incrementAndGet())
                            .build());
            Thread worker = new Thread(() -> {
                try { oldResult.set(backend.call(TARGET, null, 1, 0)); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "held-original");
            Thread closer = new Thread(() -> {
                try { report.set(registration.close()); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { closed.countDown(); }
            }, "held-original-close");
            worker.setDaemon(true);
            closer.setDaemon(true);
            try {
                worker.start();
                assertTrue("original did not enter", entered.await(2, TimeUnit.SECONDS));
                assertTrue(worker.isAlive());
                assertEquals(1, before.get());
                assertEquals(0, after.get());
                closer.start();
                assertTrue("close must return before the held original is released",
                        closed.await(2, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertTrue(worker.isAlive());
                assertEquals(1L, release.getCount());
                CloseReport snapshot = report.get();
                assertTrue(snapshot.newCallbacksRejected());
                assertEquals(CloseReport.EntryRestoration.RESTORED, snapshot.entryRestoration());
                assertEquals(1, snapshot.inFlightAtClose());
                assertEquals(resident ? RegistrationState.RETIRED_RESIDENT : RegistrationState.CLOSED,
                        snapshot.state());
                assertEquals(7, backend.call(TARGET, null, 0, 0));
                assertEquals(1, oldOriginal.get());
                assertEquals(1, newOriginal.get());
                assertEquals(1, before.get());
                assertEquals(0, after.get());
                assertTrue(worker.isAlive());
                release.countDown();
                worker.join(2_000L);
                closer.join(2_000L);
                assertFalse(worker.isAlive());
                assertFalse(closer.isAlive());
                assertNull(failure.get());
                assertEquals(7, oldResult.get());
                assertEquals(2, backend.originalCallCount(TARGET));
                assertEquals(1, after.get());
                assertSame("idempotent close preserves the historical snapshot", snapshot, registration.close());
                assertEquals(1, snapshot.inFlightAtClose());
            } finally {
                release.countDown();
                worker.join(2_000L);
                closer.join(2_000L);
                if (!closer.isAlive()) environment.close();
            }
        }
    }
}
