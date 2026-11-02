package io.github.jichuo1.meguviz.hook.benchmark102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Drives the exact B09 transaction with deterministic ordinary failures and cleanup faults. */
public class B09RunnerTest {
    static final class Fixture implements B09Runner.Clock, B09Runner.Steps, B09Runner.Events {
        final String failureCase, control;
        final RuntimeException expected = new IllegalStateException("controlled expected failure");
        final List<String> trace = new ArrayList<>();
        long before, after, original;
        int invokes, cpuReads, wallReads;
        boolean installed, closed, wrongIdentity, duplicateOriginal, wrongAfter, zero, negative, backwards;
        String fault;
        Throwable installError, invokeError, closeError, stateError;
        B09Runner.Observation failed, measured, cleaned, posted;
        String failedStage;
        Fixture(String failureCase, String control) { this.failureCase=failureCase;this.control=control; }
        B09Runner runner() { return new B09Runner(failureCase,control,this,this,this); }
        private void failAt(String name) { if(name.equals(fault)) throw new IllegalStateException(name); }
        @Override public void prepare() { trace.add("prepare");failAt("prepare"); }
        @Override public RuntimeException expectedFailure() { return expected; }
        @Override public void install() throws Throwable { trace.add("install");installed=true;if(installError!=null)throw installError; }
        @Override public void validateInstall() { trace.add("validate");failAt("validate"); }
        @Override public long wall() { trace.add("wall");wallReads++;return negative?-1:wallReads==1||zero?100:150; }
        @Override public long cpu() { trace.add("cpu");cpuReads++;if(cpuReads==2)failAt("cpu_end");return cpuReads==1||zero?10:backwards?9:30; }
        @Override public Object invoke() throws Throwable {
            trace.add("invoke");invokes++;
            if(closed) { original++;if("original".equals(failureCase))throw expected;return 42L; }
            before++;
            if(invokeError!=null)throw invokeError;
            if(!"before".equals(failureCase)) { original+=duplicateOriginal?2:1;after++; }
            throw wrongIdentity?new IllegalStateException("wrong instance"):expected;
        }
        @Override public B09Runner.State state() throws Throwable {
            if(invokes>0&&stateError!=null)throw stateError;
            String kind=after==0?"not_called":"after".equals(failureCase)?"returned":"threw";
            return new B09Runner.State(before,after,original,wrongAfter&&after>0?"not_called":kind,
                    "returned".equals(kind)?42L:null,"threw".equals(kind)?expected:null,null);
        }
        @Override public void checkFramework() { trace.add("framework");failAt("framework"); }
        @Override public B09Runner.Close closeRegistration() throws Throwable {
            trace.add("close");closed=true;if(closeError!=null)throw closeError;
            if("A0".equals(control))return new B09Runner.Close("framework_unhook_contract",installed,null);
            return new B09Runner.Close("sdk_close_report",false,installed?new CloseReport(RegistrationState.CLOSED,true,
                    CloseReport.EntryRestoration.RESTORED,0,CloseReport.PhysicalRelease.NOT_CLAIMED,"controlled"):null);
        }
        @Override public void closeEnvironment() { trace.add("env_close");failAt("env_close"); }
        @Override public void observed(String stage,B09Runner.Observation o) {
            trace.add("event_"+stage);if(stage.equals("failure_call"))measured=o;if(stage.equals("cleanup"))cleaned=o;if(stage.equals("post_close"))posted=o;
        }
        @Override public void failed(String stage,Throwable failure,B09Runner.Observation o) { trace.add("fail_"+stage);failedStage=stage;failed=o; }
    }
    @Test public void allCasesAndControlsUseOneMeasuredCallThenOneUnhookedCall() {
        for(String control:new String[]{"A0","A2"})for(String c:new String[]{"before","after","original"}) {
            Fixture f=new Fixture(c,control);B09Runner r=f.runner();assertTrue(r.configure());assertFalse(r.configure());
            assertEquals(1,f.invokes);assertEquals("threw",f.measured.outcome);assertTrue(f.measured.identityPreserved);
            assertEquals(Long.valueOf(100),f.measured.wallStart);assertEquals(Long.valueOf(30),f.measured.cpuEnd);
            assertTrue(r.finish());assertFalse(r.finish());assertEquals(2,f.invokes);
            assertEquals("before".equals(c)?1:2,f.original);assertEquals(1,f.before);assertEquals("before".equals(c)?0:1,f.after);
            assertEquals("original".equals(c)?"threw":"returned",f.posted.outcome);
            assertEquals(Arrays.asList("prepare","install","validate","event_installed","wall","cpu","invoke","cpu","wall","event_failure_call","framework","event_configured","close","env_close","event_cleanup","invoke","event_post_close"),f.trace);
        }
    }
    @Test public void unknownInstallCannotBecomeCleanupPassEvenWithAReachableHandle() {
        Fixture f=new Fixture("after","A2");f.installError=new IllegalStateException("unknown");B09Runner r=f.runner();
        assertFalse(r.configure());assertTrue(f.failed.outcomeUnknown);assertFalse(r.finish());
        assertTrue(f.trace.contains("close"));assertTrue(f.trace.contains("env_close"));assertFalse(f.trace.contains("event_cleanup"));assertEquals(0,f.invokes);
    }
    @Test public void preparationOrReceiptFailureNeverStartsTheMeasuredCall() {
        for(String fault:new String[]{"prepare","validate"}) {
            Fixture f=new Fixture("before","A0");f.fault=fault;B09Runner r=f.runner();assertFalse(r.configure());assertFalse(r.finish());
            assertEquals(0,f.invokes);assertFalse(f.trace.contains("event_failure_call"));assertTrue(f.trace.contains("env_close"));
        }
    }
    @Test public void wrongIdentityDuplicateOriginalOrWrongAfterObservationIsRejected() {
        for(String wrong:new String[]{"identity","duplicate","after"}) {
            Fixture f=new Fixture("original","A2");f.wrongIdentity=wrong.equals("identity");f.duplicateOriginal=wrong.equals("duplicate");f.wrongAfter=wrong.equals("after");
            B09Runner r=f.runner();assertFalse(r.configure());assertEquals("failure_call",f.failedStage);assertFalse(r.finish());assertEquals(1,f.invokes);
        }
    }
    @Test public void clockReadFailureRetainsAvailableEndpointsAndDoesNotReplay() {
        Fixture f=new Fixture("before","A2");f.fault="cpu_end";B09Runner r=f.runner();assertFalse(r.configure());
        assertEquals(Long.valueOf(100),f.failed.wallStart);assertEquals(Long.valueOf(10),f.failed.cpuStart);assertNull(f.failed.cpuEnd);assertEquals(Long.valueOf(150),f.failed.wallEnd);
        assertTrue(f.failed.identityPreserved);assertFalse(r.finish());assertEquals(1,f.invokes);
    }
    @Test public void negativeAndBackwardsClocksFailWhileLegalZeroRemainsRaw() {
        for(String value:new String[]{"negative","backwards"}) {
            Fixture f=new Fixture("after","A0");f.negative=value.equals("negative");f.backwards=value.equals("backwards");B09Runner r=f.runner();assertFalse(r.configure());assertFalse(r.finish());
        }
        Fixture zero=new Fixture("after","A2");zero.zero=true;B09Runner r=zero.runner();assertTrue(r.configure());assertTrue(r.finish());assertEquals(zero.measured.wallStart,zero.measured.wallEnd);assertEquals(zero.measured.cpuStart,zero.measured.cpuEnd);
    }
    @Test public void cleanupFaultStillAttemptsEnvironmentCleanupAndSkipsPostCall() {
        Fixture f=new Fixture("after","A2");f.closeError=new IllegalStateException("close");B09Runner r=f.runner();assertTrue(r.configure());assertFalse(r.finish());assertTrue(f.trace.contains("env_close"));assertTrue(f.failed.outcomeUnknown);assertEquals(1,f.invokes);
    }
    @Test public void environmentFailureRetainsTheActualCloseReport() {
        Fixture f=new Fixture("original","A2");f.fault="env_close";B09Runner r=f.runner();assertTrue(r.configure());assertFalse(r.finish());assertTrue(f.failed.outcomeUnknown);assertNotNull(f.failed.close.report);assertEquals(CloseReport.EntryRestoration.RESTORED,f.failed.close.report.entryRestoration());assertEquals(1,f.invokes);
    }
    @Test public void fatalInvokeAndStateObservationKeepTheirOriginalIdentity() {
        for(String where:new String[]{"invoke","state"}) {
            Fixture f=new Fixture("original","A2");OutOfMemoryError fatal=new OutOfMemoryError("controlled fixture");if(where.equals("invoke"))f.invokeError=fatal;else f.stateError=fatal;B09Runner r=f.runner();
            try { r.configure();fail("fatal must escape"); } catch(OutOfMemoryError actual) { assertSame(fatal,actual); }
            f.stateError=null;assertFalse(r.finish());assertEquals(1,f.invokes);
        }
    }
    @Test public void finishedRunnerCannotStartOrRepeatCleanup() {
        Fixture f=new Fixture("before","A0");B09Runner r=f.runner();assertFalse(r.finish());assertFalse(r.configure());assertFalse(r.finish());assertFalse(f.trace.contains("install"));assertEquals(1,java.util.Collections.frequency(f.trace,"close"));
    }
}
