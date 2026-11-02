package io.github.jichuo1.meguviz.hook.devicehost;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Explicitly scoped host for framework integration tests; it contains no module entry. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setText(R.string.test_host_description);
        setContentView(text);
    }
}
