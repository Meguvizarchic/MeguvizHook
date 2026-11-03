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
    // Upper bound of any single field reservation (escaped message tail), used for frame slack.
    static final int MAX_RESERVATION = MESSAGE_CHARS * 3 + 12;
    // Size estimate for one record: literals, keys, quotes, flags and 20-digit numbers.
    private static final int FIXED_BYTES = 240;
    private static final int MESSAGE_KEY_BYTES = 34;
    private static final int FRAME_KEY_BYTES = 56;
    private static final int NULL_FRAME_BYTES = 5;
    private static final byte[] HEX = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };
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
        write(text, event, includeMessage, maxFrames);
        return text.finish();
    }

    /**
     * Append one complete JSONL record; maxFrames must already be validated. The bytes and the
     * order and number of Throwable getter calls ({@code getMessage} once if requested, then
     * {@code getStackTrace} once if frames are requested) are unchanged from the original
     * encoder. Both getters run before any byte is written so that the record can be sized with a
     * single reservation; the other inputs are final, side-effect-free accessors.
     */
    static void write(Utf8 text, CallbackExceptionTracker.Event event, boolean includeMessage, int maxFrames) {
        Throwable throwable = event.failure();
        String source = event.source();
        String hook = event.hookId().value();
        String stage = event.stage().name();
        String type = throwable.getClass().getName();
        String message = null;
        boolean messageError = false;
        if (includeMessage) {
            try {
                message = throwable.getMessage();
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                messageError = true;
            }
        }
        StackTraceElement[] frames = null;
        boolean framesError = false;
        if (maxFrames != 0) {
            try {
                frames = throwable.getStackTrace();
            } catch (Throwable failure) {
                if (TypeChecks.isFatal(failure)) throw TypeChecks.fatal(failure);
                framesError = true;
            }
            if (frames == null) framesError = true;
        }
        int limit = frames == null ? 0 : Math.min(frames.length, maxFrames);
        text.reserveRecord(estimate(source, hook, stage, type, includeMessage, message, frames, limit));

        text.ascii("{\"version\":1,");
        field(text, "source", source, FIELD_CHARS);
        text.ascii(','); field(text, "hook", hook, FIELD_CHARS);
        text.ascii(",\"generation\":").number(event.generation());
        text.ascii(",\"timeNanos\":").number(event.timeNanos());
        text.ascii(','); field(text, "stage", stage, FIELD_CHARS);
        text.ascii(','); field(text, "type", type, FIELD_CHARS);
        if (includeMessage) {
            text.ascii(','); field(text, "message", message, MESSAGE_CHARS);
            if (messageError) text.ascii(",\"messageError\":true");
        }
        text.ascii(",\"frames\":[");
        boolean framesCollected = frames != null;
        boolean framesTruncated = framesCollected && frames.length > limit;
        boolean framesMalformed = false;
        for (int i = 0; i < limit; i++) {
            if (i != 0) text.ascii(',');
            StackTraceElement frame = frames[i];
            if (frame == null) {
                text.ascii("null");
                framesMalformed = true;
                continue;
            }
            text.ascii('{');
            field(text, "class", frame.getClassName(), FIELD_CHARS);
            text.ascii(','); field(text, "method", frame.getMethodName(), FIELD_CHARS);
            text.ascii(','); field(text, "file", frame.getFileName(), FIELD_CHARS);
            text.ascii(",\"line\":").number(frame.getLineNumber()).ascii('}');
        }
        text.ascii("],\"framesCollected\":").bool(framesCollected);
        if (framesCollected) text.ascii(",\"framesTruncated\":").bool(framesTruncated);
        if (framesError) text.ascii(",\"framesError\":true");
        if (framesMalformed) text.ascii(",\"framesMalformed\":true");
        text.ascii("}\n");
    }

    /**
     * Upper bound of the record size when every string is plain ASCII (non-ASCII text reserves
     * more as it is written). StackTraceElement accessors are final and side-effect free; each
     * element is read again, once, when it is written.
     */
    private static int estimate(String source, String hook, String stage, String type, boolean includeMessage,
                                String message, StackTraceElement[] frames, int limit) {
        int bytes = FIXED_BYTES + bounded(source, FIELD_CHARS) + bounded(hook, FIELD_CHARS)
                + bounded(stage, FIELD_CHARS) + bounded(type, FIELD_CHARS);
        if (includeMessage) bytes += MESSAGE_KEY_BYTES + bounded(message, MESSAGE_CHARS);
        for (int i = 0; i < limit; i++) {
            StackTraceElement frame = frames[i];
            bytes += frame == null ? NULL_FRAME_BYTES : FRAME_KEY_BYTES + bounded(frame.getClassName(), FIELD_CHARS)
                    + bounded(frame.getMethodName(), FIELD_CHARS) + bounded(frame.getFileName(), FIELD_CHARS);
        }
        return bytes;
    }

    private static int bounded(String value, int maxChars) {
        return value == null ? 4 : Math.min(value.length(), maxChars);
    }

    private static void field(Utf8 text, String name, String value, int maxChars) {
        text.ascii('"').ascii(name).ascii("\":");
        if (value == null) {
            text.ascii("null");
            return;
        }
        int limit = Math.min(value.length(), maxChars);
        // Do not truncate between a valid UTF-16 surrogate pair.
        if (limit < value.length() && limit > 0 && Character.isHighSurrogate(value.charAt(limit - 1))
                && Character.isLowSurrogate(value.charAt(limit))) limit--;
        text.ascii('"');
        text.string(value, limit);
        text.ascii('"');
        if (value.length() > limit) text.ascii(",\"").ascii(name).ascii("Truncated\":true");
    }

    /**
     * Per-call JSON byte emitter; no buffer is reused or shared across calls. Every write is
     * preceded by one capacity reservation covering its worst case, so runs of plain ASCII are
     * copied without a per-character check. A bounded emitter (batch frames) discards the bytes
     * of a record once it can no longer fit, while the record's getters still run to completion.
     */
    static final class Utf8 {
        private byte[] bytes;
        private int size;
        private final int overflowAt;
        private final int maxCapacity;
        private int recordStart = -1;
        private boolean overflowed;

        /** Unbounded emitter; the first reservation allocates the estimated record size. */
        Utf8() {
            this(0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        private Utf8(int initialCapacity, int overflowAt, int maxCapacity) {
            this.bytes = new byte[initialCapacity];
            this.overflowAt = overflowAt;
            this.maxCapacity = maxCapacity;
        }

        /** Emitter for one bounded batch frame; capacity stays below limit + one reservation. */
        static Utf8 bounded(int initialCapacity, int limit) {
            return new Utf8(Math.min(initialCapacity, limit), limit, limit + MAX_RESERVATION + 64);
        }

        int size() { return size; }

        void skip(int count) {
            reserve(count);
            size += count;
        }

        /** Start a length-prefixed record; the four-byte length is filled by endRecord. */
        void beginRecord() {
            recordStart = size;
            skip(4);
        }

        /** Returns false and rolls back when the record did not fit before the bound. */
        boolean endRecord() {
            int start = recordStart;
            recordStart = -1;
            if (overflowed || size > overflowAt) {
                overflowed = false;
                size = start;
                return false;
            }
            putInt(bytes, start, size - start - 4);
            return true;
        }

        /**
         * Reserve the estimated record size. A bounded emitter never reserves beyond its frame
         * bound for this, so a record that turns out not to fit does not grow the frame buffer.
         */
        void reserveRecord(int estimate) {
            reserve(Math.max(0, Math.min(estimate, overflowAt - size)));
        }

        private void reserve(int count) {
            if (size > overflowAt && recordStart >= 0) {
                // The current record already exceeds the frame bound: keep encoding (the getters
                // must still run) but overwrite its bytes instead of growing the frame.
                overflowed = true;
                size = recordStart + 4;
            }
            int required = size + count;
            if (required < 0) throw new OutOfMemoryError("encoded trace too large");
            if (required > bytes.length) grow(required);
        }

        private void grow(int required) {
            int expanded = bytes.length * 2 + 2;
            if (expanded < 0 || expanded > maxCapacity) expanded = maxCapacity;
            if (expanded < required) expanded = required;
            bytes = Arrays.copyOf(bytes, expanded);
        }

        // Callers pass ASCII JSON literals/field names only; user text goes through string().
        Utf8 ascii(String value) {
            int length = value.length();
            reserve(length);
            byte[] out = bytes;
            int position = size;
            for (int i = 0; i < length; i++) out[position++] = (byte) value.charAt(i);
            size = position;
            return this;
        }

        Utf8 ascii(char c) {
            reserve(1);
            bytes[size++] = (byte) c;
            return this;
        }

        Utf8 bool(boolean value) { return ascii(value ? "true" : "false"); }

        Utf8 number(long value) {
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

        /** Escaped JSON string content for value[0, limit), without quotes. */
        void string(String value, int limit) {
            reserve(limit);
            byte[] out = bytes;
            int position = size;
            int i = 0;
            while (i < limit) {
                char c = value.charAt(i);
                if (c < 0x20 || c > 0x7f || c == '"' || c == '\\') break;
                out[position++] = (byte) c;
                i++;
            }
            size = position;
            if (i < limit) escapedTail(value, i, limit);
        }

        private void escapedTail(String value, int start, int limit) {
            // Budget: three bytes per remaining UTF-16 unit covers every encoding except \\uXXXX
            // escapes (control characters and surrogates), which top the budget up as they occur.
            reserve((limit - start) * 3);
            byte[] out = bytes;
            int position = size;
            for (int i = start; i < limit; i++) {
                char c = value.charAt(i);
                if (c >= 0x20 && c <= 0x7f && c != '"' && c != '\\') {
                    out[position++] = (byte) c;
                } else if (c == '"' || c == '\\') {
                    out[position++] = (byte) '\\';
                    out[position++] = (byte) c;
                } else if (c >= 0x80 && c < 0x800) {
                    out[position++] = (byte) (0xc0 | (c >>> 6));
                    out[position++] = (byte) (0x80 | (c & 0x3f));
                } else if (c >= 0x800 && !Character.isSurrogate(c)) {
                    out[position++] = (byte) (0xe0 | (c >>> 12));
                    out[position++] = (byte) (0x80 | ((c >>> 6) & 0x3f));
                    out[position++] = (byte) (0x80 | (c & 0x3f));
                } else {
                    // Up to twelve bytes for this unit (or pair) plus the budget for the rest.
                    size = position;
                    reserve(12 + (limit - i - 1) * 3);
                    out = bytes;
                    position = size;
                    if (c < 0x20) {
                        position = unicode(out, position, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < limit && Character.isLowSurrogate(value.charAt(i + 1))) {
                            position = unicode(out, position, c);
                            position = unicode(out, position, value.charAt(++i));
                        } else {
                            position = unicode(out, position, '\ufffd');
                        }
                    } else {
                        position = unicode(out, position, '\ufffd'); // unpaired low surrogate
                    }
                }
            }
            size = position;
        }

        private static int unicode(byte[] out, int position, char c) {
            out[position++] = (byte) '\\';
            out[position++] = (byte) 'u';
            out[position++] = HEX[(c >>> 12) & 15];
            out[position++] = HEX[(c >>> 8) & 15];
            out[position++] = HEX[(c >>> 4) & 15];
            out[position++] = HEX[c & 15];
            return position;
        }

        byte[] finish() { return size == bytes.length ? bytes : Arrays.copyOf(bytes, size); }
    }

    static void putInt(byte[] bytes, int position, int value) {
        bytes[position] = (byte) (value >>> 24);
        bytes[position + 1] = (byte) (value >>> 16);
        bytes[position + 2] = (byte) (value >>> 8);
        bytes[position + 3] = (byte) value;
    }
}
