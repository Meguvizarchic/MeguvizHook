package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CallOutcome;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.EntrySnapshotInvocation;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.BoundedDiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.CoreAfterBridge;
import io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * The generic plan channel (before callbacks, several callbacks, around) is reachable through the
 * optional {@link CoreAfterBridge} value transport. Every scenario runs twice through the same chain
 * fake, once with that binding (as the libxposed backends use it) and once with ordinary
 * {@link DispatchChannel#dispatch}, and must observe identical outcomes.
 */
public final class DirectPlanTransportTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable OBJECT = TestSupport.method("nullableResult", boolean.class);

    /** Chain fake mirroring the backend hooker: direct binding when available, else dispatch + kind switch. */
    private static final class ChainBackend implements HookBackend {
        final boolean direct;
        final Object marker = new Object();
        final FakeBackend capabilities = FakeBackend.create();
        final List<Hook> hooks = new CopyOnWriteArrayList<Hook>();
        final AtomicInteger originalCalls = new AtomicInteger();
        final AtomicInteger directDispatches = new AtomicInteger();
        final AtomicInteger ordinaryDispatches = new AtomicInteger();
        volatile FakeBackend.OriginalBehavior original = (self, args) -> null;

        ChainBackend(boolean direct) {
            this.direct = direct;
        }

        private final class Hook {
            final DispatchChannel channel;
            final Function<BackendInvocation, Object> binding;

            Hook(DispatchChannel channel) {
                this.channel = channel;
                this.binding = direct && channel.requiresInvocation() ? CoreAfterBridge.bind(channel, marker) : null;
            }
        }

        @Override public String id() { return capabilities.id(); }
        @Override public BackendCapabilities capabilities() { return capabilities.capabilities(); }
        @Override public BackendContext context() { return capabilities.context(); }

        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) {
            Hook hook = new Hook(channel);
            if (direct) assertNotNull("generic and single-after channels offer the direct binding", hook.binding);
            hooks.add(hook);
            return () -> {
                hooks.remove(hook);
                return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                        CloseReport.PhysicalRelease.NOT_CLAIMED, false, "chain fake removed");
            };
        }

        Object call(Object... args) throws Throwable {
            return next(new ArrayList<Hook>(hooks), 0, args);
        }

        private Object next(List<Hook> snapshot, int index, Object[] args) throws Throwable {
            if (index == snapshot.size()) {
                originalCalls.incrementAndGet();
                return original.invoke(null, args);
            }
            Hook hook = snapshot.get(index);
            BackendInvocation invocation = new BackendInvocation() {
                @Override public Executable target() { return ADD; }
                @Override public Object thisObject() { return null; }
                @Override public Object[] arguments() { return args.clone(); }
                @Override public Object proceed(Object[] supplied) throws Throwable {
                    return next(snapshot, index + 1, supplied == null ? args : supplied);
                }
            };
            if (hook.binding != null) {
                directDispatches.incrementAndGet();
                Object value = hook.binding.apply(invocation);
                if (value == marker) return next(snapshot, index + 1, args);
                return value instanceof DispatchResult ? ((DispatchResult) value).value() : value;
            }
            ordinaryDispatches.incrementAndGet();
            DispatchResult result = hook.channel.dispatch(invocation);
            switch (result.kind()) {
                case RETURNED: return result.value();
                case THREW: throw result.throwable();
                case PASSTHROUGH:
                default: return next(snapshot, index + 1, args);
            }
        }

        void assertTransport() {
            if (direct) assertEquals("ordinary path must not be used", 0, ordinaryDispatches.get());
            else assertEquals("direct path must not be used", 0, directDispatches.get());
        }
    }

    private interface Scenario {
        void run(boolean direct) throws Throwable;
    }

    private static void onBothTransports(Scenario scenario) throws Throwable {
        for (boolean direct : new boolean[]{true, false}) {
            try {
                scenario.run(direct);
            } catch (AssertionError failure) {
                throw new AssertionError((direct ? "direct" : "ordinary") + " transport: " + failure.getMessage(), failure);
            }
        }
    }

    private static HookEnvironment environment(ChainBackend backend, BoundedDiagnosticsSink diagnostics) {
        return HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
    }

    @Test public void everyGenericShapeMatchesOrdinaryDispatch() throws Throwable {
        List<HookSpec.Builder> shapes = new ArrayList<HookSpec.Builder>();
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        shapes.add(TestSupport.spec("direct.before.noargs", ADD).before(i -> events.add("before")));
        shapes.add(TestSupport.spec("direct.before.view", ADD).access(ParameterAccess.READ_ARGS_VIEW)
                .before(i -> events.add("view:" + ((ReadArgsInvocation) i).getArgument(0))));
        shapes.add(TestSupport.spec("direct.before.read", ADD).access(ParameterAccess.READ_ARGS)
                .before(i -> events.add("read:" + ((ReadArgsInvocation) i).getArgument(1))));
        shapes.add(TestSupport.spec("direct.before.snapshot", ADD).access(ParameterAccess.ENTRY_SNAPSHOT)
                .before(i -> events.add("snapshot:" + ((EntrySnapshotInvocation) i).getEntryArgument(0))));
        shapes.add(TestSupport.spec("direct.before.mutable", ADD).access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> ((MutableArgsInvocation) i).setArgument(0, 10)));
        shapes.add(TestSupport.spec("direct.after.single", ADD).after((i, r) -> events.add("after:" + r.getResult())));
        shapes.add(TestSupport.spec("direct.before.after", ADD).access(ParameterAccess.READ_ARGS)
                .before(i -> events.add("b:" + ((ReadArgsInvocation) i).getArgument(0)))
                .after((i, r) -> events.add("a:" + r.getResult())));
        shapes.add(TestSupport.spec("direct.multi", ADD)
                .before(i -> events.add("b1")).before(i -> events.add("b2"))
                .after((i, r) -> events.add("a1")).after((i, r) -> { events.add("a2"); r.setResult(100); }));
        shapes.add(TestSupport.spec("direct.around", ADD).around(i -> {
            CallOutcome outcome = i.proceed();
            i.setEarlyResult((Integer) outcome.value() + 1);
        }));
        Object[] expectedValues = {7, 7, 7, 7, 14, 7, 7, 100, 8};
        List<List<String>> expectedEvents = Arrays.asList(
                Arrays.asList("before"), Arrays.asList("view:3"), Arrays.asList("read:4"), Arrays.asList("snapshot:3"),
                Collections.<String>emptyList(), Arrays.asList("after:7"), Arrays.asList("b:3", "a:7"),
                Arrays.asList("b1", "b2", "a2", "a1"), Collections.<String>emptyList());
        for (int index = 0; index < shapes.size(); index++) {
            HookSpec spec = shapes.get(index).build();
            Object expected = expectedValues[index];
            List<String> expectedOrder = expectedEvents.get(index);
            onBothTransports(direct -> {
                events.clear();
                ChainBackend backend = new ChainBackend(direct);
                backend.original = (self, args) -> (Integer) args[0] + (Integer) args[1];
                HookEnvironment env = environment(backend, new BoundedDiagnosticsSink(4));
                try {
                    TestSupport.installOrFail(env, spec);
                    assertEquals(spec.id().toString(), expected, backend.call(3, 4));
                    assertEquals(expectedOrder, new ArrayList<String>(events));
                    assertEquals(1, backend.originalCalls.get());
                    backend.assertTransport();
                } finally {
                    env.close();
                }
            });
        }
    }

    @Test public void earlyOutcomesAndOrdinaryFailuresKeepIdentityAndCounts() throws Throwable {
        RuntimeException early = new IllegalStateException("early");
        RuntimeException before = new IllegalArgumentException("before");
        RuntimeException after = new UnsupportedOperationException("after");
        RuntimeException original = new IllegalStateException("original");
        onBothTransports(direct -> {
            ChainBackend backend = new ChainBackend(direct);
            BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
            HookEnvironment env = environment(backend, diagnostics);
            AtomicInteger afterRuns = new AtomicInteger();
            backend.original = (self, args) -> {
                if ((Integer) args[0] == 4) throw original;
                return 7;
            };
            try {
                TestSupport.installOrFail(env, TestSupport.spec("direct.outcomes", ADD).access(ParameterAccess.READ_ARGS)
                        .before(i -> {
                            int mode = (Integer) ((ReadArgsInvocation) i).getArgument(0);
                            if (mode == 0) i.setEarlyResult(42);
                            if (mode == 1) i.setEarlyThrowable(early);
                            if (mode == 2) throw before;
                        })
                        .after((i, r) -> {
                            afterRuns.incrementAndGet();
                            int mode = (Integer) ((ReadArgsInvocation) i).getArgument(0);
                            if (mode == 3 || mode == 5) throw after;
                        }).build());
                assertEquals(42, backend.call(0, 0));
                assertSame(early, TestSupport.capture(() -> backend.call(1, 0)));
                assertEquals("early outcomes and before failures skip the original", 0, backend.originalCalls.get());
                assertEquals(2, afterRuns.get());
                assertSame(before, TestSupport.capture(() -> backend.call(2, 0)));
                assertEquals("before failure skips after callbacks", 2, afterRuns.get());
                assertSame(after, TestSupport.capture(() -> backend.call(3, 0)));
                assertEquals(1, backend.originalCalls.get());
                assertSame(original, TestSupport.capture(() -> backend.call(4, 0)));
                assertTrue(diagnostics.events().isEmpty());
                backend.original = (self, args) -> { throw original; };
                assertSame("original wins when both fail", original, TestSupport.capture(() -> backend.call(5, 0)));
                assertEquals(1, diagnostics.events().size());
                assertSame(after, diagnostics.events().get(0).failure());
                assertEquals(3, backend.originalCalls.get());
                backend.assertTransport();
            } finally {
                env.close();
            }
        });
    }

    @Test public void aroundOutcomesMatchOrdinaryDispatch() throws Throwable {
        RuntimeException original = new IllegalStateException("original");
        RuntimeException callback = new IllegalArgumentException("around");
        onBothTransports(direct -> {
            ChainBackend backend = new ChainBackend(direct);
            HookEnvironment env = environment(backend, new BoundedDiagnosticsSink(4));
            backend.original = (self, args) -> {
                if ((Integer) args[0] == 1) throw original;
                return 7;
            };
            AtomicReference<Throwable> second = new AtomicReference<Throwable>();
            try {
                TestSupport.installOrFail(env, TestSupport.spec("direct.around.outcomes", ADD).around(i -> {
                    int mode = (Integer) i.getArgument(0);
                    if (mode == 3) return;
                    CallOutcome outcome = i.proceed();
                    if (mode == 0) second.set(TestSupport.capture(i::proceed));
                    if (mode == 1) assertSame(original, outcome.throwable());
                    if (mode == 2) throw callback;
                    if (mode == 4) i.setEarlyThrowable(callback);
                }).build());
                assertEquals(7, backend.call(0, 0));
                assertTrue(second.get() instanceof HookContractException);
                assertSame(original, TestSupport.capture(() -> backend.call(1, 0)));
                assertSame(callback, TestSupport.capture(() -> backend.call(2, 0)));
                assertEquals("failure after proceed never replays", 3, backend.originalCalls.get());
                assertTrue(TestSupport.capture(() -> backend.call(3, 0)) instanceof HookContractException);
                assertEquals("no implicit original call", 3, backend.originalCalls.get());
                assertSame(callback, TestSupport.capture(() -> backend.call(4, 0)));
                assertEquals(4, backend.originalCalls.get());
                backend.assertTransport();
            } finally {
                env.close();
            }
        });
    }

    @SuppressWarnings("removal")
    @Test public void fatalEventsPropagateWithoutReplayOnBothTransports() throws Throwable {
        ThreadDeath caught = new ThreadDeath();
        OutOfMemoryError downstream = new OutOfMemoryError("downstream");
        StackOverflowError afterFatal = new StackOverflowError("after");
        onBothTransports(direct -> {
            ChainBackend backend = new ChainBackend(direct);
            HookEnvironment env = environment(backend, new BoundedDiagnosticsSink(4));
            AtomicInteger afterRuns = new AtomicInteger();
            backend.original = (self, args) -> {
                if ((Integer) args[0] == 1) throw downstream;
                return 7;
            };
            try {
                HookRegistration first = TestSupport.installOrFail(env, TestSupport.spec("direct.fatal", ADD)
                        .access(ParameterAccess.READ_ARGS)
                        .before(i -> {
                            if ((Integer) ((ReadArgsInvocation) i).getArgument(0) == 0) {
                                try { i.setEarlyThrowable(caught); } catch (ThreadDeath deliberatelyCaught) { }
                            }
                        })
                        .after((i, r) -> {
                            afterRuns.incrementAndGet();
                            if ((Integer) ((ReadArgsInvocation) i).getArgument(0) == 2) throw afterFatal;
                        }).build());
                assertSame(caught, TestSupport.capture(() -> backend.call(0, 0)));
                assertEquals(0, backend.originalCalls.get());
                assertSame(downstream, TestSupport.capture(() -> backend.call(1, 0)));
                assertEquals("fatal downstream skips after callbacks", 0, afterRuns.get());
                assertSame(afterFatal, TestSupport.capture(() -> backend.call(2, 0)));
                assertEquals(2, backend.originalCalls.get());
                first.close();

                TestSupport.installOrFail(env, TestSupport.spec("direct.fatal.around", ADD).around(i -> {
                    try { i.proceed(); } catch (OutOfMemoryError deliberatelyCaught) { }
                    i.setEarlyResult(1);
                }).build());
                backend.original = (self, args) -> { throw downstream; };
                assertSame(downstream, TestSupport.capture(() -> backend.call(5, 0)));
                backend.assertTransport();
            } finally {
                env.close();
            }
        });
    }

    @Test public void applicationValuesEqualToTransportMarkersRoundTrip() throws Throwable {
        onBothTransports(direct -> {
            ChainBackend backend = new ChainBackend(direct);
            Object escaped = DirectAfterDispatchChannel.escape(DirectAfterDispatchChannel.NOT_ENTERED);
            Object[] values = {null, new Object(), backend.marker, DirectAfterDispatchChannel.NOT_ENTERED, escaped,
                    DirectAfterDispatchChannel.escape(escaped), DispatchResult.passthrough(),
                    DispatchResult.returned("wrapped"), DispatchResult.threw(new RuntimeException("value"))};
            AtomicReference<Object> current = new AtomicReference<Object>();
            List<HookSpec> specs = Arrays.asList(
                    TestSupport.spec("direct.value.early", OBJECT).before(i -> i.setEarlyResult(current.get())).build(),
                    TestSupport.spec("direct.value.before", OBJECT).before(i -> { }).build(),
                    TestSupport.spec("direct.value.before.after", OBJECT).before(i -> { }).after((i, r) -> { }).build(),
                    TestSupport.spec("direct.value.after.replace", OBJECT).before(i -> { })
                            .after((i, r) -> r.setResult(current.get())).build(),
                    TestSupport.spec("direct.value.around", OBJECT).around(i -> {
                        i.proceed();
                        i.setEarlyResult(current.get());
                    }).build(),
                    TestSupport.spec("direct.value.around.proceed", OBJECT).around(i -> i.proceed()).build());
            for (HookSpec spec : specs) {
                HookEnvironment env = environment(backend, new BoundedDiagnosticsSink(4));
                try {
                    TestSupport.installOrFail(env, spec);
                    for (Object value : values) {
                        current.set(value);
                        backend.original = (self, args) -> current.get();
                        assertSame(spec.id() + " / " + value, value, backend.call(true));
                    }
                } finally {
                    env.close();
                }
            }
            backend.assertTransport();
        });
    }

    @Test public void closedRegistrationPassesThroughExactlyOnceWithoutCallbacks() throws Throwable {
        onBothTransports(direct -> {
            ChainBackend backend = new ChainBackend(direct);
            HookEnvironment env = environment(backend, new BoundedDiagnosticsSink(4));
            AtomicInteger callbacks = new AtomicInteger();
            backend.original = (self, args) -> 7;
            try {
                HookRegistration first = TestSupport.installOrFail(env, TestSupport.spec("direct.closed", ADD)
                        .before(i -> callbacks.incrementAndGet())
                        .after((i, r) -> callbacks.incrementAndGet()).build());
                HookRegistration around = TestSupport.installOrFail(env, TestSupport.spec("direct.closed.around", ADD)
                        .around(i -> { callbacks.incrementAndGet(); i.proceed(); }).build());
                // Keep the framework entries but close the SDK registrations, as a racing call would see.
                List<ChainBackend.Hook> retained = new ArrayList<ChainBackend.Hook>(backend.hooks);
                first.close();
                around.close();
                backend.hooks.addAll(0, retained);
                assertEquals(7, backend.call(1, 2));
                assertEquals(1, backend.originalCalls.get());
                assertEquals(0, callbacks.get());
                backend.assertTransport();
            } finally {
                env.close();
            }
        });
    }

    @Test public void reentrantCallsGetIndependentFrames() throws Throwable {
        onBothTransports(direct -> {
            ChainBackend backend = new ChainBackend(direct);
            HookEnvironment env = environment(backend, new BoundedDiagnosticsSink(4));
            List<String> events = new ArrayList<String>();
            backend.original = (self, args) -> {
                int depth = (Integer) args[0];
                return depth == 0 ? 1 : (Integer) backend.call(depth - 1, 0) + 1;
            };
            try {
                TestSupport.installOrFail(env, TestSupport.spec("direct.reentry", ADD).access(ParameterAccess.READ_ARGS)
                        .before(i -> events.add("before:" + ((ReadArgsInvocation) i).getArgument(0)))
                        .after((i, r) -> {
                            events.add("after:" + ((ReadArgsInvocation) i).getArgument(0));
                            r.setResult((Integer) r.getResult() * 10);
                        }).build());
                assertEquals(110, backend.call(1, 0));
                assertEquals(Arrays.asList("before:1", "before:0", "after:0", "after:1"), events);
                assertEquals(2, backend.originalCalls.get());
                backend.assertTransport();
            } finally {
                env.close();
            }
        });
    }

    @Test public void closeDuringDownstreamKeepsTheAdmittedLease() throws Throwable {
        onBothTransports(direct -> {
            ChainBackend backend = new ChainBackend(direct);
            HookEnvironment env = environment(backend, new BoundedDiagnosticsSink(4));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            backend.original = (self, args) -> {
                if ((Integer) args[0] == 1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("downstream not released");
                }
                return 7;
            };
            AtomicReference<Object> value = new AtomicReference<Object>();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            HookRegistration registration = TestSupport.installOrFail(env, TestSupport.spec("direct.inflight", ADD)
                    .access(ParameterAccess.READ_ARGS)
                    .before(i -> ((ReadArgsInvocation) i).getArgument(0))
                    .after((i, r) -> r.setResult(70)).build());
            List<ChainBackend.Hook> retained = new ArrayList<ChainBackend.Hook>(backend.hooks);
            Thread worker = new Thread(() -> {
                try { value.set(backend.call(1, 0)); } catch (Throwable error) { failure.set(error); }
            }, "direct-inflight");
            worker.setDaemon(true);
            try {
                worker.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                CloseReport report = registration.close();
                assertTrue(report.newCallbacksRejected());
                assertEquals(1, report.inFlightAtClose());
                backend.hooks.addAll(retained);
                assertEquals("retired channel passes through", 7, backend.call(0, 0));
            } finally {
                release.countDown();
                worker.join(5000);
                env.close();
            }
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(70, value.get());
            assertEquals(2, backend.originalCalls.get());
            backend.assertTransport();
        });
    }
}
