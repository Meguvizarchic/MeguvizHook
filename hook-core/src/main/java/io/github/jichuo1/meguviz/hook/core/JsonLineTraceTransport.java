package io.github.jichuo1.meguviz.hook.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Optional bounded UTF-8 trace encoding for a caller-provided transport. The writer may be a
 * caller-owned IPC bridge or a module-side service/file writer; this class creates neither and
 * must run from {@link CallbackExceptionTracker#deliver} on a caller-owned worker.
 *
 * <p>Records can include class names, source identifiers and stack frames. Throwable messages are
 * emitted only with explicit opt-in; callers remain responsible for privacy and retention. This
 * encodes the top-level throwable only, not causes or suppressed exceptions. Limits bound encoded
 * field lengths and frame count; they do not bound a Throwable's existing object graph or the
 * array returned by its {@code getStackTrace()} implementation.
 */
public final class JsonLineTraceTransport implements CallbackExceptionTracker.Transport {
    /** Return only after accepting a complete record; this is not automatically durable. */
    public interface RecordWriter {
        void writeRecord(byte[] utf8JsonLine) throws Exception;
    }

    private static final int FIELD_CHARS = 128;
    private static final int MESSAGE_CHARS = 512;
    private final RecordWriter writer;
    private final boolean includeMessage;
    private final int maxFrames;

    public JsonLineTraceTransport(RecordWriter writer, boolean includeMessage, int maxFrames) {
        this.writer = Objects.requireNonNull(writer, "writer");
        if (maxFrames < 0 || maxFrames > 32) throw new IllegalArgumentException("maxFrames must be 0..32");
        this.includeMessage = includeMessage;
        this.maxFrames = maxFrames;
    }

    @Override
    public void send(CallbackExceptionTracker.Event event) throws Exception {
        writer.writeRecord(encode(event));
    }

    /** Encode one event without sending it, for explicit synchronous batch transports. */
    public byte[] encode(CallbackExceptionTracker.Event event) {
        return encode(event, includeMessage, maxFrames);
    }

    /** Encode with explicit privacy and stack limits without creating a transport. */
    public static byte[] encode(CallbackExceptionTracker.Event event, boolean includeMessage, int maxFrames) {
        Objects.requireNonNull(event, "event");
        if (maxFrames < 0 || maxFrames > 32) throw new IllegalArgumentException("maxFrames must be 0..32");
        StringBuilder text = new StringBuilder();
        text.append("{\"version\":1,");
        field(text, "source", event.source(), FIELD_CHARS);
        text.append(','); field(text, "hook", event.hookId().value(), FIELD_CHARS);
        text.append(",\"generation\":").append(event.generation());
        text.append(",\"timeNanos\":").append(event.timeNanos());
        text.append(','); field(text, "stage", event.stage().name(), FIELD_CHARS);
        text.append(','); field(text, "type", event.failure().getClass().getName(), FIELD_CHARS);
        if (includeMessage) {
            String message = null;
            boolean messageError = false;
            try {
                message = event.failure().getMessage();
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                messageError = true;
            }
            text.append(','); field(text, "message", message, MESSAGE_CHARS);
            if (messageError) text.append(",\"messageError\":true");
        }
        text.append(",\"frames\":[");
        boolean framesTruncated = false;
        boolean framesCollected = false;
        boolean framesError = false;
        boolean framesMalformed = false;
        if (maxFrames != 0) {
            StackTraceElement[] frames = null;
            try {
                frames = event.failure().getStackTrace();
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                framesError = true;
            }
            if (frames == null) {
                framesError = true;
            } else {
                framesCollected = true;
                int limit = Math.min(frames.length, maxFrames);
                framesTruncated = frames.length > limit;
                for (int i = 0; i < limit; i++) {
                    if (i != 0) text.append(',');
                    StackTraceElement frame = frames[i];
                    if (frame == null) {
                        text.append("null");
                        framesMalformed = true;
                        continue;
                    }
                    text.append('{');
                    field(text, "class", frame.getClassName(), FIELD_CHARS);
                    text.append(','); field(text, "method", frame.getMethodName(), FIELD_CHARS);
                    text.append(','); field(text, "file", frame.getFileName(), FIELD_CHARS);
                    text.append(",\"line\":").append(frame.getLineNumber()).append('}');
                }
            }
        }
        text.append("],\"framesCollected\":").append(framesCollected);
        if (framesCollected) text.append(",\"framesTruncated\":").append(framesTruncated);
        if (framesError) text.append(",\"framesError\":true");
        if (framesMalformed) text.append(",\"framesMalformed\":true");
        text.append("}\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder text, String name, String value, int maxChars) {
        text.append('"').append(name).append("\":");
        if (value == null) {
            text.append("null");
            return;
        }
        text.append('"');
        int limit = Math.min(value.length(), maxChars);
        // Do not truncate between a valid UTF-16 surrogate pair.
        if (limit < value.length() && limit > 0 && Character.isHighSurrogate(value.charAt(limit - 1))
                && Character.isLowSurrogate(value.charAt(limit))) limit--;
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                text.append('\\').append(c);
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < limit && Character.isLowSurrogate(value.charAt(i + 1))) {
                    unicode(text, c);
                    unicode(text, value.charAt(++i));
                } else {
                    unicode(text, '\ufffd');
                }
            } else if (Character.isLowSurrogate(c)) {
                unicode(text, '\ufffd');
            } else if (c < 0x20) {
                unicode(text, c);
            } else {
                text.append(c);
            }
        }
        text.append('"');
        if (value.length() > limit) text.append(",\"").append(name).append("Truncated\":true");
    }

    private static void unicode(StringBuilder text, char c) {
        text.append("\\u");
        String hex = Integer.toHexString(c);
        for (int pad = hex.length(); pad < 4; pad++) text.append('0');
        text.append(hex);
    }
}
