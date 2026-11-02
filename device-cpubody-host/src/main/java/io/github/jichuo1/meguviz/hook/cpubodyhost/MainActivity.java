package io.github.jichuo1.meguviz.hook.cpubodyhost;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded, opt-in callback-body diagnostic. It is not a performance gate. */
public final class MainActivity extends Activity {
    private static final String TAG = "MeguvizHookCpuBody";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        setContentView(text);
        Intent intent = getIntent();
        try {
            if (intent == null || intent.getComponent() == null
                    || !getPackageName().equals(intent.getComponent().getPackageName())
                    || !MainActivity.class.getName().equals(intent.getComponent().getClassName())) {
                text.setText("Explicit diagnostic request required."); return;
            }
            String id = intent.getStringExtra("run_id");
            String backend = intent.getStringExtra("backend");
            String workload = intent.getStringExtra("workload");
            String control = intent.getStringExtra("control");
            int warmup = intent.getIntExtra("warmup", -1), samples = intent.getIntExtra("samples", -1);
            if (id == null || !UUID.fromString(id).toString().equals(id)
                    || !("101".equals(backend) || "102".equals(backend))
                    || !("B01".equals(workload) || "B02".equals(workload))
                    || !("A0".equals(control) || "A2".equals(control))
                    || warmup < 0 || warmup > 100 || samples < 1 || samples > 200
                    || !STARTED.compareAndSet(false, true)) {
                text.setText("Invalid request or process already used."); return;
            }
            text.setText("CPU body diagnostic running; results in structured log.");
            new Thread(() -> run(id, backend, workload, control, warmup, samples), "MeguvizHookCpuBody").start();
        } catch (RuntimeException invalid) {
            text.setText("Invalid diagnostic request.");
        }
    }

    private static void run(String id, String backend, String workload, String control, int warmup, int samples) {
        boolean hasAfter = "B01".equals(workload);
        int total = warmup + samples, completed = 0;
        ProbeLedger.initialize(total, hasAfter);
        event(id, "start", "start", ",\"backend\":" + quote(backend) + ",\"workload\":" + quote(workload)
                + ",\"control\":" + quote(control) + ",\"warmup\":" + warmup + ",\"samples\":" + samples
                + ",\"clock\":\"thread_cpu_ns\",\"measurement_kind\":\"instrumented_callback_body_cpu_interval\"");
        String[] reasons = new String[total];
        String failure = "";
        boolean cleanup = false;
        Throwable thrown = null;
        try {
            if (!ProbeControl.configure(id, backend, workload, control, warmup, samples)) {
                failure = "configure_failed";
            } else {
                for (int i = 0; i < total; i++) {
                    ProbeLedger.begin(i);
                    try {
                        long value = hasAfter ? ProbeTarget.noArgs() : ProbeTarget.add(3, 4);
                        reasons[i] = value == (hasAfter ? 42L : 7L) ? ProbeLedger.reason(i) : "unexpected_result";
                    } catch (RuntimeException | Error problem) {
                        if (FailurePriority.isFatal(problem)) { thrown = problem; throw problem; }
                        reasons[i] = "target_exception";
                        thrown = problem;
                    }
                    completed = i + 1;
                    if (!reasons[i].isEmpty()) { failure = reasons[i]; break; }
                }
            }
        } catch (RuntimeException | Error problem) {
            if (FailurePriority.isFatal(problem)) { thrown = problem; throw problem; }
            failure = "execution_exception";
            thrown = problem;
        } finally {
            if (!FailurePriority.isFatal(thrown)) {
                try { cleanup = ProbeControl.finish(id); }
                catch (RuntimeException | Error problem) {
                    if (FailurePriority.isFatal(problem)) throw problem;
                    thrown = FailurePriority.combine(thrown, problem);
                    if (failure.isEmpty()) failure = "cleanup_exception";
                }
            }
        }
        failure = ProbeLedger.finalReason(failure, cleanup);
        for (int i = 0; i < completed; i++) {
            int flags = ProbeLedger.present[i];
            event(id, "call", reasons[i].isEmpty() ? "pass" : "fail", ",\"ordinal\":" + i
                    + ",\"before_count\":" + ProbeLedger.beforeCounts[i]
                    + ",\"original_count\":" + ProbeLedger.originalCounts[i]
                    + ",\"after_count\":" + ProbeLedger.afterCounts[i]
                    + ",\"before_cpu_start_ns\":" + value(flags, 1, ProbeLedger.beforeStart[i])
                    + ",\"before_cpu_end_ns\":" + value(flags, 2, ProbeLedger.beforeEnd[i])
                    + ",\"after_cpu_start_ns\":" + value(flags, 4, ProbeLedger.afterStart[i])
                    + ",\"after_cpu_end_ns\":" + value(flags, 8, ProbeLedger.afterEnd[i])
                    + ",\"reason\":" + quote(reasons[i]));
        }
        event(id, "end", failure.isEmpty() ? "pass" : "fail", ",\"before_calls\":" + ProbeLedger.state[2]
                + ",\"original_calls\":" + ProbeLedger.state[3] + ",\"after_calls\":" + ProbeLedger.state[4]
                + ",\"cleanup_reported\":" + cleanup + ",\"reason\":" + quote(failure));
        if (thrown instanceof Error) throw (Error) thrown;
        if (thrown instanceof RuntimeException) throw (RuntimeException) thrown;
    }

    private static String value(int flags, int bit, long value) { return (flags & bit) != 0 ? Long.toString(value) : "null"; }
    private static String quote(String value) { return JSONObject.quote(value); }
    private static void event(String id, String kind, String status, String extra) {
        Log.i(TAG, "{\"schema\":1,\"run_id\":" + quote(id) + ",\"pid\":" + Process.myPid()
                + ",\"tid\":" + Process.myTid() + ",\"event\":" + quote(kind) + ",\"status\":" + quote(status) + extra + "}");
    }
}
