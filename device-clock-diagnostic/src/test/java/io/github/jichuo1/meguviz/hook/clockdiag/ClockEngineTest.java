package io.github.jichuo1.meguviz.hook.clockdiag;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ClockEngineTest {
    @Test
    public void equalAndAllZeroReadingsAreValidAndRemainUnmodified() {
        List<String> events = new ArrayList<>();
        String result = ClockEngine.run(new ScriptedClock(new long[]{0, 0, 0, 0},
                new long[]{0, 0, 0, 0}), capture(events), 1, 1);
        assertEquals("", result);
        assertEquals(Arrays.asList("warmup:0:0:0:0:0:", "sample:0:0:0:0:0:"), events);
    }

    @Test
    public void unavailableCpuIsRecordedAndStopsWithoutSkippingToSamples() {
        List<String> events = new ArrayList<>();
        String result = ClockEngine.run(new ScriptedClock(new long[]{-1, 5},
                new long[]{10, 11}), capture(events), 1, 1);
        assertEquals("cpu_unavailable", result);
        assertEquals(Arrays.asList("warmup:0:-1:5:10:11:cpu_unavailable"), events);
    }

    @Test
    public void backwardCpuBetweenReadingsOrBetweenIntervalsFails() {
        List<String> events = new ArrayList<>();
        assertEquals("cpu_backward", ClockEngine.run(new ScriptedClock(new long[]{9, 8},
                new long[]{10, 11}), capture(events), 0, 1));
        assertEquals(Arrays.asList("sample:0:9:8:10:11:cpu_backward"), events);

        events.clear();
        assertEquals("cpu_backward", ClockEngine.run(new ScriptedClock(new long[]{5, 6, 5, 7},
                new long[]{10, 11, 12, 13}), capture(events), 1, 1));
        assertEquals(Arrays.asList("warmup:0:5:6:10:11:", "sample:0:5:7:12:13:cpu_backward"), events);
    }

    @Test
    public void wallClockUnavailableAndBackwardAreDistinctFailures() {
        List<String> events = new ArrayList<>();
        assertEquals("wall_unavailable", ClockEngine.run(new ScriptedClock(new long[]{0, 0},
                new long[]{-1, 1}), capture(events), 0, 1));
        assertEquals("sample:0:0:0:-1:1:wall_unavailable", events.get(0));

        events.clear();
        assertEquals("wall_backward", ClockEngine.run(new ScriptedClock(new long[]{0, 0, 0, 0},
                new long[]{5, 7, 6, 8}), capture(events), 1, 1));
        assertEquals("sample:0:0:0:6:8:wall_backward", events.get(1));
    }

    @Test
    public void invalidConditionsUseDocumentedPrecedence() {
        List<String> events = new ArrayList<>();
        assertEquals("cpu_unavailable", ClockEngine.run(new ScriptedClock(new long[]{-1, -1},
                new long[]{-1, -1}), capture(events), 0, 1));
        assertEquals("sample:0:-1:-1:-1:-1:cpu_unavailable", events.get(0));
    }

    @Test
    public void onlyCanonicalUuidAndBoundedCountsAreAccepted() {
        String runId = "6abefc46-11fe-4f53-8aa6-fba235cb679c";
        assertNotNull(ClockRequest.create(runId, 0, 1));
        assertNotNull(ClockRequest.create(runId, 100, 200));
        assertNull(ClockRequest.create(runId.toUpperCase(), 0, 1));
        assertNull(ClockRequest.create(runId, -1, 1));
        assertNull(ClockRequest.create(runId, 101, 1));
        assertNull(ClockRequest.create(runId, 0, 0));
        assertNull(ClockRequest.create(runId, 0, 201));
    }

    private static ClockEngine.Sink capture(List<String> events) {
        return (phase, index, cpuBefore, cpuAfter, wallBefore, wallAfter, reason) ->
                events.add(phase + ":" + index + ":" + cpuBefore + ":" + cpuAfter + ":"
                        + wallBefore + ":" + wallAfter + ":" + reason);
    }

    private static final class ScriptedClock implements ClockEngine.Clock {
        private final long[] cpu;
        private final long[] wall;
        private int cpuIndex;
        private int wallIndex;

        ScriptedClock(long[] cpu, long[] wall) {
            this.cpu = cpu;
            this.wall = wall;
        }

        @Override public long cpuNanos() { return cpu[cpuIndex++]; }
        @Override public long elapsedNanos() { return wall[wallIndex++]; }
    }
}
