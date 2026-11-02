package io.github.jichuo1.meguviz.hook.benchmarkhost;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly invoked, bounded pilot. No measurement starts unless the module reports ready. */
public final class MainActivity extends Activity {
    private static final String TAG = "MeguvizHookBench";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
        PilotRequest request = readRequest(getIntent());
        if (request == null) {
            text.setText("Benchmark pilot: explicit bounded request required.");
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            text.setText("Benchmark pilot: start a new process for each session.");
            return;
        }
        text.setText("Benchmark pilot: running; see structured log output.");
        new Thread(() -> runPilot(request), "MeguvizHookBench").start();
    }

    private PilotRequest readRequest(Intent intent) {
        try {
            if (intent == null || intent.getComponent() == null
                    || !getPackageName().equals(intent.getComponent().getPackageName())
                    || !MainActivity.class.getName().equals(intent.getComponent().getClassName())) {
                return null;
            }
            String mode = intent.getStringExtra("measurement_mode");
            if (intent.hasExtra("measurement_mode") && mode == null) return null;
            String failureCase = intent.getStringExtra("failure_case");
            if (intent.hasExtra("failure_case") && failureCase == null) return null;
            return PilotRequest.create(intent.getStringExtra("run_id"), intent.getStringExtra("pair_id"),
                    intent.getStringExtra("session_id"), intent.getStringExtra("side"),
                    intent.getStringExtra("workload"), intent.getStringExtra("control"),
                    intent.getIntExtra("warmup", -1), intent.getIntExtra("samples", -1),
                    intent.getIntExtra("iterations", -1), mode, failureCase);
        } catch (RuntimeException invalidIntent) {
            return null;
        }
    }

    private static void runPilot(PilotRequest request) {
        event(request, "start", "start", ",\"warmup\":" + request.warmup
                + ",\"samples\":" + request.samples + ",\"iterations\":" + request.iterations
                + ",\"measurement_kind\":" + quote(request.measurementKind()));
        PilotExecution.Outcome outcome = PilotExecution.run(new PilotExecution.Steps() {
            @Override
            public boolean configure() {
                if (request.observed) ObservedBenchmarkTarget.prepareObservation();
                return BenchmarkControl.configure(request.runId, request.pairId, request.sessionId,
                        request.side, request.bridgeWorkload(),
                        request.control, request.warmup,
                        request.samples, request.iterations);
            }

            @Override
            public String sample() {
                // These single transactions are measured and checked by the module bridge.
                if ("B07".equals(request.workload) || "B08".equals(request.workload)
                        || "B09".equals(request.workload) || "B10".equals(request.workload)) return "completed";
                if (request.observed) return sampleObserved(request);
                if (request.warmup > 0 && runLoop(request.workload, request.warmup) != expected(request, request.warmup)) {
                    return "warmup_failed";
                }
                for (int index = 0; index < request.samples; index++) {
                    long start = Debug.threadCpuTimeNanos();
                    long checksum = runLoop(request.workload, request.iterations);
                    long end = Debug.threadCpuTimeNanos();
                    boolean valid = start >= 0 && end >= start && checksum == expected(request, request.iterations);
                    event(request, "sample", valid ? "pass" : "fail", ",\"index\":" + index
                            + ",\"iterations\":" + request.iterations + ",\"thread_cpu_start_ns\":" + start
                            + ",\"thread_cpu_end_ns\":" + end + ",\"checksum\":" + quote(hex(checksum)));
                    if (!valid) return "sample_failed";
                }
                return "completed";
            }

            @Override
            public boolean finish() {
                return BenchmarkControl.finish(request.runId);
            }
        });
        event(request, "end", outcome.completed && outcome.cleanupReported && outcome.error == null ? "pass" : "fail",
                ",\"detail\":" + quote(outcome.detail) + ",\"cleanup_reported\":" + outcome.cleanupReported);
        if (outcome.error != null) throw outcome.error;
    }

    private static String sampleObserved(PilotRequest request) {
        ObservedBenchmarkTarget.resetObservations();
        long warmupResult = runObservedLoop(request.workload, request.warmup);
        long[] counts = ObservedBenchmarkTarget.observationSnapshot();
        boolean valid = warmupResult == expected(request, request.warmup)
                && ObservedCounts.matches(request.workload, request.warmup, counts);
        event(request, "warmup_observation", valid ? "pass" : "fail",
                ",\"iterations\":" + request.warmup + ObservedCounts.fields(counts));
        if (!valid) return "warmup_failed";
        for (int index = 0; index < request.samples; index++) {
            ObservedBenchmarkTarget.resetObservations();
            long start = Debug.threadCpuTimeNanos();
            long checksum = runObservedLoop(request.workload, request.iterations);
            long end = Debug.threadCpuTimeNanos();
            counts = ObservedBenchmarkTarget.observationSnapshot();
            valid = start >= 0 && end >= start && checksum == expected(request, request.iterations)
                    && ObservedCounts.matches(request.workload, request.iterations, counts);
            event(request, "sample", valid ? "pass" : "fail", ",\"index\":" + index
                    + ",\"iterations\":" + request.iterations + ",\"thread_cpu_start_ns\":" + start
                    + ",\"thread_cpu_end_ns\":" + end + ",\"checksum\":" + quote(hex(checksum))
                    + ObservedCounts.fields(counts));
            if (!valid) return "sample_failed";
        }
        return "completed";
    }

    private static long runLoop(String workload, int iterations) {
        if ("B01".equals(workload)) return BenchmarkTarget.loopB01(iterations);
        if ("B02".equals(workload)) return BenchmarkTarget.loopB02(iterations);
        if ("B03".equals(workload)) return BenchmarkTarget.loopB03(iterations);
        if ("B04".equals(workload)) return BenchmarkTarget.loopB04(iterations);
        if ("B05".equals(workload)) return BenchmarkTarget.loopB05(iterations);
        if ("B06".equals(workload)) return BenchmarkTarget.loopB06(iterations);
        throw new IllegalArgumentException("unknown workload: " + workload);
    }

    private static long runObservedLoop(String workload, int iterations) {
        if ("B01".equals(workload)) return ObservedBenchmarkTarget.loopObservedB01(iterations);
        if ("B02".equals(workload)) return ObservedBenchmarkTarget.loopObservedB02(iterations);
        if ("B03".equals(workload)) return ObservedBenchmarkTarget.loopObservedB03(iterations);
        if ("B04".equals(workload)) return ObservedBenchmarkTarget.loopObservedB04(iterations);
        if ("B05".equals(workload)) return ObservedBenchmarkTarget.loopObservedB05(iterations);
        if ("B06".equals(workload)) return ObservedBenchmarkTarget.loopObservedB06(iterations);
        throw new IllegalArgumentException("unknown workload: " + workload);
    }

    private static long expected(PilotRequest request, int iterations) {
        if ("B01".equals(request.workload) || "B03".equals(request.workload)
                || "B05".equals(request.workload)) return 42L * iterations;
        if ("B02".equals(request.workload)) return 7L * iterations;
        if ("B04".equals(request.workload)) return 9L * iterations;
        if ("B06".equals(request.workload)) return 4L * iterations;
        throw new IllegalArgumentException("unknown workload: " + request.workload);
    }

    private static String hex(long value) {
        return String.format(Locale.ROOT, "%016x", value);
    }

    private static void event(PilotRequest request, String kind, String status, String extra) {
        String json = "{\"schema\":1,\"run_id\":" + quote(request.runId)
                + ",\"pair_id\":" + quote(request.pairId) + ",\"session_id\":" + quote(request.sessionId)
                + ",\"pid\":" + Process.myPid() + ",\"side\":" + quote(request.side)
                + ",\"workload\":" + quote(request.workload) + ",\"control\":" + quote(request.control)
                + (request.failureCase == null ? "" : ",\"failure_case\":" + quote(request.failureCase))
                + ",\"event\":" + quote(kind) + ",\"status\":" + quote(status) + extra + "}";
        Log.i(TAG, json);
    }

    private static String quote(String value) {
        return JSONObject.quote(value);
    }
}
