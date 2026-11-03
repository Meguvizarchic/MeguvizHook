package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.EntrySnapshotInvocation;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.InvocationArguments;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * MUTABLE_ARGS and AROUND obtain the backend array on the first slot access, at most once per frame.
 * Downstream receives that array if it was obtained before downstream, otherwise null ("keep
 * incoming"), and every stage still reads the slots downstream used (ADR-0001 §2). READ_ARGS and
 * ENTRY_SNAPSHOT keep materializing at entry.
 */
public final class LazyArgumentMaterializationTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable APPEND = TestSupport.method("append", StringBuilder.class, String.class);

    /** Records how the core drove the SPI; models a backend whose arguments() copies incoming slots. */
    private static final class RecordingInvocation implements BackendInvocation {
        final Object[] incoming;
        final List<String> events = new ArrayList<String>();
        Object[] lastMaterialized;
        RuntimeException argumentsFailure;
        Error argumentsError;

        RecordingInvocation(Object... incoming) {
            this.incoming = incoming;
        }

        @Override public Executable target() { return ADD; }
        @Override public Object thisObject() { return null; }

        @Override public Object[] arguments() {
            events.add("arguments");
            if (argumentsError != null) throw argumentsError;
            if (argumentsFailure != null) throw argumentsFailure;
            lastMaterialized = incoming.clone();
            return lastMaterialized;
        }

        @Override public List<?> readOnlyArguments() { throw new AssertionError("unexpected view"); }

        @Override public Object proceed(Object[] supplied) {
            if (supplied == null) {
                events.add("proceed()");
                supplied = incoming;
            } else {
                events.add(supplied == lastMaterialized ? "proceed(materialized)" : "proceed(other)");
            }
            return (Integer) supplied[0] + (Integer) supplied[1];
        }
    }

    private static final class CapturingBackend implements HookBackend {
        final FakeBackend delegate = FakeBackend.create();
        DispatchChannel channel;
        @Override public String id() { return delegate.id(); }
        @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
        @Override public BackendContext context() { return delegate.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel value) throws BackendInstallException {
            channel = value;
            return delegate.install(plan, value);
        }
    }

    private static Object dispatch(HookSpec spec, RecordingInvocation invocation) throws Throwable {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            TestSupport.installOrFail(env, spec);
            DispatchResult result = backend.channel.dispatch(invocation);
            if (result.kind() == DispatchResult.Kind.THREW) throw result.throwable();
            assertEquals(DispatchResult.Kind.RETURNED, result.kind());
            return result.value();
        } finally {
            env.close();
        }
    }

    @Test public void untouchedMutableSlotsAreNeverMaterializedAndDownstreamKeepsIncomingArguments() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        Object value = dispatch(TestSupport.spec("lazy.untouched", ADD).access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> {
                    ReadArgsInvocation args = (ReadArgsInvocation) i;
                    // Count and types come from the plan, not from the backend array.
                    assertEquals(2, args.argumentCount());
                    assertEquals(int.class, args.argumentType(1));
                })
                .after((i, result) -> assertEquals(7, result.getResult())).build(), invocation);
        assertEquals(7, value);
        assertEquals(java.util.Collections.singletonList("proceed()"), invocation.events);
    }

    @Test public void readArgsStaysEagerAndPassesItsArray() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        assertEquals(7, dispatch(TestSupport.spec("lazy.read.eager", ADD).access(ParameterAccess.READ_ARGS)
                .before(i -> assertEquals(2, ((ReadArgsInvocation) i).argumentCount())).build(), invocation));
        assertEquals(java.util.Arrays.asList("arguments", "proceed(materialized)"), invocation.events);
    }

    @Test public void aroundThatOnlyProceedsNeverMaterializes() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        assertEquals(7, dispatch(TestSupport.spec("lazy.around", ADD).around(i -> i.proceed()).build(), invocation));
        assertEquals(java.util.Collections.singletonList("proceed()"), invocation.events);
    }

    @Test public void readBeforeDownstreamPassesTheSameArrayAndIsMaterializedOnce() throws Throwable {
        for (ParameterAccess access : new ParameterAccess[]{ParameterAccess.READ_ARGS, ParameterAccess.MUTABLE_ARGS}) {
            RecordingInvocation invocation = new RecordingInvocation(3, 4);
            Object value = dispatch(TestSupport.spec("lazy.read." + access, ADD).access(access)
                    .before(i -> assertEquals(3, ((ReadArgsInvocation) i).getArgument(0)))
                    .before(i -> assertEquals(4, ((ReadArgsInvocation) i).getArgument(1)))
                    .after((i, result) -> assertEquals(3, ((ReadArgsInvocation) i).getArgument(0))).build(), invocation);
            assertEquals(7, value);
            assertEquals(java.util.Arrays.asList("arguments", "proceed(materialized)"), invocation.events);
        }
    }

    @Test public void writesMaterializeAndReachDownstream() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        assertEquals(14, dispatch(TestSupport.spec("lazy.write", ADD).access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> ((MutableArgsInvocation) i).setArgument(0, 10)).build(), invocation));
        assertEquals(java.util.Arrays.asList("arguments", "proceed(materialized)"), invocation.events);
        assertArrayEquals("incoming slots are not written", new Object[]{3, 4}, invocation.incoming);

        RecordingInvocation around = new RecordingInvocation(3, 4);
        assertEquals(5, dispatch(TestSupport.spec("lazy.around.write", ADD).around(i -> {
            i.setArgument(1, 2);
            i.proceed();
        }).build(), around));
        assertEquals(java.util.Arrays.asList("arguments", "proceed(materialized)"), around.events);
    }

    @Test public void invalidWriteIsRejectedBeforeMaterialization() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        assertEquals(7, dispatch(TestSupport.spec("lazy.write.invalid", ADD).access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> failure.set(TestSupport.capture(() -> ((MutableArgsInvocation) i).setArgument(2, 1))))
                .build(), invocation));
        assertTrue(failure.get() instanceof IndexOutOfBoundsException);
        assertEquals(java.util.Collections.singletonList("proceed()"), invocation.events);
    }

    @Test public void aroundReadAfterProceedObtainsSlotsAfterDownstream() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        assertEquals(7, dispatch(TestSupport.spec("lazy.around.after", ADD).around(i -> {
            i.proceed();
            assertArrayEquals(new Object[]{3, 4}, InvocationArguments.copyOf(i));
        }).build(), invocation));
        assertEquals(java.util.Arrays.asList("proceed()", "arguments"), invocation.events);
    }

    @Test public void ordinaryMaterializationFailureSurfacesInTheCallbackThatReadSlots() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        IllegalStateException broken = new IllegalStateException("backend arguments failed");
        invocation.argumentsFailure = broken;
        AtomicReference<Throwable> seen = new AtomicReference<Throwable>();
        assertEquals(7, dispatch(TestSupport.spec("lazy.failure.caught", ADD).access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> seen.set(TestSupport.capture(() -> ((ReadArgsInvocation) i).getArgument(0))))
                .build(), invocation));
        assertSame(broken, seen.get());

        RecordingInvocation uncaught = new RecordingInvocation(3, 4);
        uncaught.argumentsFailure = broken;
        assertSame(broken, TestSupport.capture(() -> dispatch(TestSupport.spec("lazy.failure", ADD)
                .access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> ((ReadArgsInvocation) i).getArgument(0)).build(), uncaught)));
        assertFalse("a failed before keeps downstream at zero", uncaught.events.contains("proceed()"));
    }

    @Test public void fatalMaterializationFailureStaysFatalEvenWhenCaught() {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        OutOfMemoryError fatal = new OutOfMemoryError("arguments");
        invocation.argumentsError = fatal;
        Throwable observed = TestSupport.capture(() -> dispatch(TestSupport.spec("lazy.fatal", ADD)
                .access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> {
                    try { ((ReadArgsInvocation) i).getArgument(0); } catch (OutOfMemoryError deliberatelyCaught) { }
                }).build(), invocation));
        assertSame(fatal, observed);
        assertEquals(java.util.Collections.singletonList("arguments"), invocation.events);
    }

    @Test public void entrySnapshotStaysEagerAndPassesTheLiveArray() throws Throwable {
        RecordingInvocation invocation = new RecordingInvocation(3, 4);
        assertEquals(7, dispatch(TestSupport.spec("lazy.snapshot", ADD).access(ParameterAccess.ENTRY_SNAPSHOT)
                .before(i -> {
                    EntrySnapshotInvocation snapshot = (EntrySnapshotInvocation) i;
                    Object[] copy = snapshot.entrySnapshot();
                    assertNotSame(copy, snapshot.entrySnapshot());
                    assertArrayEquals(new Object[]{3, 4}, copy);
                }).build(), invocation));
        assertEquals(java.util.Arrays.asList("arguments", "proceed(materialized)"), invocation.events);
    }

    @Test public void afterSeesDownstreamSlotWritesWithOrWithoutEarlierReads() throws Throwable {
        for (boolean readBefore : new boolean[]{false, true}) {
            FakeBackend backend = FakeBackend.create();
            backend.onTarget(APPEND, (self, args) -> {
                args[1] = "downstream";
                return null;
            });
            HookEnvironment env = HookEnvironment.create(backend);
            AtomicReference<Object> afterSlot = new AtomicReference<Object>();
            try {
                HookSpec.Builder spec = TestSupport.spec("lazy.downstream." + readBefore, APPEND)
                        .access(ParameterAccess.MUTABLE_ARGS)
                        .after((i, result) -> afterSlot.set(((ReadArgsInvocation) i).getArgument(1)));
                if (readBefore) spec.before(i -> assertEquals("entry", ((ReadArgsInvocation) i).getArgument(1)));
                TestSupport.installOrFail(env, spec.build());
                backend.call(APPEND, null, new StringBuilder(), "entry");
                assertEquals("read before downstream: " + readBefore, "downstream", afterSlot.get());
                assertEquals(1, backend.argumentMaterializationCount());
            } finally {
                env.close();
            }
        }
    }
}
