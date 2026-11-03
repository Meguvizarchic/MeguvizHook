package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Compatibility checks for mutable arguments supplied by existing backend implementations. */
public final class MutableArgsCopyOnWriteTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

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

    @Test public void noWritePassesBackendArrayAndAfterSeesDownstreamMutation() {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        Object[] arguments = {3, 4};
        AtomicInteger materializations = new AtomicInteger();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("mutable.after.downstream", ADD)
                    .access(ParameterAccess.MUTABLE_ARGS)
                    .before(i -> assertEquals(3, ((ReadArgsInvocation) i).getArgument(0)))
                    .after((i, result) -> assertEquals(9, ((ReadArgsInvocation) i).getArgument(0)))
                    .build());
            DispatchResult result = backend.channel.dispatch(new BackendInvocation() {
                @Override public Executable target() { return ADD; }
                @Override public Object thisObject() { return null; }
                @Override public Object[] arguments() { materializations.incrementAndGet(); return arguments; }
                @Override public List<?> readOnlyArguments() { throw new AssertionError("unexpected read-only view"); }
                @Override public Object proceed(Object[] supplied) {
                    assertSame(arguments, supplied);
                    assertArrayEquals(new Object[]{3, 4}, supplied);
                    int value = (Integer) supplied[0] + (Integer) supplied[1];
                    supplied[0] = 9;
                    return value;
                }
            });
            assertEquals(DispatchResult.Kind.RETURNED, result.kind());
            assertEquals(7, result.value());
            assertEquals(1, materializations.get());
        } finally { env.close(); }
    }

    @Test public void beforeOnlyRetainsSharedArrayAndPassesItDownstream() {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        Object[] shared = {3, 4};
        AtomicInteger materializations = new AtomicInteger();
        try {
            TestSupport.installOrFail(env, TestSupport.spec("mutable.before.shared", ADD)
                    .access(ParameterAccess.MUTABLE_ARGS)
                    .before(i -> {
                        ReadArgsInvocation args = (ReadArgsInvocation) i;
                        assertEquals(3, args.getArgument(0));
                        shared[0] = 9;
                        assertEquals(9, args.getArgument(0));
                    }).build());
            DispatchResult result = backend.channel.dispatch(new BackendInvocation() {
                @Override public Executable target() { return ADD; }
                @Override public Object thisObject() { return null; }
                @Override public Object[] arguments() { materializations.incrementAndGet(); return shared; }
                @Override public Object proceed(Object[] supplied) {
                    assertSame(shared, supplied);
                    assertArrayEquals(new Object[]{9, 4}, supplied);
                    return (Integer) supplied[0] + (Integer) supplied[1];
                }
            });
            assertEquals(DispatchResult.Kind.RETURNED, result.kind());
            assertEquals(13, result.value());
            assertEquals(1, materializations.get());
        } finally { env.close(); }
    }

    @Test public void writesAreSharedAcrossCallbacksAndDownstream() {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        Object[] incoming = {3, 4};
        try {
            TestSupport.installOrFail(env, TestSupport.spec("mutable.shared", ADD)
                    .access(ParameterAccess.MUTABLE_ARGS)
                    .before(i -> ((MutableArgsInvocation) i).setArgument(0, 10))
                    .before(i -> {
                        MutableArgsInvocation args = (MutableArgsInvocation) i;
                        assertEquals(10, args.getArgument(0));
                        args.setArgument(1, 20);
                    })
                    .after((i, result) -> {
                        ReadArgsInvocation args = (ReadArgsInvocation) i;
                        assertEquals(11, args.getArgument(0));
                        assertEquals(20, args.getArgument(1));
                    }).build());
            DispatchResult result = backend.channel.dispatch(new BackendInvocation() {
                @Override public Executable target() { return ADD; }
                @Override public Object thisObject() { return null; }
                @Override public Object[] arguments() { return incoming.clone(); }
                @Override public Object proceed(Object[] supplied) {
                    assertNotSame(incoming, supplied);
                    assertArrayEquals(new Object[]{10, 20}, supplied);
                    assertArrayEquals(new Object[]{3, 4}, incoming);
                    supplied[0] = 11;
                    return 30;
                }
            });
            assertEquals(DispatchResult.Kind.RETURNED, result.kind());
            assertEquals(30, result.value());
        } finally { env.close(); }
    }

    @Test public void singleAfterObservesTheArrayPassedToDownstream() {
        CapturingBackend backend = new CapturingBackend();
        HookEnvironment env = HookEnvironment.create(backend);
        Object[] arguments = {3, 4};
        try {
            TestSupport.installOrFail(env, TestSupport.spec("mutable.single.after", ADD)
                    .access(ParameterAccess.MUTABLE_ARGS)
                    .after((i, result) -> assertEquals(9, ((ReadArgsInvocation) i).getArgument(0)))
                    .build());
            DispatchResult result = backend.channel.dispatch(new BackendInvocation() {
                @Override public Executable target() { return ADD; }
                @Override public Object thisObject() { return null; }
                @Override public Object[] arguments() { return arguments; }
                @Override public Object proceed(Object[] supplied) {
                    assertSame(arguments, supplied);
                    supplied[0] = 9;
                    return 7;
                }
            });
            assertEquals(DispatchResult.Kind.RETURNED, result.kind());
            assertEquals(7, result.value());
        } finally { env.close(); }
    }
}
