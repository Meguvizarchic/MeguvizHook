package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.core.BoundedDiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A later after callback may not erase an earlier callback failure. */
public class MultipleAfterFailureTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);

    @Test
    public void afterFailureAfterSuccessSurvivesSubsequentResultWrite() {
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(ADD, (receiver, args) -> 3);
        BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
        HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
        RuntimeException afterFailure = new RuntimeException("after");
        List<String> order = new ArrayList<String>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("after.multi.success", ADD)
                    .after((invocation, result) -> {
                        order.add("later");
                        result.setResult(42);
                    })
                    .after((invocation, result) -> {
                        order.add("failure");
                        throw afterFailure;
                    }).build());
            assertSame(afterFailure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(Arrays.asList("failure", "later"), order);
            assertEquals(1, backend.originalCallCount(ADD));
            assertTrue(diagnostics.events().isEmpty());
        } finally {
            environment.close();
        }
    }

    @Test
    public void originalFailureSurvivesAfterFailureAndSubsequentResultWrite() {
        FakeBackend backend = FakeBackend.create();
        IllegalStateException originalFailure = new IllegalStateException("original");
        RuntimeException afterFailure = new RuntimeException("after");
        backend.onTarget(ADD, (receiver, args) -> { throw originalFailure; });
        BoundedDiagnosticsSink diagnostics = new BoundedDiagnosticsSink(4);
        HookEnvironment environment = HookEnvironment.create(backend, new ReflectiveMemberResolver(), diagnostics);
        List<String> order = new ArrayList<String>();
        try {
            TestSupport.installOrFail(environment, TestSupport.spec("after.multi.original", ADD)
                    .after((invocation, result) -> {
                        order.add("later");
                        result.setResult(42);
                    })
                    .after((invocation, result) -> {
                        order.add("failure");
                        throw afterFailure;
                    }).build());
            assertSame(originalFailure, TestSupport.capture(() -> backend.call(ADD, null, 1, 2)));
            assertEquals(Arrays.asList("failure", "later"), order);
            assertEquals(1, backend.originalCallCount(ADD));
            assertEquals(1, diagnostics.events().size());
            assertSame(afterFailure, diagnostics.events().get(0).failure());
        } finally {
            environment.close();
        }
    }
}
