package io.github.jichuo1.meguviz.hook.clockdiag;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly invoked clock-read diagnostic; not a Hook or performance gate. */
public final class MainActivity extends Activity {
    private static final String TAG = "MeguvizHookClock";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
        ClockRequest request = readRequest(getIntent());
        if (request == null) {
            text.setText("Clock diagnostic: explicit bounded request required.");
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            text.setText("Clock diagnostic: start a new process for each session.");
            return;
        }
        text.setText("Clock diagnostic: running; see structured log output.");
        new Thread(() -> runDiagnostic(request), "MeguvizHookClock").start();
    }

    private ClockRequest readRequest(Intent intent) {
        try {
            if (intent == null || intent.getComponent() == null
                    || !getPackageName().equals(intent.getComponent().getPackageName())
                    || !MainActivity.class.getName().equals(intent.getComponent().getClassName())) {
                return null;
            }
            return ClockRequest.create(intent.getStringExtra("run_id"),
                    intent.getIntExtra("warmup", -1), intent.getIntExtra("samples", -1));
        } catch (RuntimeException invalidIntent) {
            return null;
        }
    }

    private static void runDiagnostic(ClockRequest request) {
        event(request.runId, "start", "start", ",\"warmup\":" + request.warmup
                + ",\"samples\":" + request.samples
                + ",\"clock\":\"thread_cpu_ns\",\"wall_clock\":\"elapsed_realtime_ns\"");
        String result = ClockEngine.run(new ClockEngine.Clock() {
            @Override public long cpuNanos() { return Debug.threadCpuTimeNanos(); }
            @Override public long elapsedNanos() { return SystemClock.elapsedRealtimeNanos(); }
        }, (phase, index, cpuBefore, cpuAfter, wallBefore, wallAfter, reason) ->
                event(request.runId, phase, reason.isEmpty() ? "pass" : "fail",
                        ",\"index\":" + index + ",\"cpu_before_ns\":" + cpuBefore
                                + ",\"cpu_after_ns\":" + cpuAfter + ",\"wall_before_ns\":" + wallBefore
                                + ",\"wall_after_ns\":" + wallAfter + ",\"reason\":" + quote(reason)),
                request.warmup, request.samples);
        event(request.runId, "end", result.isEmpty() ? "pass" : "fail", ",\"reason\":" + quote(result));
    }

    private static void event(String runId, String kind, String status, String extra) {
        String json = "{\"schema\":1,\"run_id\":" + quote(runId)
                + ",\"pid\":" + Process.myPid() + ",\"tid\":" + Process.myTid()
                + ",\"event\":" + quote(kind) + ",\"status\":" + quote(status) + extra + "}";
        Log.i(TAG, json);
    }

    private static String quote(String value) {
        return JSONObject.quote(value);
    }
}
