package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookInvocation;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Consumer-visible behavior of the sole after-only callback shape. */
public class SingleAfterLeaseFastPathTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void resultAndInvocationExpireIndependentlyAcrossReentryAndThreads() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<HookInvocation> outer = new AtomicReference<HookInvocation>();
        AtomicReference<HookInvocation> inner = new AtomicReference<HookInvocation>();
        AtomicReference<InvocationResult> outerResult = new AtomicReference<InvocationResult>();
        AtomicReference<InvocationResult> innerResult = new AtomicReference<InvocationResult>();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("single.after.lease", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW).after((invocation, result) -> {
                        int depth = (Integer) ((ReadArgsInvocation) invocation).getArgument(0);
                        assertNotSame(invocation, result);
                        assertSame(Thread.currentThread(), invocation.ownerThread());
                        if (depth == 1) {
                            outer.set(invocation);
                            outerResult.set(result);
                            AtomicReference<Throwable> wrongThread = new AtomicReference<Throwable>();
                            Thread other = new Thread(() -> wrongThread.set(TestSupport.capture(result::getResult)));
                            other.start();
                            other.join(2000L);
                            assertFalse(other.isAlive());
                            assertTrue(wrongThread.get() instanceof HookContractException);
                            assertEquals(2, backend.call(ADD, null, 0, 2));
                            assertThrows(HookContractException.class, () -> inner.get().target());
                            assertThrows(HookContractException.class, () -> innerResult.get().getResult());
                            assertEquals(3, result.getResult());
                            result.setResult(7);
                        } else {
                            inner.set(invocation);
                            innerResult.set(result);
                            assertNotSame(outer.get(), invocation);
                            assertEquals(3, outerResult.get().getResult());
                            assertEquals(2, result.getResult());
                        }
                    }).build());
            assertEquals(7, backend.call(ADD, null, 1, 2));
            assertEquals(2, backend.originalCallCount(ADD));
            assertThrows(HookContractException.class, () -> outer.get().access());
            assertThrows(HookContractException.class, () -> outerResult.get().getResult());
        } finally {
            env.close();
        }
    }

    @Test public void originalAndAfterFailuresKeepPrecedenceAndExpireBeforeDiagnostics() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        RuntimeException original = new IllegalStateException("original");
        RuntimeException after = new IllegalArgumentException("after");
        backend.onTarget(ADD, (self, args) -> {
            if ((Integer) args[0] < 2) throw original;
            return 3;
        });
        AtomicReference<HookInvocation> lastInvocation = new AtomicReference<HookInvocation>();
        AtomicReference<InvocationResult> lastResult = new AtomicReference<InvocationResult>();
        AtomicReference<DiagnosticsSink.Event> event = new AtomicReference<DiagnosticsSink.Event>();
        AtomicReference<Throwable> invocationExpired = new AtomicReference<Throwable>();
        AtomicReference<Throwable> resultExpired = new AtomicReference<Throwable>();
        DiagnosticsSink diagnostics = entry -> {
            event.set(entry);
            invocationExpired.set(TestSupport.capture(() -> lastInvocation.get().id()));
            resultExpired.set(TestSupport.capture(() -> lastResult.get().getThrowable()));
        };
        HookEnvironment env = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
        try {
            TestSupport.installOrFail(env, TestSupport.spec("single.after.failure", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW).after((invocation, result) -> {
                        lastInvocation.set(invocation);
                        lastResult.set(result);
                        int mode = (Integer) ((ReadArgsInvocation) invocation).getArgument(0);
                        if (mode == 1) {
                            assertSame(original, result.getThrowable());
                            result.setResult(9);
                            return;
                        }
                        result.setResult(10);
                        throw after;
                    }).build());
            assertSame(original, TestSupport.capture(() -> backend.call(ADD, null, 0, 0)));
            assertSame(after, event.get().failure());
            assertTrue(invocationExpired.get() instanceof HookContractException);
            assertTrue(resultExpired.get() instanceof HookContractException);
            assertEquals(9, backend.call(ADD, null, 1, 0));
            assertSame(after, TestSupport.capture(() -> backend.call(ADD, null, 2, 0)));
            assertEquals(3, backend.originalCallCount(ADD));
        } finally {
            env.close();
        }
    }

    @Test public void caughtFatalInAfterStillPropagatesWithoutReplayingOriginal() {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 3);
        HookEnvironment env = HookEnvironment.create(backend);
        ThreadDeath fatal = new ThreadDeath();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("single.after.fatal", ADD)
                    .after((invocation, result) -> {
                        try { result.setThrowable(fatal); } catch (ThreadDeath deliberatelyCaught) { }
                        result.setResult(42);
                    }).build());
            assertSame(fatal, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(1, backend.originalCallCount(ADD));
        } finally {
            env.close();
        }
    }

    @Test public void diagnosticFailuresDoNotChangeTheExistingPrecedence() {
        for (boolean fatalSink : new boolean[]{false, true}) {
            FakeBackend backend = FakeBackend.create();
            RuntimeException original = new IllegalStateException("original");
            ThreadDeath fatal = new ThreadDeath();
            backend.onTarget(ADD, (self, args) -> { throw original; });
            DiagnosticsSink diagnostics = entry -> {
                if (fatalSink) throw fatal;
                throw new IllegalStateException("diagnostics");
            };
            HookEnvironment env = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
            try {
                TestSupport.installOrFail(env, TestSupport.spec("single.after.diagnostic", ADD)
                        .after((invocation, result) -> { throw new IllegalArgumentException("after"); }).build());
                assertSame(fatalSink ? fatal : original, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
                assertEquals(1, backend.originalCallCount(ADD));
            } finally {
                env.close();
            }
        }
    }

    @Test public void closeReportsHeldAfterAndDoesNotRevokeIt() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (self, args) -> 3);
        HookEnvironment env = HookEnvironment.create(backend);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<Object> value = new AtomicReference<Object>();
        HookRegistration registration = TestSupport.installOrFail(env, TestSupport.spec("single.after.close", ADD)
                .after((invocation, result) -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("after not released");
                    assertTrue(result.hasReturned());
                    result.setResult(7);
                }).build());
        Thread worker = new Thread(() -> {
            try { value.set(backend.call(ADD, null, 1, 2)); }
            catch (Throwable error) { failure.set(error); }
        }, "single-after-held");
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, registration.close().inFlightAtClose());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            release.countDown();
            worker.join(2000L);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(7, value.get());
            assertEquals(2, backend.originalCallCount(ADD));
        } finally {
            release.countDown();
            worker.join(2000L);
            if (!worker.isAlive()) env.close();
        }
    }

    @Test public void voidAndConstructorAfterKeepReturnTypeChecks() throws Throwable {
        Executable[] targets = {
                TestSupport.method("append", StringBuilder.class, String.class),
                Fixtures.Constructed.class.getConstructor(int.class)
        };
        for (int index = 0; index < targets.length; index++) {
            Executable target = targets[index];
            FakeBackend backend = FakeBackend.create();
            backend.onTarget(target, (self, args) -> null);
            HookEnvironment env = HookEnvironment.create(backend);
            try {
                TestSupport.installOrFail(env, TestSupport.spec("single.after.void." + index, target)
                        .after((invocation, result) -> {
                            assertTrue(result.hasReturned());
                            assertNull(result.getResult());
                            assertThrows(IllegalArgumentException.class, () -> result.setResult("invalid"));
                            result.setResult(null);
                        }).build());
                Object[] arguments = index == 0 ? new Object[]{new StringBuilder(), "x"} : new Object[]{1};
                assertNull(backend.call(target, null, arguments));
                assertEquals(1, backend.originalCallCount(target));
            } finally {
                env.close();
            }
        }
    }

    @Test public void closeDuringOriginalKeepsTheAdmittedAfter() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        backend.onTarget(ADD, (self, args) -> {
            if (originalCalls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("original not released");
            }
            return 3;
        });
        HookEnvironment env = HookEnvironment.create(backend);
        HookRegistration registration = TestSupport.installOrFail(env, TestSupport.spec("single.after.original.close", ADD)
                .after((invocation, result) -> { afterCalls.incrementAndGet(); result.setResult(7); }).build());
        AtomicReference<Object> value = new AtomicReference<Object>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { value.set(backend.call(ADD, null, 1, 2)); }
            catch (Throwable error) { failure.set(error); }
        }, "single-after-original-held");
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, registration.close().inFlightAtClose());
            assertEquals(3, backend.call(ADD, null, 1, 2));
            assertEquals(0, afterCalls.get());
            release.countDown();
            worker.join(2000L);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(7, value.get());
            assertEquals(1, afterCalls.get());
            assertEquals(2, originalCalls.get());
        } finally {
            release.countDown();
            worker.join(2000L);
            if (!worker.isAlive()) env.close();
        }
    }
}
