package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Benchmark-only copies of the round-0 JSONL encoder, batch sender/receiver, stateless RAW decode
 * and tracker queue snapshot/removal, so old and new code can be compared in the same run on a
 * noisy host. The authoritative oracle used by tests lives in hook-testing (LegacyTraceCodec).
 */
final class LegacyTransport {
    private LegacyTransport() { }

    private static final int FIELD_CHARS = 128;
    private static final int MESSAGE_CHARS = 512;
    private static final String HEX = "0123456789abcdef";
    private static final int MAGIC = 0x4d484231;
    private static final int HEADER_BYTES = 8;

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static RuntimeException fatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        throw (ThreadDeath) failure;
    }

    static byte[] encode(CallbackExceptionTracker.Event event, boolean includeMessage, int maxFrames) {
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
                if (isFatal(failure)) throw fatal(failure);
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
                if (isFatal(failure)) throw fatal(failure);
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
                reserve(1); bytes[size++] = (byte) '?';
            } else {
                reserve(3); bytes[size++] = (byte) (0xe0 | (c >>> 12));
                bytes[size++] = (byte) (0x80 | ((c >>> 6) & 0x3f));
                bytes[size++] = (byte) (0x80 | (c & 0x3f));
            }
            return this;
        }

        Utf8 appendAscii(String value) {
            if (value == null) value = "null";
            for (int i = 0; i < value.length(); i++) append(value.charAt(i));
            return this;
        }

        Utf8 append(boolean value) { return appendAscii(value ? "true" : "false"); }
        Utf8 append(int value) { return append((long) value); }

        Utf8 append(long value) {
            boolean negative = value < 0;
            long n = negative ? value : -value;
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

    /** Round-0 BatchTraceTransport.sender body (bounds already validated by the caller). */
    static int send(List<CallbackExceptionTracker.Event> events, boolean includeMessage, int maxFrames,
                    int maxFrameBytes, int maxRecords, List<byte[]> written) {
        Objects.requireNonNull(events, "events");
        List<byte[]> records = new ArrayList<byte[]>();
        int size = HEADER_BYTES;
        int count = 0;
        for (CallbackExceptionTracker.Event event : events) {
            if (count == maxRecords || maxFrameBytes - size < 5) break;
            byte[] record = encode(event, includeMessage, maxFrames);
            if (record.length == 0 || record.length > maxFrameBytes - HEADER_BYTES - 4) {
                if (count == 0) throw new IllegalArgumentException("trace record does not fit batch frame");
                break;
            }
            if (record.length > maxFrameBytes - size - 4) break;
            records.add(record);
            size += 4 + record.length;
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("empty batch");
        byte[] frame = new byte[size];
        putInt(frame, 0, MAGIC);
        putInt(frame, 4, count);
        int position = HEADER_BYTES;
        for (byte[] record : records) {
            putInt(frame, position, record.length);
            position += 4;
            System.arraycopy(record, 0, frame, position, record.length);
            position += record.length;
        }
        written.add(frame);
        return count;
    }

    /** Round-0 BatchTraceTransport receiver body (snapshotting variant). */
    static List<byte[]> receive(byte[] incoming, int maxFrameBytes, int maxRecords) {
        Objects.requireNonNull(incoming, "frame");
        if (incoming.length < HEADER_BYTES || incoming.length > maxFrameBytes) {
            throw new IllegalArgumentException("invalid batch frame");
        }
        byte[] frame = incoming.clone();
        if (readInt(frame, 0) != MAGIC) throw new IllegalArgumentException("invalid batch frame");
        int count = readInt(frame, 4);
        if (count < 1 || count > maxRecords || count > (frame.length - HEADER_BYTES) / 5) {
            throw new IllegalArgumentException("invalid batch count");
        }
        int position = HEADER_BYTES;
        for (int i = 0; i < count; i++) {
            if (frame.length - position < 4) throw new IllegalArgumentException("truncated batch");
            int length = readInt(frame, position);
            position += 4;
            if (length < 1 || length > frame.length - position) {
                throw new IllegalArgumentException("invalid batch record length");
            }
            position += length;
        }
        if (position != frame.length) throw new IllegalArgumentException("trailing batch data");
        List<byte[]> records = new ArrayList<byte[]>(count);
        position = HEADER_BYTES;
        for (int i = 0; i < count; i++) {
            int length = readInt(frame, position);
            position += 4;
            byte[] record = new byte[length];
            System.arraycopy(frame, position, record, 0, length);
            position += length;
            records.add(record);
        }
        return Collections.unmodifiableList(records);
    }

    private static int readInt(byte[] bytes, int position) {
        return (bytes[position] & 255) << 24 | (bytes[position + 1] & 255) << 16
                | (bytes[position + 2] & 255) << 8 | bytes[position + 3] & 255;
    }

    private static void putInt(byte[] bytes, int position, int value) {
        bytes[position] = (byte) (value >>> 24);
        bytes[position + 1] = (byte) (value >>> 16);
        bytes[position + 2] = (byte) (value >>> 8);
        bytes[position + 3] = (byte) value;
    }
    /** Round-0 stateless decode restricted to RAW frames (clone, then copy, then CRC). */
    static byte[] decodeRaw(byte[] frame, int maxPlainBytes, int maxFrameBytes) throws java.io.IOException {
        if (frame == null || frame.length < 20 || frame.length > maxFrameBytes) {
            throw new java.io.IOException("invalid trace frame size");
        }
        frame = frame.clone();
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(frame).order(java.nio.ByteOrder.BIG_ENDIAN);
        if (header.getInt() != 0x4d485443 || (header.get() & 255) != 1) throw new java.io.IOException("trace frame version");
        int method = header.get() & 255;
        if (header.getShort() != 0 || method != 0) throw new java.io.IOException("unsupported trace frame method");
        int plainLength = header.getInt();
        int payloadLength = header.getInt();
        int expectedCrc = header.getInt();
        if (plainLength < 0 || plainLength > maxPlainBytes || payloadLength != frame.length - 20
                || plainLength != payloadLength) throw new java.io.IOException("invalid trace lengths");
        byte[] plain = new byte[plainLength];
        System.arraycopy(frame, 20, plain, 0, plainLength);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(plain);
        if ((int) crc.getValue() != expectedCrc) throw new java.io.IOException("trace checksum mismatch");
        return plain;
    }

    /** Round-0 tracker snapshot: one queue-lock acquisition per iterator step. */
    static <E> List<E> snapshotByIteration(java.util.concurrent.ArrayBlockingQueue<E> pending, int limit) {
        List<E> batch = new ArrayList<E>(Math.min(limit, pending.size()));
        for (E event : pending) {
            if (batch.size() == limit) break;
            batch.add(event);
        }
        return batch.isEmpty() ? null : Collections.unmodifiableList(batch);
    }

    /** Round-0 tracker prefix removal: one poll (queue-lock acquisition) per accepted record. */
    static <E> void removeByPolling(java.util.concurrent.ArrayBlockingQueue<E> pending, List<E> batch, int accepted) {
        for (int i = 0; i < accepted; i++) {
            if (pending.poll() != batch.get(i)) throw new IllegalStateException("trace queue ownership lost");
        }
    }
}
