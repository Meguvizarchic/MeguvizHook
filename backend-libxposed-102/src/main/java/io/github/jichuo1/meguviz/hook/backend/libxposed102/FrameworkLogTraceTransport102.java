package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import android.util.Log;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.libxposed.api.XposedInterface;
import java.util.Objects;

/**
 * Optional transport to the framework's existing logging API. It is synchronous and must only be
 * invoked by {@link CallbackExceptionTracker#deliver} on a caller-owned background worker.
 * This sends to framework logging, not Remote Files, and offers no persistence/IPC guarantee.
 * Construct it only after framework attach and {@code onModuleLoaded}.
 * It passes the complete original Throwable to the framework, including its message and stack;
 * the JSON transport's optional message omission does not apply to this transport.
 */
public final class FrameworkLogTraceTransport102 implements CallbackExceptionTracker.Transport {
    private final XposedInterface framework;
    private final String tag;

    public FrameworkLogTraceTransport102(XposedInterface framework, String tag) {
        this.framework = Objects.requireNonNull(framework, "framework");
        this.tag = Objects.requireNonNull(tag, "tag");
        if (tag.isEmpty() || tag.length() > 64) throw new IllegalArgumentException("tag length must be 1..64");
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029) {
                throw new IllegalArgumentException("tag must not contain control or line separators");
            }
        }
        if (framework.getApiVersion() < XposedInterface.API_102) {
            throw new IllegalArgumentException("libxposed API 102 required");
        }
    }

    @Override
    public void send(CallbackExceptionTracker.Event event) {
        Objects.requireNonNull(event, "event");
        framework.log(Log.ERROR, tag,
                "hook=" + event.hookId().value() + " generation=" + event.generation()
                        + " source=" + safeSource(event.source()) + " stage=" + event.stage().name(),
                event.failure());
    }

    private static String safeSource(String source) {
        StringBuilder safe = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            safe.append(Character.isISOControl(c) || c == 0x2028 || c == 0x2029 ? '?' : c);
        }
        return safe.toString();
    }
}
