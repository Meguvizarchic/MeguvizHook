package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import static org.junit.Assert.*;

public class B10TargetTest {
    @Test public void targetRequiresPreparationAndCannotResetObservedCounts() {
        B10Target.State state = new B10Target.State();
        try { state.call(); fail("unprepared"); }
        catch (IllegalStateException expected) { assertEquals(0L, state.originalCount()); }
        state.prepare();
        assertEquals(42L, state.call());
        assertEquals(42L, state.call());
        assertEquals(2L, state.originalCount());
        try { state.prepare(); fail("cannot reset"); }
        catch (IllegalStateException expected) { assertEquals(2L, state.originalCount()); }
    }

    @Test public void publicTargetUsesSameOneShotState() {
        assertEquals(0L, B10Target.originalCount());
        B10Target.prepare();
        assertEquals(42L, B10Target.call());
        assertEquals(1L, B10Target.originalCount());
        try { B10Target.prepare(); fail("cannot prepare twice"); }
        catch (IllegalStateException expected) { assertEquals(1L, B10Target.originalCount()); }
    }
}
