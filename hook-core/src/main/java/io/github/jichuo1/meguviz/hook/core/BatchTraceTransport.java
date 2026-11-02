package io.github.jichuo1.meguviz.hook.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Explicit synchronous framing of bounded JSONL batches over a caller-provided record writer.
 * The writer must return only after the entire frame has been accepted by the next receiver, not
 * merely queued in the sender. No thread, timer, IPC connection, persistence or retry is created.
 * Both endpoints must explicitly opt into this format; it is not a JSONL record or an old protocol.
 */
public final class BatchTraceTransport {
    private static final int MAGIC = 0x4d484231; // MHB1
    private static final int HEADER_BYTES = 8;
    private static final int MAX_FRAME_BYTES = 65536;
    private static final int MAX_RECORDS = 1024;

    private BatchTraceTransport() { }

    /** Called once after the entire frame is validated; normal return accepts the complete batch. */
    public interface BatchWriter {
        void writeBatch(List<byte[]> records) throws Exception;
    }

    /**
     * Encode the largest prefix that fits in a single bounded frame. Call via deliverBatch on a
     * caller-owned worker. A single oversized head fails without sending; it must be handled by
     * the caller. A writer failure may follow an external side effect, so retry may duplicate it.
     */
    public static CallbackExceptionTracker.BatchTransport sender(
            JsonLineTraceTransport.RecordWriter writer, boolean includeMessage, int maxFrames,
            int maxFrameBytes, int maxRecords) {
        Objects.requireNonNull(writer, "writer");
        checkBounds(maxFrameBytes, maxRecords);
        if (maxFrames < 0 || maxFrames > 32) throw new IllegalArgumentException("maxFrames must be 0..32");
        return events -> {
            Objects.requireNonNull(events, "events");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxFrameBytes, 1024));
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(0); // count is filled only after framing completes
            int count = 0;
            for (CallbackExceptionTracker.Event event : events) {
                if (count == maxRecords || maxFrameBytes - bytes.size() < 5) break;
                byte[] record = JsonLineTraceTransport.encode(event, includeMessage, maxFrames);
                if (record.length == 0 || record.length > maxFrameBytes - HEADER_BYTES - 4) {
                    if (count == 0) throw new IllegalArgumentException("trace record does not fit batch frame");
                    break;
                }
                if (record.length > maxFrameBytes - bytes.size() - 4) break;
                out.writeInt(record.length);
                out.write(record);
                count++;
            }
            if (count == 0) throw new IllegalArgumentException("empty batch");
            byte[] frame = bytes.toByteArray();
            putInt(frame, 4, count);
            writer.writeRecord(frame);
            return count;
        };
    }

    /**
     * Build the matching receiver for a bounded record channel, e.g. BinderTrace102.receiver.
     * Validation happens before the single batch callback, which should transfer the whole batch
     * to a bounded receiver queue. The callback can have partial side effects before throwing.
     * Its return is not proof of durable storage, uniqueness, peer identity or valid JSON contents.
     */
    public static JsonLineTraceTransport.RecordWriter receiver(BatchWriter writer,
                                                               int maxFrameBytes, int maxRecords) {
        Objects.requireNonNull(writer, "writer");
        checkBounds(maxFrameBytes, maxRecords);
        return incoming -> {
            Objects.requireNonNull(incoming, "frame");
            if (incoming.length < HEADER_BYTES || incoming.length > maxFrameBytes) {
                throw new IllegalArgumentException("invalid batch frame");
            }
            // A local caller may retain and mutate its array while parsing; use one bounded snapshot.
            byte[] frame = incoming.clone();
            if (readInt(frame, 0) != MAGIC) throw new IllegalArgumentException("invalid batch frame");
            int count = readInt(frame, 4);
            if (count < 1 || count > maxRecords || count > (frame.length - HEADER_BYTES) / 5) {
                throw new IllegalArgumentException("invalid batch count");
            }
            // First pass rejects truncation, overflow and trailing data before allocating records.
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
            writer.writeBatch(Collections.unmodifiableList(records));
        };
    }

    private static void checkBounds(int maxFrameBytes, int maxRecords) {
        if (maxFrameBytes < HEADER_BYTES + 5 || maxFrameBytes > MAX_FRAME_BYTES
                || maxRecords < 1 || maxRecords > MAX_RECORDS) {
            throw new IllegalArgumentException("invalid batch bounds");
        }
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
}
