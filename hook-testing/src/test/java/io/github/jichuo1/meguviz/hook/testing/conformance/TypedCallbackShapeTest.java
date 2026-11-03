package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.ArgsAfterCallback;
import io.github.jichuo1.meguviz.hook.api.ArgsBeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Typed builder callbacks choose a shape, narrow the invocation and keep ordinary semantics. */
public final class TypedCallbackShapeTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test public void beforeArgsSelectsReadOnlyViewAndReadsWithoutCast() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        List<Object> seen = new ArrayList<Object>();
        try {
            HookSpec spec = TestSupport.spec("typed.before.read", ADD)
                    .beforeArgs(invocation -> seen.add(invocation.getArgument(1)))
                    .build();
            assertEquals(ParameterAccess.READ_ARGS_VIEW, spec.access());
            TestSupport.installOrFail(env, spec);
            assertEquals(5, backend.call(ADD, null, 2, 3));
            assertEquals(1, seen.size());
            assertEquals(3, seen.get(0));
        } finally { env.close(); }
    }

    @Test public void beforeMutableArgsSelectsMutableShapeAndRewrites() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        try {
            HookSpec spec = TestSupport.spec("typed.before.mutable", ADD)
                    .beforeArgs(invocation -> assertEquals(1, invocation.getArgument(0)))
                    .beforeMutableArgs(invocation -> invocation.setArgument(0, 10))
                    .build();
            assertEquals("a mutable typed callback widens the implicit shape", ParameterAccess.MUTABLE_ARGS, spec.access());
            TestSupport.installOrFail(env, spec);
            assertEquals(12, backend.call(ADD, null, 1, 2));
            assertEquals(1, backend.originalCallCount(ADD));
        } finally { env.close(); }
    }

    @Test public void afterArgsReadsArgumentsAndResult() throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        try {
            TestSupport.installOrFail(env, TestSupport.spec("typed.after.read", ADD)
                    .afterArgs((invocation, result) ->
                            result.setResult((Integer) result.getResult() * 100 + (Integer) invocation.getArgument(0)))
                    .build());
            assertEquals(704, backend.call(ADD, null, 4, 3));
        } finally { env.close(); }
    }

    @Test public void explicitReadableShapeIsKept() {
        HookSpec entry = TestSupport.spec("typed.explicit.entry", ADD)
                .access(ParameterAccess.ENTRY_SNAPSHOT)
                .beforeArgs(invocation -> { })
                .build();
        assertEquals(ParameterAccess.ENTRY_SNAPSHOT, entry.access());
        HookSpec mutable = TestSupport.spec("typed.explicit.mutable", ADD)
                .beforeArgs(invocation -> { })
                .access(ParameterAccess.MUTABLE_ARGS)
                .build();
        assertEquals("order of the explicit choice does not matter", ParameterAccess.MUTABLE_ARGS, mutable.access());
    }

    @Test public void explicitIncompatibleShapeFailsAtBuild() {
        IllegalArgumentException noArgs = buildFails(TestSupport.spec("typed.bad.noargs", ADD)
                .access(ParameterAccess.NO_ARGS)
                .beforeArgs(invocation -> { }));
        assertTrue(noArgs.getMessage(), noArgs.getMessage().contains("readable"));
        IllegalArgumentException readOnly = buildFails(TestSupport.spec("typed.bad.readonly", ADD)
                .access(ParameterAccess.READ_ARGS_VIEW)
                .beforeMutableArgs(invocation -> { }));
        assertTrue(readOnly.getMessage(), readOnly.getMessage().contains("MUTABLE_ARGS"));
        buildFails(TestSupport.spec("typed.bad.around", ADD)
                .around(invocation -> invocation.proceed())
                .beforeArgs(invocation -> { }));
        buildFails(TestSupport.spec("typed.bad.constant", ADD)
                .replaceConstant(1)
                .beforeArgs(invocation -> { }));
    }

    @Test public void retainedTypedLambdaKeepsIdempotentReinstall() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        ArgsBeforeCallback before = invocation -> { };
        ArgsAfterCallback after = (invocation, result) -> { };
        try {
            HookSpec first = TestSupport.spec("typed.idempotent", ADD).beforeArgs(before).afterArgs(after).build();
            HookSpec same = TestSupport.spec("typed.idempotent", ADD).beforeArgs(before).afterArgs(after).build();
            HookSpec other = TestSupport.spec("typed.idempotent", ADD).beforeArgs(invocation -> { }).afterArgs(after).build();
            assertTrue(first.hasSameSemantics(same));
            assertFalse(first.hasSameSemantics(other));
            // An untyped callback with the same lambda shape is a different callback kind.
            HookSpec untyped = TestSupport.spec("typed.idempotent", ADD)
                    .access(ParameterAccess.READ_ARGS_VIEW).before(invocation -> { }).after((i, r) -> { }).build();
            assertFalse(first.hasSameSemantics(untyped));

            TestSupport.installOrFail(env, first);
            RegistrationReceipt again = env.install(same);
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, again.status());
            RegistrationReceipt conflict = env.install(other);
            assertEquals(RegistrationReceipt.Status.CONFLICT, conflict.status());
        } finally { env.close(); }
    }

    @Test public void typedCallbackExceptionsKeepIdentity() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        backend.onTarget(ADD, (self, args) -> (Integer) args[0] + (Integer) args[1]);
        IllegalStateException failure = new IllegalStateException("typed failure");
        try {
            TestSupport.installOrFail(env, TestSupport.spec("typed.failure", ADD)
                    .beforeArgs(invocation -> { throw failure; })
                    .build());
            Throwable thrown = TestSupport.capture(() -> backend.call(ADD, null, 1, 2));
            assertSame(failure, thrown);
            assertEquals("a failed before callback must not run the original", 0, backend.originalCallCount(ADD));
        } finally { env.close(); }
    }

    private static IllegalArgumentException buildFails(HookSpec.Builder builder) {
        try {
            builder.build();
        } catch (IllegalArgumentException expected) {
            return expected;
        }
        throw new AssertionError("build should fail");
    }
}
