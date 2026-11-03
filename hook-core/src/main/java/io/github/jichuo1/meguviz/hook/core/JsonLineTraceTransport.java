package io.github.jichuo1.meguviz.hook.core;

import java.util.Arrays;
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
    private static final String HEX = "0123456789abcdef";
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
        Utf8 text = new Utf8();
        text.appendAscii("{\"version\":1,");
        field(text, "source", event.source(), FIELD_CHARS);
        text.append(','); field(text, "hook", event.hookId().value(), FIELD_CHARS);
        text.appendAscii(",\"generation\":").append(event.generation());
        text.appendAscii(",\"timeNanos\":").append(event.timeNanos());
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
            if (messageError) text.appendAscii(",\"messageError\":true");
        }
        text.appendAscii(",\"frames\":[");
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
                        text.appendAscii("null");
                        framesMalformed = true;
                        continue;
                    }
                    text.append('{');
                    field(text, "class", frame.getClassName(), FIELD_CHARS);
                    text.append(','); field(text, "method", frame.getMethodName(), FIELD_CHARS);
                    text.append(','); field(text, "file", frame.getFileName(), FIELD_CHARS);
                    text.appendAscii(",\"line\":").append(frame.getLineNumber()).append('}');
                }
            }
        }
        text.appendAscii("],\"framesCollected\":").append(framesCollected);
        if (framesCollected) text.appendAscii(",\"framesTruncated\":").append(framesTruncated);
        if (framesError) text.appendAscii(",\"framesError\":true");
        if (framesMalformed) text.appendAscii(",\"framesMalformed\":true");
        text.appendAscii("}\n");
        return text.finish();
    }

    private static void field(Utf8 text, String name, String value, int maxChars) {
        text.append('"').appendAscii(name).appendAscii("\":");
        if (value == null) {
            text.appendAscii("null");
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
        if (value.length() > limit) text.appendAscii(",\"").appendAscii(name).appendAscii("Truncated\":true");
    }

    private static void unicode(Utf8 text, char c) {
        text.appendAscii("\\u");
        text.append(HEX.charAt((c >>> 12) & 15));
        text.append(HEX.charAt((c >>> 8) & 15));
        text.append(HEX.charAt((c >>> 4) & 15));
        text.append(HEX.charAt(c & 15));
    }

    /** Per-call JSON byte emitter; no buffer is reused or shared across calls. */
    private static final class Utf8 {
        private byte[] bytes = new byte[16];
        private int size;

        private void reserve(int count) {
            int required = size + count;
            if (required < 0) throw new OutOfMemoryError("encoded trace too large");
            if (required > bytes.length) {
                int expanded = bytes.length * 2 + 2;
                if (expanded < required) expanded = required;
                if (expanded < 0) expanded = Integer.MAX_VALUE;
                bytes = Arrays.copyOf(bytes, expanded);
            }
        }

        Utf8 append(char c) {
            if (c <= 0x7f) {
                reserve(1); bytes[size++] = (byte) c;
            } else if (c <= 0x7ff) {
                reserve(2); bytes[size++] = (byte) (0xc0 | (c >>> 6));
                bytes[size++] = (byte) (0x80 | (c & 0x3f));
            } else if (c >= 0xd800 && c <= 0xdfff) {
                // Current field() always escapes surrogate code units before appending them.
                reserve(1); bytes[size++] = (byte) '?';
            } else {
                reserve(3); bytes[size++] = (byte) (0xe0 | (c >>> 12));
                bytes[size++] = (byte) (0x80 | ((c >>> 6) & 0x3f));
                bytes[size++] = (byte) (0x80 | (c & 0x3f));
            }
            return this;
        }

        // Current callers pass ASCII JSON literals/field names only. User text goes through
        // field(), which escapes every surrogate before invoking append(char). This is not a
        // general-purpose UTF-8 String encoder.
        Utf8 appendAscii(String value) {
            if (value == null) value = "null";
            for (int i = 0; i < value.length(); i++) append(value.charAt(i));
            return this;
        }

        Utf8 append(boolean value) { return appendAscii(value ? "true" : "false"); }
        Utf8 append(int value) { return append((long) value); }

        Utf8 append(long value) {
            boolean negative = value < 0;
            long n = negative ? value : -value; // Keeps Long.MIN_VALUE representable.
            long cursor = n;
            int digits = 1;
            while (cursor <= -10) { cursor /= 10; digits++; }
            reserve(digits + (negative ? 1 : 0));
            if (negative) bytes[size++] = (byte) '-';
            int end = size + digits;
            int position = end;
            while (n <= -10) {
                long quotient = n / 10;
                bytes[--position] = (byte) ('0' - (n - quotient * 10));
                n = quotient;
            }
            bytes[--position] = (byte) ('0' - n);
            size = end;
            return this;
        }

        byte[] finish() { return size == bytes.length ? bytes : Arrays.copyOf(bytes, size); }
    }
}
