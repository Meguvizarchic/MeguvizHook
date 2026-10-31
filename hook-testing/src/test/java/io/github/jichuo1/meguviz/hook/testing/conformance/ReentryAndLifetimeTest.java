package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Reentry, borrowed-lifetime, close/in-flight behaviour and a bounded concurrency smoke test. */
public class ReentryAndLifetimeTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    private FakeBackend backend;
    private HookEnvironment environment;

    @Before
    public void setUp() {
        backend = FakeBackend.create();
        environment = HookEnvironment.create(backend);
    }

    @Test
    public void reentrantCallsOwnIndependentFrames() {
        List<String> events = new ArrayList<String>();
        backend.onTarget(ADD, new FakeBackend.OriginalBehavior() {
            @Override
            public Object invoke(Object thisObject, Object[] args) throws Throwable {
                int depth = (Integer) args[0];
                if (depth == 0) {
                    return 0;
                }
                // Synchronous reentry into the same hooked target.
                return (Integer) backend.call(ADD, null, depth - 1, 0) + 1;
            }
        });
        TestSupport.installOrFail(environment, TestSupport.spec("reentry", ADD)
                .access(ParameterAccess.READ_ARGS)
                .around(invocation -> {
                    int depth = (Integer) invocation.getArgument(0);
                    events.add("enter-" + depth);
                    invocation.proceed();
                    events.add("exit-" + depth);
                    // A nested call must not have disturbed this frame's proceed state.
                    assertEquals(io.github.jichuo1.meguviz.hook.api.CallOutcome.State.RETURNED, invocation.proceedState());
                })
                .build());
        try {
            assertEquals(3, backend.call(ADD, null, 3, 0));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals(4, backend.originalCallCount(ADD));
        assertEquals(java.util.Arrays.asList(
                "enter-3", "enter-2", "enter-1", "enter-0",
                "exit-0", "exit-1", "exit-2", "exit-3"), events);
    }

    @Test
    public void borrowedInvocationRejectsOtherThreads() throws Exception {
        AtomicReference<HookInvocation> borrowed = new AtomicReference<HookInvocation>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> crossThreadFailure = new AtomicReference<Throwable>();
        TestSupport.installOrFail(environment, TestSupport.spec("lifetime.thread", ADD)
                .access(ParameterAccess.READ_ARGS)
                .before(invocation -> {
                    borrowed.set(invocation);
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build());

        Thread caller = new Thread(() -> {
            try {
                backend.call(ADD, null, 1, 2);
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }, "hook-caller");
        caller.start();
        entered.await();
        Thread other = new Thread(() -> crossThreadFailure.set(TestSupport.capture(
                () -> ((ReadArgsInvocation) borrowed.get()).getArgument(0))), "other-thread");
        other.start();
        other.join();
        release.countDown();
        caller.join();
        assertTrue("expected cross-thread rejection but got " + crossThreadFailure.get(),
                crossThreadFailure.get() instanceof HookContractException);
    }

    @Test
    public void invocationExpiresWhenCallbackReturns() {
        AtomicReference<HookInvocation> leaked = new AtomicReference<HookInvocation>();
        backend.onTarget(ADD, (thisObject, args) -> 0);
        TestSupport.installOrFail(environment, TestSupport.spec("lifetime.expiry", ADD)
                .access(ParameterAccess.READ_ARGS)
                .before(leaked::set)
                .build());
        try {
            backend.call(ADD, null, 1, 2);
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        Throwable observed = TestSupport.capture(() -> ((ReadArgsInvocation) leaked.get()).getArgument(0));
        assertTrue(observed instanceof HookContractException);
    }

    @Test
    public void closeRejectsNewCallbacksButRunsOriginalOnce() throws Throwable {
        AtomicInteger callbackRuns = new AtomicInteger();
        backend.onTarget(ADD, (thisObject, args) -> 1);
        HookRegistration registration = TestSupport.installOrFail(environment, TestSupport.spec("close.basic", ADD)
                .before(invocation -> callbackRuns.incrementAndGet())
                .build());
        backend.call(ADD, null, 1, 2);
        assertEquals(1, callbackRuns.get());

        CloseReport report = registration.close();
        assertTrue(report.newCallbacksRejected());
        assertEquals(CloseReport.EntryRestoration.RESTORED, report.entryRestoration());
        assertEquals(CloseReport.PhysicalRelease.NOT_CLAIMED, report.physicalRelease());
        assertEquals(RegistrationState.CLOSED, registration.state());

        backend.call(ADD, null, 1, 2);
        assertEquals("closed registration must not run SDK callbacks", 1, callbackRuns.get());
        assertEquals("each host call still executes the original exactly once", 2, backend.originalCallCount(ADD));
        assertTrue("close is idempotent", registration.close() == report);
    }

    @Test
    public void closeDuringInflightCallReportsLeaseAndCompletesOnOldPlan() throws Throwable {
        AtomicReference<HookRegistration> registrationRef = new AtomicReference<HookRegistration>();
        AtomicReference<CloseReport> closeReport = new AtomicReference<CloseReport>();
        backend.onTarget(ADD, (thisObject, args) -> 5);
        HookRegistration registration = TestSupport.installOrFail(environment, TestSupport.spec("close.inflight", ADD)
                .before(invocation -> closeReport.set(registrationRef.get().close()))
                .after((invocation, result) -> assertEquals(5, result.getResult()))
                .build());
        registrationRef.set(registration);

        assertEquals(5, backend.call(ADD, null, 1, 2));
        CloseReport report = closeReport.get();
        assertEquals("close must report the in-flight lease it observed", 1, report.inFlightAtClose());
        assertEquals(1, backend.originalCallCount(ADD));
        assertEquals(RegistrationState.CLOSED, registration.state());
    }

    @Test
    public void residentCloseIsReportedAsRetiredResident() {
        backend.withResidentOnClose(true);
        backend.onTarget(ADD, (thisObject, args) -> 1);
        HookRegistration registration = TestSupport.installOrFail(environment, TestSupport.spec("close.resident", ADD)
                .before(invocation -> {
                })
                .build());
        CloseReport report = registration.close();
        assertEquals(RegistrationState.RETIRED_RESIDENT, report.state());
        assertEquals(RegistrationState.RETIRED_RESIDENT, registration.state());
        assertEquals(CloseReport.PhysicalRelease.NOT_CLAIMED, report.physicalRelease());
    }

    @Test
    public void concurrentCallsAndCloseNeverReplayOriginal() throws Exception {
        backend.onTarget(ADD, (thisObject, args) -> (Integer) args[0] + (Integer) args[1]);
        HookRegistration registration = TestSupport.installOrFail(environment, TestSupport.spec("close.race", ADD)
                .before(invocation -> {
                })
                .after((invocation, result) -> {
                })
                .build());
        int threads = 4;
        int callsPerThread = 250;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<Thread>();
        AtomicReference<Throwable> unexpected = new AtomicReference<Throwable>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int call = 0; call < callsPerThread; call++) {
                        Object value = backend.call(ADD, null, 1, 2);
                        if (!Integer.valueOf(3).equals(value)) {
                            unexpected.compareAndSet(null, new AssertionError("unexpected result " + value));
                            return;
                        }
                    }
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            }, "caller-" + i);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(5000L);
        }
        registration.close();
        for (Thread worker : workers) {
            assertTrue("worker did not finish", !worker.isAlive());
        }
        if (unexpected.get() != null) {
            throw new AssertionError(unexpected.get());
        }
        assertEquals("every host call executed the original exactly once",
                threads * callsPerThread, backend.originalCallCount(ADD));
    }
}
