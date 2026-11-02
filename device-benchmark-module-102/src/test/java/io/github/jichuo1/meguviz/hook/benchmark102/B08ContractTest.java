package io.github.jichuo1.meguviz.hook.benchmark102;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Request and failure-serialization boundaries; no framework/device operation. */
public class B08ContractTest {
    static final OutOfMemoryError FATAL = new OutOfMemoryError("controlled reflection fixture");
    public static long fatalTarget(boolean hold) { throw FATAL; }
    private static List<Object> request() {
        return new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755",
                "A", "B08", "A2", 0, 1, 1));
    }
    @Test public void onlyOneFreshUnobservedA2TransactionIsAccepted() {
        assertNotNull(BenchmarkEntry.Request.from(request()));
        for (Object[] change : new Object[][]{{5,"A0"},{4,"B08_OBSERVED"},{4,"B09"},{6,1},{7,2},{8,2}}) {
            List<Object> args = request(); args.set((Integer) change[0],change[1]); assertNull(BenchmarkEntry.Request.from(args));
        }
        for (int[] duplicate : new int[][]{{0,1},{0,2},{1,2}}) {
            List<Object> args=request();args.set(duplicate[1],args.get(duplicate[0]));assertNull(BenchmarkEntry.Request.from(args));
        }
    }
    @Test public void reflectedBooleanCallRetainsFatalIdentity() throws Exception {
        Method target=getClass().getMethod("fatalTarget",boolean.class);
        try { BenchmarkEntry.b08InvokeStatic(target,true); fail("fatal must escape"); }
        catch (Throwable failure) { assertSame(FATAL,failure); }
    }
    @Test public void incompleteCloseReportDoesNotBreakFailureSerialization() throws Exception {
        B08Runner.Observation observed=new B08Runner.Observation();
        observed.report=new CloseReport(null,false,null,1,null,"incomplete");
        Method formatter=BenchmarkEntry.class.getDeclaredMethod("b08Observation",B08Runner.Observation.class);formatter.setAccessible(true);
        String json=(String)formatter.invoke(null,observed);
        assertTrue(json.contains("\"in_flight_at_close\":1"));
        assertFalse(json.contains("registration_state"));assertFalse(json.contains("entry_restoration"));
        assertFalse(json.contains("physical_release"));assertFalse(B08Runner.validClose(observed.report));
    }
}
