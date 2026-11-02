package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Drives the actual bounded transaction; fake steps are not framework or device evidence. */
public class B08RunnerTest {
    static final class Fixture implements B08Runner.Clock, B08Runner.Steps, B08Runner.Events {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch allowClose = new CountDownLatch(1), closeEnded = new CountDownLatch(1);
        final CountDownLatch allowWorker = new CountDownLatch(1), workerEnded = new CountDownLatch(1);
        final CountDownLatch allowPost = new CountDownLatch(1), postEnded = new CountDownLatch(1);
        final AtomicLong before = new AtomicLong(), after = new AtomicLong(), old = new AtomicLong(), fresh = new AtomicLong();
        final AtomicInteger closeCalls = new AtomicInteger(), environmentCalls = new AtomicInteger(), reads = new AtomicInteger();
        final List<String> trace = new ArrayList<>();
        final List<B08Runner.Observation> observed = new ArrayList<>();
        volatile boolean closed;
        boolean closeWaitsRelease, stuckClose, stuckWorker, stuckPost, postHooked, dropAfter;
        boolean zeroClock, negativeClock, endClockFails, failInstall, failPrepare;
        boolean duplicateOriginal;
        Throwable oldFailure, postFailure;
        Error observationError;
        int errorOnRead;
        final AtomicInteger clockCalls = new AtomicInteger();
        CloseReport report = report(RegistrationState.CLOSED, 1);
        B08Runner runner() { return new B08Runner(this, this, this, 40L); }
        static CloseReport report(RegistrationState state, int inflight) {
            return new CloseReport(state, true, CloseReport.EntryRestoration.RESTORED,
                    inflight, CloseReport.PhysicalRelease.NOT_CLAIMED, "fake");
        }
        @Override public long wall() {
            int call = clockCalls.incrementAndGet();
            if (endClockFails && call == 2) throw new IllegalStateException("clock end failed");
            return negativeClock && call == 1 ? -1 : zeroClock ? 100 : 100 + call;
        }
        @Override public long tid() { return Thread.currentThread().getId(); }
        @Override public void install() { if (failInstall) throw new IllegalStateException("install failed"); }
        @Override public CountDownLatch[] prepareTarget() {
            if (failPrepare) throw new IllegalStateException("prepare failed");
            return new CountDownLatch[]{entered, release};
        }
        @Override public Object invoke(boolean hold) throws Throwable {
            boolean sdk = !closed || (!hold && postHooked);
            if (sdk) before.incrementAndGet();
            if (hold) {
                try {
                    if (oldFailure != null) throw oldFailure;
                    old.addAndGet(duplicateOriginal ? 2 : 1); entered.countDown();
                    if (!(stuckWorker ? allowWorker : release).await(2000, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("test original timeout");
                    }
                    return 42L;
                } finally { if (sdk && !dropAfter) after.incrementAndGet(); workerEnded.countDown(); }
            }
            try {
                fresh.incrementAndGet();
                if (stuckPost && !allowPost.await(2000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("test post timeout");
                if (postFailure != null) throw postFailure;
                return 42L;
            } finally { if (sdk) after.incrementAndGet(); postEnded.countDown(); }
        }
        @Override public long[] originalCounts() {
            if (reads.incrementAndGet() == errorOnRead) throw observationError;
            return new long[]{old.get(), fresh.get()};
        }
        @Override public long[] hookCounts() { return new long[]{before.get(), after.get()}; }
        @Override public CloseReport closeRegistration() throws Throwable {
            closeCalls.incrementAndGet();
            try {
                if (closeWaitsRelease && !release.await(2000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("test close timeout");
                if (stuckClose && !allowClose.await(2000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("test close timeout");
                closed = true; return report;
            } finally { closeEnded.countDown(); }
        }
        @Override public void closeEnvironment() { environmentCalls.incrementAndGet(); closed = true; }
        @Override public void checkFramework() { }
        @Override public void observed(String stage, B08Runner.Observation value) { trace.add(stage); observed.add(value); }
        @Override public void failed(String stage, Throwable error, B08Runner.Observation value) {
            trace.add("fail_" + stage); observed.add(value);
        }
        void unblock() throws InterruptedException {
            release.countDown(); allowClose.countDown(); allowWorker.countDown(); allowPost.countDown();
            if (closeCalls.get() > 0) assertTrue(closeEnded.await(500, TimeUnit.MILLISECONDS));
            if (old.get() > 0) assertTrue(workerEnded.await(500, TimeUnit.MILLISECONDS));
            if (fresh.get() > 0) assertTrue(postEnded.await(500, TimeUnit.MILLISECONDS));
        }
    }

    @Test public void successHoldsOldCallUntilAfterPostCloseAndKeepsSnapshot() throws Exception {
        for (RegistrationState state : new RegistrationState[]{RegistrationState.CLOSED, RegistrationState.RETIRED_RESIDENT}) {
            Fixture f = new Fixture(); f.report = Fixture.report(state, 1); B08Runner runner = f.runner();
            assertTrue(runner.configure()); assertFalse(runner.configure()); assertTrue(runner.finish()); assertFalse(runner.finish());
            assertEquals(java.util.Arrays.asList("installed", "old_entered", "close_returned", "post_close", "old_completed", "configured", "cleanup"), f.trace);
            B08Runner.Observation held = f.observed.get(3), done = f.observed.get(4), cleanup = f.observed.get(6);
            assertFalse(held.workerCompleted); assertFalse(held.gateReleased); assertTrue(held.workerAlive);
            assertEquals(1L, held.originalCounts[0]); assertEquals(1L, held.originalCounts[1]);
            assertEquals(1L, held.hookCounts[0]); assertEquals(0L, held.hookCounts[1]);
            assertTrue(done.workerCompleted); assertTrue(done.gateReleased); assertFalse(done.workerAlive);
            assertEquals(1L, done.hookCounts[1]); assertSame(f.report, done.report); assertEquals(1, done.report.inFlightAtClose());
            assertTrue(cleanup.cleanupConfirmed); assertFalse(cleanup.workerAlive || cleanup.closerAlive || cleanup.postAlive || cleanup.cleanupAlive);
            assertEquals(1, f.closeCalls.get()); assertEquals(1, f.environmentCalls.get()); f.unblock();
        }
    }

    @Test public void finishBeforeConfigureCannotStartAWorker() {
        Fixture f = new Fixture(); B08Runner runner = f.runner();
        assertFalse(runner.finish()); assertFalse(runner.configure()); assertEquals(0, f.closeCalls.get()); assertEquals(0, f.old.get());
    }

    @Test public void closeThatWaitsForOriginalIsARecordedTimeoutNotSuccess() throws Exception {
        Fixture f = new Fixture(); f.closeWaitsRelease = true; B08Runner runner = f.runner();
        try {
            assertFalse(runner.configure()); assertFalse(runner.finish()); assertEquals(1, f.closeCalls.get());
            assertFalse(f.trace.contains("close_returned")); assertEquals(1, f.old.get()); assertEquals(0, f.fresh.get());
        } finally { f.unblock(); }
    }

    @Test public void survivingOwnedThreadPreventsSynchronousEnvironmentClose() throws Exception {
        for (String mode : new String[]{"close", "old", "post"}) {
            Fixture f = new Fixture(); f.stuckClose = "close".equals(mode); f.stuckWorker = "old".equals(mode); f.stuckPost = "post".equals(mode);
            B08Runner runner = f.runner();
            try {
                assertFalse(runner.configure()); assertFalse(runner.finish()); assertEquals(0, f.environmentCalls.get());
                assertFalse(f.observed.get(f.observed.size() - 1).cleanupConfirmed);
            } finally { f.unblock(); }
        }
    }

    @Test public void wrongCloseSnapshotOrRestorationCannotPass() throws Exception {
        for (CloseReport report : new CloseReport[]{Fixture.report(RegistrationState.CLOSED, 0),
                Fixture.report(RegistrationState.RETIRING, 1), new CloseReport(null, true, null, 1, null, "partial"),
                new CloseReport(RegistrationState.CLOSED, false, CloseReport.EntryRestoration.NOT_RESTORED, 1, CloseReport.PhysicalRelease.UNKNOWN, "failed")}) {
            Fixture f = new Fixture(); f.report = report; B08Runner runner = f.runner();
            try { assertFalse(runner.configure()); assertFalse(runner.finish()); assertFalse(f.trace.contains("close_returned")); }
            finally { f.unblock(); }
        }
    }

    @Test public void duplicateOriginalPostHookOrLostOldAfterCannotPass() throws Exception {
        for (String mode : new String[]{"duplicate", "post_hook", "after"}) {
            Fixture f = new Fixture(); f.duplicateOriginal = "duplicate".equals(mode); f.postHooked = "post_hook".equals(mode); f.dropAfter = "after".equals(mode); B08Runner runner = f.runner();
            try { assertFalse(runner.configure()); assertFalse(runner.finish()); }
            finally { f.unblock(); }
        }
    }

    @Test public void preparationAndInstallationFailuresStillHaveBoundedCleanup() throws Exception {
        for (boolean install : new boolean[]{true, false}) {
            Fixture f = new Fixture(); f.failInstall = install; f.failPrepare = !install; B08Runner runner = f.runner();
            assertFalse(runner.configure()); assertFalse(runner.finish()); assertEquals(1, f.environmentCalls.get()); assertEquals(0, f.closeCalls.get()); f.unblock();
        }
    }

    @Test public void clockFailuresRetainCloseResultAndZeroIsNotDiscarded() throws Exception {
        for (String mode : new String[]{"zero", "negative", "missing_end"}) {
            Fixture f = new Fixture(); f.zeroClock = "zero".equals(mode); f.negativeClock = "negative".equals(mode); f.endClockFails = "missing_end".equals(mode); B08Runner runner = f.runner();
            try {
                if (f.zeroClock) { assertTrue(runner.configure()); assertTrue(runner.finish()); }
                else { assertFalse(runner.configure()); assertFalse(runner.finish()); }
                assertEquals(1, f.closeCalls.get());
                B08Runner.Observation x = f.observed.get(f.observed.size() - 1); assertSame(f.report, x.report); assertNotNull(x.closeStart);
                if (f.negativeClock) assertEquals(Long.valueOf(-1), x.closeStart);
                if (f.endClockFails) assertNull(x.closeEnd);
                if (f.zeroClock) assertEquals(x.closeStart, x.closeEnd);
            } finally { f.unblock(); }
        }
    }

    @Test public void workerAndPostFailuresCannotBecomeSuccessfulTransactions() throws Exception {
        for (boolean old : new boolean[]{true, false}) {
            Fixture f = new Fixture(); if (old) f.oldFailure = new IllegalStateException("old"); else f.postFailure = new IllegalStateException("post"); B08Runner runner = f.runner();
            try { assertFalse(runner.configure()); assertFalse(runner.finish()); }
            finally { f.unblock(); }
        }
    }

    @Test public void observationErrorIsNotSwallowedAndRetainsFatalPriority() throws Exception {
        Fixture f = new Fixture(); AssertionError same = new AssertionError("observation"); f.observationError = same; f.errorOnRead = 2; B08Runner runner = f.runner();
        try {
            try { runner.configure(); fail("Error must escape"); } catch (AssertionError error) { assertSame(same, error); }
            try { runner.finish(); fail("Error must remain"); } catch (AssertionError error) { assertSame(same, error); }
        } finally { f.unblock(); }
        Fixture fatal = new Fixture(); OutOfMemoryError original = new OutOfMemoryError("original"); fatal.oldFailure = original;
        B08Runner other = fatal.runner();
        try {
            try { other.configure(); fail("fatal must escape"); } catch (OutOfMemoryError error) { assertSame(original, error); }
            try { other.finish(); fail("fatal must remain"); } catch (OutOfMemoryError error) { assertSame(original, error); }
        } finally { fatal.unblock(); }
    }

    @Test public void earlyAliveReturnRestoresControllerInterrupt() throws Exception {
        Fixture f = new Fixture(); f.stuckClose = true; B08Runner runner = f.runner();
        try {
            assertFalse(runner.configure()); Thread.currentThread().interrupt(); assertFalse(runner.finish()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); f.unblock(); }
    }
}
