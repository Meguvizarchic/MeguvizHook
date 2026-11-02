package io.github.jichuo1.meguviz.hook.benchmarkhost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/** Uses the same state as the public target, with no Hook framework or device. */
public class B09TargetTest {
    @Test public void preparedCasesHaveOneStableSentinelAndDeterministicOriginalBehavior() {
        for (String failureCase : new String[]{"before", "after", "original"}) {
            B09Target.State state = new B09Target.State();
            state.prepare(failureCase);
            RuntimeException sentinel = state.expectedFailure();
            assertSame(sentinel, state.expectedFailure());
            assertEquals(0L, state.originalCount());
            for (int call = 1; call <= 2; call++) {
                if ("original".equals(failureCase)) {
                    try { state.value(); fail("original must throw the preallocated exception"); }
                    catch (RuntimeException observed) { assertSame(sentinel, observed); }
                } else {
                    assertEquals(42L, state.value());
                }
                assertEquals(call, state.originalCount());
                assertSame(sentinel, state.expectedFailure());
            }
            try { state.prepare("original"); fail("a prepared target cannot be reset"); }
            catch (IllegalStateException expected) {
                assertEquals(2L, state.originalCount());
                assertSame(sentinel, state.expectedFailure());
            }
        }
    }

    @Test public void invalidOrUnpreparedCallsCannotStartTheOriginal() {
        B09Target.State state = new B09Target.State();
        try { state.value(); fail("unprepared target"); }
        catch (IllegalStateException expected) { assertEquals(0L, state.originalCount()); }
        try { state.expectedFailure(); fail("unprepared sentinel"); }
        catch (IllegalStateException expected) { assertEquals(0L, state.originalCount()); }
        for (String failureCase : new String[]{null, "", "ORIGINAL", "after ", "other"}) {
            try { state.prepare(failureCase); fail("invalid case"); }
            catch (IllegalArgumentException expected) { assertEquals(0L, state.originalCount()); }
        }
        state.prepare("before");
        assertEquals(42L, state.value());
        assertEquals(1L, state.originalCount());
    }

    @Test public void publicFacadeHasTheSameOneShotIdentityContract() {
        assertEquals(0L, B09Target.originalCount());
        B09Target.prepare("original");
        RuntimeException sentinel = B09Target.expectedFailure();
        try { B09Target.value(); fail("original must fail"); }
        catch (RuntimeException observed) { assertSame(sentinel, observed); }
        assertEquals(1L, B09Target.originalCount());
        try { B09Target.prepare("after"); fail("public target must not reset"); }
        catch (IllegalStateException expected) { assertSame(sentinel, B09Target.expectedFailure()); }
    }
}
