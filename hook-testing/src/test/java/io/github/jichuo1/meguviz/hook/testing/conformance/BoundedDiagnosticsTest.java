package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.core.BoundedDiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Diagnostics must be bounded; overflow is counted, never silently grown. */
public class BoundedDiagnosticsTest {
    @Test
    public void capacityIsEnforcedAndOverflowCounted() {
        BoundedDiagnosticsSink sink = new BoundedDiagnosticsSink(2);
        for (int i = 0; i < 5; i++) {
            sink.record(new DiagnosticsSink.Event(
                    DiagnosticsSink.Event.Kind.AFTER_CALLBACK_FAILED_WHILE_ORIGINAL_FAILED,
                    HookId.of("diag." + i), 1L, new RuntimeException("failure-" + i), "test"));
        }
        assertEquals(2, sink.events().size());
        assertEquals(3L, sink.droppedCount());
    }
}
