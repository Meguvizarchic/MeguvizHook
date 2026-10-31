package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.EntrySnapshotInvocation;
import io.github.jichuo1.meguviz.hook.api.HookContractException;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Parameter shapes: NO_ARGS/READ_ARGS/ENTRY_SNAPSHOT/MUTABLE_ARGS plus type and void checks. */
public class ParameterShapeTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable CONCAT = TestSupport.method("concat", String.class, String.class);
    private static final Executable APPEND = TestSupport.method("append", StringBuilder.class, String.class);
    private static final Executable NO_ARGS = TestSupport.method("noArguments");

    private FakeBackend backend;
    private HookEnvironment environment;

    @Before
    public void setUp() {
        backend = FakeBackend.create();
        environment = HookEnvironment.create(backend);
    }

    @Test
    public void noArgsNeverMaterializesArguments() {
        backend.onTarget(NO_ARGS, (thisObject, args) -> 42L);
        TestSupport.installOrFail(environment, TestSupport.spec("shape.noargs", NO_ARGS)
                .before(invocation -> {
                })
                .after((invocation, result) -> {
                })
                .build());
        try {
            assertEquals(42L, backend.call(NO_ARGS, null));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals("NO_ARGS must not ask the backend for an argument array",
                0, backend.argumentMaterializationCount());
    }

    @Test
    public void noArgsRejectsArgumentReads() {
        backend.onTarget(NO_ARGS, (thisObject, args) -> 42L);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        TestSupport.installOrFail(environment, TestSupport.spec("shape.noargs.reject", NO_ARGS)
                .before(invocation -> failure.set(TestSupport.capture(() -> ((ReadArgsInvocation) invocation).getArgument(0))))
                .build());
        try {
            backend.call(NO_ARGS, null);
        } catch (Throwable error) {
            throw new AssertionError(error);
        }
        assertTrue(failure.get() instanceof HookContractException);
    }

    @Test
    public void readArgsExposesSlotsAndTypes() {
        backend.onTarget(ADD, (thisObject, args) -> 0);
        TestSupport.installOrFail(environment, TestSupport.spec("shape.read", ADD)
                .access(ParameterAccess.READ_ARGS)
                .before(invocation -> {
                    ReadArgsInvocation args = (ReadArgsInvocation) invocation;
                    assertEquals(2, args.argumentCount());
                    assertEquals(int.class, args.argumentType(0));
                    assertEquals(3, args.getArgument(0));
                    assertEquals(4, args.getArgument(1));
                })
                .build());
        try {
            backend.call(ADD, null, 3, 4);
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals(1, backend.argumentMaterializationCount());
    }

    @Test
    public void entrySnapshotKeepsEntrySlotsButSharesObjects() {
        StringBuilder sink = new StringBuilder("a");
        backend.onTarget(APPEND, (thisObject, args) -> {
            // Mutate the live slot and the shared object to prove "shallow slot snapshot".
            args[1] = "live";
            ((StringBuilder) args[0]).append("-mutated");
            return null;
        });
        AtomicReference<Object> entrySlot = new AtomicReference<Object>();
        AtomicReference<Object> liveSlot = new AtomicReference<Object>();
        AtomicReference<Object> sharedObject = new AtomicReference<Object>();
        TestSupport.installOrFail(environment, TestSupport.spec("shape.snapshot", APPEND)
                .access(ParameterAccess.ENTRY_SNAPSHOT)
                .before(invocation -> {
                    EntrySnapshotInvocation snapshot = (EntrySnapshotInvocation) invocation;
                    assertEquals("b", snapshot.getEntryArgument(1));
                    assertSame(sink, snapshot.entrySnapshot()[0]);
                })
                .after((invocation, result) -> {
                    EntrySnapshotInvocation snapshot = (EntrySnapshotInvocation) invocation;
                    entrySlot.set(snapshot.getEntryArgument(1));
                    liveSlot.set(snapshot.getArgument(1));
                    sharedObject.set(snapshot.getEntryArgument(0));
                })
                .build());
        try {
            backend.call(APPEND, null, sink, "b");
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals("snapshot slot must keep the entry value", "b", entrySlot.get());
        assertEquals("live slot reflects the downstream write", "live", liveSlot.get());
        assertEquals("objects are shared, not deep-copied", "a-mutated", sharedObject.get().toString());
    }

    @Test
    public void entrySnapshotShapeIsRequiredForSnapshotAccess() {
        backend.onTarget(ADD, (thisObject, args) -> 0);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        TestSupport.installOrFail(environment, TestSupport.spec("shape.snapshot.reject", ADD)
                .access(ParameterAccess.READ_ARGS)
                .before(invocation -> failure.set(TestSupport.capture(() -> ((EntrySnapshotInvocation) invocation).entrySnapshot())))
                .build());
        try {
            backend.call(ADD, null, 1, 2);
        } catch (Throwable error) {
            throw new AssertionError(error);
        }
        assertTrue(failure.get() instanceof HookContractException);
    }

    @Test
    public void mutableArgsWriteReachesDownstream() {
        backend.onTarget(ADD, (thisObject, args) -> (Integer) args[0] + (Integer) args[1]);
        TestSupport.installOrFail(environment, TestSupport.spec("shape.mutable", ADD)
                .access(ParameterAccess.MUTABLE_ARGS)
                .before(invocation -> ((MutableArgsInvocation) invocation).setArgument(0, 10))
                .build());
        try {
            assertEquals(12, backend.call(ADD, null, 1, 2));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void argumentWritesAreTypeCheckedBeforeDownstreamRuns() {
        TestSupport.installOrFail(environment, TestSupport.spec("shape.mutable.typecheck", ADD)
                .access(ParameterAccess.MUTABLE_ARGS)
                // The validation failure propagates out of the callback instead of being caught by it.
                .before(invocation -> ((MutableArgsInvocation) invocation).setArgument(0, "not-an-int"))
                .build());
        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertTrue(observed instanceof IllegalArgumentException);
        assertEquals("a failed before callback must keep the original at zero executions",
                0, backend.originalCallCount(ADD));
    }

    @Test
    public void nullIsRejectedForPrimitiveArgument() {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        TestSupport.installOrFail(environment, TestSupport.spec("shape.mutable.null", ADD)
                .access(ParameterAccess.MUTABLE_ARGS)
                .before(invocation -> failure.set(TestSupport.capture(
                        () -> ((MutableArgsInvocation) invocation).setArgument(1, null))))
                .build());
        try {
            backend.call(ADD, null, 1, 2);
        } catch (Throwable error) {
            throw new AssertionError(error);
        }
        assertTrue(failure.get() instanceof IllegalArgumentException);
    }

    @Test
    public void voidTargetOnlyAcceptsNullResult() {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        TestSupport.installOrFail(environment, TestSupport.spec("shape.void", APPEND)
                .before(invocation -> failure.set(TestSupport.capture(() -> invocation.setEarlyResult("value"))))
                .build());
        try {
            backend.call(APPEND, null, new StringBuilder(), "x");
        } catch (Throwable error) {
            throw new AssertionError(error);
        }
        assertTrue(failure.get() instanceof IllegalArgumentException);
    }

    @Test
    public void primitiveResultSlotRejectsNull() {
        backend.onTarget(ADD, (thisObject, args) -> 5);
        TestSupport.installOrFail(environment, TestSupport.spec("shape.result.null", ADD)
                .after((invocation, result) -> result.setResult(null))
                .build());
        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertTrue(observed instanceof IllegalArgumentException);
        assertEquals(1, backend.originalCallCount(ADD));
    }

    @Test
    public void nullableReferenceResultIsAllowed() {
        Executable nullable = TestSupport.method("nullableResult", boolean.class);
        backend.onTarget(nullable, (thisObject, args) -> null);
        TestSupport.installOrFail(environment, TestSupport.spec("shape.result.nullable", nullable)
                .after((invocation, result) -> assertNull(result.getResult()))
                .build());
        try {
            assertNull(backend.call(nullable, null, true));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void constructorTargetProceedsWithNullResult() {
        Executable constructor;
        try {
            constructor = io.github.jichuo1.meguviz.hook.testing.Fixtures.Constructed.class.getConstructor(int.class);
        } catch (NoSuchMethodException error) {
            throw new AssertionError(error);
        }
        backend.onTarget(constructor, (thisObject, args) -> null);
        TestSupport.installOrFail(environment, TestSupport.spec("shape.constructor", constructor)
                .around(invocation -> {
                    assertNull(invocation.thisObject());
                    assertEquals(io.github.jichuo1.meguviz.hook.api.CallOutcome.State.RETURNED,
                            invocation.proceed().state());
                })
                .build());
        try {
            assertNull(backend.call(constructor, null, 3));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        assertEquals(1, backend.originalCallCount(constructor));
    }

    @Test
    public void aroundSupportsArgumentRewriteThroughContinuation() {
        backend.onTarget(CONCAT, (thisObject, args) -> args[0].toString() + args[1].toString());
        TestSupport.installOrFail(environment, TestSupport.spec("shape.around.mutable", CONCAT)
                .around(invocation -> {
                    invocation.setArgument(0, "left-");
                    assertSame("left-", invocation.getArgument(0));
                    invocation.proceed();
                })
                .build());
        try {
            assertEquals("left-right", backend.call(CONCAT, null, "x", "right"));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void wrongResultTypeIsRejectedByAfter() {
        backend.onTarget(ADD, (thisObject, args) -> 5);
        TestSupport.installOrFail(environment, TestSupport.spec("shape.result.type", ADD)
                .after((invocation, result) -> result.setResult("not-an-int"))
                .build());
        Throwable observed = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
        assertTrue(observed instanceof IllegalArgumentException);
        assertEquals(1, backend.originalCallCount(ADD));
    }
}
