package io.github.jichuo1.meguviz.hook.core.advanced;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure JVM lifecycle/lease tests; no framework or ART class initialization is exercised. */
public final class ManagedInitializerRegistrationTest {
    @Test public void passThroughBeforePublishAndOneShotContinuationContract() throws Throwable {
        AtomicReference<ManagedInitializerRegistration.Dispatch> dispatcher = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger downstream = new AtomicInteger();
        ManagedInitializerRegistration registration = ManagedInitializerRegistration.install(
                String.class, invocation -> { callbacks.incrementAndGet(); invocation.proceed(); }, dispatch -> {
                    dispatcher.set(dispatch);
                    try { assertEquals("preparing", dispatch.intercept(() -> { downstream.incrementAndGet(); return "preparing"; })); }
                    catch (Throwable error) { throw new AssertionError(error); }
                    return () -> { };
                });
        assertEquals(0, callbacks.get());
        assertNull(dispatcher.get().intercept(() -> { downstream.incrementAndGet(); return null; }));
        assertEquals(1, callbacks.get());
        assertEquals(2, downstream.get());
        assertEquals(CloseReport.EntryRestoration.RESTORED, registration.close().entryRestoration());
        assertEquals("after-close", dispatcher.get().intercept(() -> { downstream.incrementAndGet(); return "after-close"; }));
        assertEquals(3, downstream.get());
    }

    @Test public void closeRejectsNewCallbacksButKeepsAdmittedCallAndCountsIt() throws Throwable {
        AtomicReference<ManagedInitializerRegistration.Dispatch> dispatcher = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger unhooks = new AtomicInteger();
        AtomicInteger downstream = new AtomicInteger();
        AtomicReference<Throwable> threadError = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ManagedInitializerRegistration registration = ManagedInitializerRegistration.install(String.class, invocation -> {
            callbacks.incrementAndGet(); entered.countDown();
            if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out");
            invocation.proceed();
        }, dispatch -> { dispatcher.set(dispatch); return unhooks::incrementAndGet; });
        Thread worker = new Thread(() -> {
            try { dispatcher.get().intercept(() -> { downstream.incrementAndGet(); return null; }); }
            catch (Throwable error) { threadError.set(error); }
        });
        worker.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        try {
            CloseReport report = registration.close();
            assertEquals(1, report.inFlightAtClose());
            assertTrue(report.newCallbacksRejected());
            assertEquals(CloseReport.PhysicalRelease.NOT_CLAIMED, report.physicalRelease());
            assertEquals("passthrough", dispatcher.get().intercept(() -> { downstream.incrementAndGet(); return "passthrough"; }));
            assertSame(report, registration.close());
            assertEquals(1, unhooks.get());
        } finally { resume.countDown(); worker.join(10000); }
        assertFalse(worker.isAlive());
        assertNull(threadError.get());
        assertEquals(1, callbacks.get());
        assertEquals(2, downstream.get());
        assertEquals(0, registration.inFlight());
    }

    @Test public void callbackAndDownstreamFailuresAreNeverReplayed() {
        AtomicReference<ManagedInitializerRegistration.Dispatch> dispatch = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        RuntimeException original = new RuntimeException("downstream");
        ManagedInitializerRegistration.install(String.class, AdvancedHookOperations.ClassInitializerInvocation::proceed,
                d -> { dispatch.set(d); return () -> { }; });
        Throwable seen = assertThrows(RuntimeException.class,
                () -> dispatch.get().intercept(() -> { calls.incrementAndGet(); throw original; }));
        assertSame(original, seen);
        assertEquals(1, calls.get());

        ManagedInitializerRegistration.install(String.class, invocation -> { invocation.proceed(); invocation.proceed(); },
                d -> { dispatch.set(d); return () -> { }; });
        assertThrows(HookContractException.class, () -> dispatch.get().intercept(() -> { calls.incrementAndGet(); return null; }));
        assertEquals(2, calls.get());
        ManagedInitializerRegistration.install(String.class, invocation -> { }, d -> { dispatch.set(d); return () -> { }; });
        assertThrows(HookContractException.class, () -> dispatch.get().intercept(() -> { calls.incrementAndGet(); return null; }));
        assertEquals(2, calls.get());
        ManagedInitializerRegistration.install(String.class, AdvancedHookOperations.ClassInitializerInvocation::skip,
                d -> { dispatch.set(d); return () -> { }; });
        assertNull(assertNoThrow(dispatch.get()));
        assertEquals(2, calls.get());
    }

    private static Object assertNoThrow(ManagedInitializerRegistration.Dispatch dispatch) {
        try { return dispatch.intercept(() -> { throw new AssertionError("skipped continuation invoked"); }); }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }

    @Test public void invocationCannotEscapeItsCallbackOrBeUsedOnAnotherThread() throws Throwable {
        AtomicReference<ManagedInitializerRegistration.Dispatch> dispatcher = new AtomicReference<>();
        AtomicReference<AdvancedHookOperations.ClassInitializerInvocation> borrowed = new AtomicReference<>();
        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        ManagedInitializerRegistration.install(String.class, invocation -> {
            borrowed.set(invocation);
            Thread other = new Thread(() -> {
                try { invocation.targetClass(); } catch (Throwable failure) { crossThread.set(failure); }
            });
            other.start(); other.join(10000);
            if (other.isAlive()) throw new AssertionError("thread did not finish");
            invocation.skip();
        }, dispatch -> { dispatcher.set(dispatch); return () -> { }; });
        dispatcher.get().intercept(() -> null);
        assertTrue(crossThread.get() instanceof HookContractException);
        assertThrows(HookContractException.class, () -> borrowed.get().targetClass());
    }

    @Test public void unhookFailureIsUnknownAndNotAutomaticallyRetried() {
        AtomicReference<ManagedInitializerRegistration.Dispatch> dispatcher = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("unhook may have completed");
        ManagedInitializerRegistration registration = ManagedInitializerRegistration.install(String.class,
                AdvancedHookOperations.ClassInitializerInvocation::skip,
                d -> { dispatcher.set(d); return () -> { calls.incrementAndGet(); throw failure; }; });
        assertSame(failure, assertThrows(IllegalStateException.class, registration::close));
        assertEquals(CloseReport.EntryRestoration.UNKNOWN, registration.close().entryRestoration());
        assertEquals(1, calls.get());
        try { assertEquals("original", dispatcher.get().intercept(() -> "original")); }
        catch (Throwable unexpected) { throw new AssertionError(unexpected); }
    }

    @Test public void failedInstallLeavesLeakedEntryDisabled() throws Throwable {
        AtomicReference<ManagedInitializerRegistration.Dispatch> dispatcher = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ManagedInitializerRegistration.install(String.class,
                invocation -> callbacks.incrementAndGet(), d -> { dispatcher.set(d); return null; }));
        assertEquals("pass", dispatcher.get().intercept(() -> "pass"));
        assertEquals(0, callbacks.get());
    }

    @SuppressWarnings("removal")
    @Test public void fatalFromContinuationCannotBeSwallowedByCallback() {
        AtomicReference<ManagedInitializerRegistration.Dispatch> dispatcher = new AtomicReference<>();
        ThreadDeath fatal = new ThreadDeath();
        ManagedInitializerRegistration registration = ManagedInitializerRegistration.install(String.class, invocation -> {
            try { invocation.proceed(); } catch (ThreadDeath ignored) { }
        }, dispatch -> { dispatcher.set(dispatch); return () -> { }; });
        assertSame(fatal, assertThrows(ThreadDeath.class, () -> dispatcher.get().intercept(() -> { throw fatal; })));
        assertEquals(0, registration.inFlight());
    }
}
