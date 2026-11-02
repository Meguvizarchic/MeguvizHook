package io.github.jichuo1.meguviz.hook.benchmark101;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.benchmarkhost.B07Target;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** B07 request and proof guardrails on JVM stubs, not a device run. */
public class B07ContractTest {
    private static List<Object> request() {
        return new ArrayList<>(Arrays.asList("f83b50a7-cda5-4d58-9249-d37e2da9929d",
                "184b0505-57a1-4075-8e33-c75f4247ebbc", "a215634c-75bc-4202-9893-8a291abda755",
                "A", "B07", "A2", 0, 1, 1));
    }

    @Test public void b07RequiresSingleFreshA2Run() {
        List<Object> args = request();
        assertNotNull(BenchmarkEntry.Request.from(args));
        args.set(5, "A0"); assertNull(BenchmarkEntry.Request.from(args));
        args = request(); args.set(4, "B07_OBSERVED"); assertNull(BenchmarkEntry.Request.from(args));
        args = request(); args.set(6, 1); assertNull(BenchmarkEntry.Request.from(args));
        args = request(); args.set(7, 2); assertNull(BenchmarkEntry.Request.from(args));
        args = request(); args.set(8, 2); assertNull(BenchmarkEntry.Request.from(args));
        args = request(); args.set(4, "B09"); assertNull(BenchmarkEntry.Request.from(args));
    }

    @Test public void b07RejectsAnyRepeatedIdentityWithoutChangingOlderWorkloads() {
        List<Object> base = request();
        Object run = base.get(0), pair = base.get(1), session = base.get(2);
        Object[][] repeated = {
                {run, run, session}, {run, pair, run}, {run, pair, pair}, {run, run, run}
        };
        for (Object[] ids : repeated) {
            List<Object> args = request();
            for (int i = 0; i < ids.length; i++) args.set(i, ids[i]);
            assertNull(BenchmarkEntry.Request.from(args));
            for (String workload : new String[]{"B01", "B02", "B03", "B04", "B05", "B06"}) {
                args.set(4, workload);
                assertNotNull(BenchmarkEntry.Request.from(args));
            }
        }
    }

    @Test public void rawClockEndpointsCannotRunBackwardsOrBeNegative() {
        assertTrue(BenchmarkEntry.b07ValidClocks(10, 20, 25, 30));
        assertTrue(BenchmarkEntry.b07ValidClocks(10, 20, 20, 10)); // zero remains raw, not qualification
        assertFalse(BenchmarkEntry.b07ValidClocks(-1, 20, 25, 30));
        assertFalse(BenchmarkEntry.b07ValidClocks(10, -1, 25, 30));
        assertFalse(BenchmarkEntry.b07ValidClocks(10, 20, 19, 30));
        assertFalse(BenchmarkEntry.b07ValidClocks(10, 20, 25, 9));
    }

    @Test public void onlyExactTargetIdentityAndLoaderIsAccepted() throws Exception {
        assertTrue(BenchmarkEntry.b07TargetMatches(B07Target.class.getMethod("value"), B07Target.class.getClassLoader()));
        assertFalse(BenchmarkEntry.b07TargetMatches(B07Target.class.getMethod("value"), null));
        assertFalse(BenchmarkEntry.b07TargetMatches(B07Target.class.getMethod("originalCount"), B07Target.class.getClassLoader()));
        assertFalse(BenchmarkEntry.b07TargetMatches(null, B07Target.class.getClassLoader()));
    }

    @Test public void reflectedTargetPreservesFatalErrorIdentity() throws Exception {
        try {
            BenchmarkEntry.b07InvokeStatic(B07Target.class.getMethod("failsFatal"));
            fail("fatal target error must escape");
        } catch (Throwable failure) {
            assertSame(B07Target.FATAL, failure);
        }
    }

    @Test public void cleanupRequiresExplicitRestorationAndNoInflight() {
        CloseReport pass = new CloseReport(RegistrationState.CLOSED, true,
                CloseReport.EntryRestoration.RESTORED, 0, CloseReport.PhysicalRelease.NOT_CLAIMED, "ok");
        assertTrue(BenchmarkEntry.b07CloseRestored(pass));
        assertFalse(BenchmarkEntry.b07CloseRestored(null));
        assertFalse(BenchmarkEntry.b07CloseRestored(new CloseReport(RegistrationState.CLOSED, false,
                CloseReport.EntryRestoration.RESTORED, 0, CloseReport.PhysicalRelease.NOT_CLAIMED, "")));
        assertFalse(BenchmarkEntry.b07CloseRestored(new CloseReport(RegistrationState.CLOSED, true,
                CloseReport.EntryRestoration.UNKNOWN, 0, CloseReport.PhysicalRelease.NOT_CLAIMED, "")));
        assertFalse(BenchmarkEntry.b07CloseRestored(new CloseReport(RegistrationState.CLOSED, true,
                CloseReport.EntryRestoration.RESTORED, 1, CloseReport.PhysicalRelease.NOT_CLAIMED, "")));
    }
}
