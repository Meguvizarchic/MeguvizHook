package io.github.jichuo1.meguviz.hook.benchmark102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.InvocationResult;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Uses actual B09 callbacks against Chain/InvocationResult stubs, never an Android framework. */
public class B09ContractTest {
    static final OutOfMemoryError FATAL=new OutOfMemoryError("controlled reflection fixture");
    public static long fatalTarget() { throw FATAL; }
    private static List<Object> request(String c,String control) {
        return new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d","184b0505-57a1-4075-8e33-c75f4247ebbc","a215634c-75bc-4202-9893-8a291abda755","A","B09_"+c.toUpperCase(java.util.Locale.ROOT),control,0,1,1));
    }
    private XposedInterface.Chain chain(AtomicInteger calls,Throwable thrown) {
        return (XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{XposedInterface.Chain.class},(proxy,method,args)->{
            if(!"proceed".equals(method.getName()))throw new AssertionError("NO_ARGS must not inspect the chain");
            assertTrue(args==null||args.length==0);calls.incrementAndGet();if(thrown!=null)throw thrown;return 42L;
        });
    }
    private InvocationResult result(RuntimeException expected,boolean threw) {
        return (InvocationResult)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{InvocationResult.class},(proxy,method,args)->{
            switch(method.getName()) {case "hasThrown":return threw;case "hasReturned":return !threw;case "getThrowable":return expected;case "getResult":return 42L;default:throw new AssertionError("unexpected result mutation");}
        });
    }
    @Test public void directCallbacksPreserveEachExpectedFailureAndProceedAtMostOnce() throws Throwable {
        for(String c:new String[]{"before","after","original"}) {
            RuntimeException expected=new IllegalStateException(c);BenchmarkEntry.B09Callbacks callback=new BenchmarkEntry.B09Callbacks(c,expected);AtomicInteger calls=new AtomicInteger();
            try { callback.intercept(chain(calls,"original".equals(c)?expected:null));fail("expected ordinary failure"); }catch(RuntimeException actual) { assertSame(expected,actual); }
            B09Runner.State s=callback.state(calls.get());assertEquals(1,s.before);assertEquals("before".equals(c)?0:1,s.after);assertEquals("before".equals(c)?0:1,calls.get());
            assertEquals("before".equals(c)?"not_called":"after".equals(c)?"returned":"threw",s.afterOutcome);
            if("after".equals(c))assertEquals(42L,s.afterResult);if("original".equals(c))assertSame(expected,s.afterFailure);
        }
    }
    @Test public void sdkObserversRetainAfterOutcomeWithoutThrowingAssertionsInsideAfter() throws Throwable {
        for(String c:new String[]{"before","after","original"}) {
            RuntimeException expected=new IllegalStateException(c);BenchmarkEntry.B09Callbacks callback=new BenchmarkEntry.B09Callbacks(c,expected);
            try { callback.before();if(!"before".equals(c))callback.after(result(expected,"original".equals(c)));if(!"original".equals(c))fail("callback must fail"); }
            catch(RuntimeException actual) { assertSame(expected,actual); }
            B09Runner.State s=callback.state("before".equals(c)?0:1);assertEquals(1,s.before);assertEquals("before".equals(c)?0:1,s.after);assertNull(s.observationFailure);
            if("original".equals(c))assertSame(expected,s.afterFailure);if("after".equals(c))assertEquals(42L,s.afterResult);
        }
    }
    @Test public void fatalOriginalSkipsAfterAndIsNotTurnedIntoTheExpectedRuntimeException() throws Throwable {
        BenchmarkEntry.B09Callbacks callback=new BenchmarkEntry.B09Callbacks("original",new IllegalStateException("ordinary"));AtomicInteger calls=new AtomicInteger();
        try { callback.intercept(chain(calls,FATAL));fail("fatal must escape"); }catch(OutOfMemoryError actual) { assertSame(FATAL,actual); }
        assertEquals(1,calls.get());assertEquals(0,callback.state(1).after);
        try { BenchmarkEntry.b09InvokeStatic(getClass().getMethod("fatalTarget"));fail("reflection must unwrap"); }catch(OutOfMemoryError actual) { assertSame(FATAL,actual); }
    }
    @Test public void observerGetterErrorsAreCapturedForOutsideValidationAndFatalStillEscapes() throws Throwable {
        BenchmarkEntry.B09Callbacks callback=new BenchmarkEntry.B09Callbacks("original",new IllegalStateException("ordinary"));AssertionError observed=new AssertionError("getter fault");
        InvocationResult broken=(InvocationResult)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{InvocationResult.class},(p,m,a)->{throw observed;});
        callback.after(broken);assertSame(observed,callback.state(1).observationFailure);
        InvocationResult fatal=(InvocationResult)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{InvocationResult.class},(p,m,a)->{throw FATAL;});
        try { callback.after(fatal);fail("fatal getter must escape"); }catch(OutOfMemoryError actual) { assertSame(FATAL,actual); }
    }
    @Test public void onlyExplicitCasesAndFreshOneShotIdentitiesAreAccepted() {
        for(String control:new String[]{"A0","A2"})for(String c:new String[]{"before","after","original"}) {
            List<Object> args=request(c,control);BenchmarkEntry.Request r=BenchmarkEntry.Request.from(args);assertNotNull(r);assertEquals("B09",r.workload);assertEquals(c,r.failureCase);assertFalse(r.observed);
            for(Object[] change:new Object[][]{{4,"B09"},{4,"B09_BAD"},{4,"B09_BEFORE_OBSERVED"},{5,"A1"},{6,1},{7,2},{8,2}}) {
                List<Object> bad=request(c,control);bad.set((Integer)change[0],change[1]);assertNull(BenchmarkEntry.Request.from(bad));
            }
            for(int[] duplicate:new int[][]{{0,1},{0,2},{1,2}}) {List<Object> bad=request(c,control);bad.set(duplicate[1],bad.get(duplicate[0]));assertNull(BenchmarkEntry.Request.from(bad));}
        }
    }
    @Test public void rawCloseDoesNotInventSdkReportAndSdkRequiresZeroInflightRestoration() {
        assertTrue(B09Runner.validClose("A0",new B09Runner.Close("framework_unhook_contract",true,null)));
        CloseReport report=new CloseReport(RegistrationState.CLOSED,true,CloseReport.EntryRestoration.RESTORED,0,CloseReport.PhysicalRelease.NOT_CLAIMED,"controlled");
        assertTrue(B09Runner.validClose("A2",new B09Runner.Close("sdk_close_report",false,report)));
        assertFalse(B09Runner.validClose("A0",new B09Runner.Close("framework_unhook_contract",true,report)));
        assertFalse(B09Runner.validClose("A2",new B09Runner.Close("sdk_close_report",false,new CloseReport(RegistrationState.CLOSED,true,CloseReport.EntryRestoration.RESTORED,1,CloseReport.PhysicalRelease.NOT_CLAIMED,"held"))));
    }
}
