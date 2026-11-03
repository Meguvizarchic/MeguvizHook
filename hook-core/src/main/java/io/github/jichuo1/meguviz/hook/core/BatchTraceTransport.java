package io.github.jichuo1.meguviz.hook.core;

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
    static final int MAX_FRAME_BYTES = 65536;
    static final int MIN_FRAME_BYTES = HEADER_BYTES + 5;
    private static final int MAX_RECORDS = 1024;
    // First frame-buffer allocation; it doubles (bounded by the frame limit) as records are added.
    private static final int INITIAL_FRAME_BYTES = 512;

    private BatchTraceTransport() { }

    /** Called once after the entire frame is validated; normal return accepts the complete batch. */
    public interface BatchWriter {
        void writeBatch(List<byte[]> records) throws Exception;
    }

    /**
     * Encode the largest prefix that fits in a single bounded frame. Call via deliverBatch on a
     * caller-owned worker. A single oversized head fails without sending; it must be handled by
     * the caller. A writer failure may follow an external side effect, so retry may duplicate it.
     * A {@link JsonLineTraceTransport.SlicedRecordWriter} receives the frame as a slice of the
     * sender's private, per-call buffer instead of an exact-length copy; the bytes are identical.
     */
    public static CallbackExceptionTracker.BatchTransport sender(
            JsonLineTraceTransport.RecordWriter writer, boolean includeMessage, int maxFrames,
            int maxFrameBytes, int maxRecords) {
        Objects.requireNonNull(writer, "writer");
        checkBounds(maxFrameBytes, maxRecords);
        if (maxFrames < 0 || maxFrames > 32) throw new IllegalArgumentException("maxFrames must be 0..32");
        JsonLineTraceTransport.SlicedRecordWriter sliced = writer instanceof JsonLineTraceTransport.SlicedRecordWriter
                ? (JsonLineTraceTransport.SlicedRecordWriter) writer : null;
        return events -> {
            Objects.requireNonNull(events, "events");
            // Records are encoded straight into one per-call frame buffer (no per-record arrays).
            // Iteration, getter calls, fit decisions and the resulting bytes are unchanged.
            JsonLineTraceTransport.Utf8 frame = JsonLineTraceTransport.Utf8.bounded(INITIAL_FRAME_BYTES, maxFrameBytes);
            frame.skip(HEADER_BYTES);
            int count = 0;
            for (CallbackExceptionTracker.Event event : events) {
                if (count == maxRecords || maxFrameBytes - frame.size() < 5) break;
                frame.beginRecord();
                JsonLineTraceTransport.write(frame, event, includeMessage, maxFrames);
                // A record fits only if it ends within maxFrameBytes; for the first record this is
                // the same as fitting an otherwise empty frame.
                if (!frame.endRecord()) {
                    if (count == 0) throw new IllegalArgumentException("trace record does not fit batch frame");
                    break;
                }
                count++;
            }
            if (count == 0) throw new IllegalArgumentException("empty batch");
            byte[] buffer = frame.buffer();
            putInt(buffer, 0, MAGIC);
            putInt(buffer, 4, count);
            if (sliced != null) sliced.writeRecord(buffer, 0, frame.size());
            else writer.writeRecord(frame.finish());
            return count;
        };
    }

    /**
     * Build the matching receiver for a bounded record channel, e.g. BinderTrace102.receiver.
     * Validation happens before the single batch callback, which should transfer the whole batch
     * to a bounded receiver queue. The callback can have partial side effects before throwing.
     * Its return is not proof of durable storage, uniqueness, peer identity or valid JSON contents.
     * Each input byte is read once (header and length words into a private length table, record
     * bytes into independent arrays), so a later or concurrent change to the caller's array cannot
     * separate validation from the delivered records; the input array itself is not retained.
     */
    public static JsonLineTraceTransport.RecordWriter receiver(BatchWriter writer,
                                                               int maxFrameBytes, int maxRecords) {
        return receiverInternal(writer, maxFrameBytes, maxRecords);
    }

    /**
     * Pair compression decoding and batch parsing on a caller-owned worker. This accepts the same
     * frames and invokes the writer under the same conditions as
     * {@code codec.decodingWriter(receiver(writer, maxFrameBytes, maxRecords))}. The decoder's
     * newly created plaintext stays internal; each record passed to the writer remains an
     * independent byte array. The codec validates the wire frame from a single read of each byte.
     * Normal return is not a durability guarantee.
     */
    public static JsonLineTraceTransport.RecordWriter compressedReceiver(BoundedTraceCompression codec,
            BatchWriter writer, int maxFrameBytes, int maxRecords) {
        JsonLineTraceTransport.RecordWriter receiver = receiverInternal(writer, maxFrameBytes, maxRecords);
        return Objects.requireNonNull(codec, "codec").decodingWriter(receiver);
    }

    /**
     * Session variant of {@link #compressedReceiver}. The session retains its existing owner-thread,
     * availability and close rules; neither this method nor the receiver creates a worker.
     */
    public static JsonLineTraceTransport.RecordWriter compressedSessionReceiver(BoundedTraceCompression.Session session,
            BatchWriter writer, int maxFrameBytes, int maxRecords) {
        JsonLineTraceTransport.RecordWriter receiver = receiverInternal(writer, maxFrameBytes, maxRecords);
        return Objects.requireNonNull(session, "session").decodingWriter(receiver);
    }

    /**
     * Serialized-session variant of {@link #compressedReceiver}. Codec operations may move
     * between threads but overlapping use is rejected; the batch writer runs outside the codec
     * lock and is responsible for its own synchronization and acceptance semantics.
     */
    public static JsonLineTraceTransport.RecordWriter compressedSerializedReceiver(
            BoundedTraceCompression.SerializedSession session, BatchWriter writer, int maxFrameBytes, int maxRecords) {
        JsonLineTraceTransport.RecordWriter receiver = receiverInternal(writer, maxFrameBytes, maxRecords);
        return Objects.requireNonNull(session, "session").decodingWriter(receiver);
    }

    private static JsonLineTraceTransport.RecordWriter receiverInternal(BatchWriter writer,
            int maxFrameBytes, int maxRecords) {
        Objects.requireNonNull(writer, "writer");
        checkBounds(maxFrameBytes, maxRecords);
        return incoming -> {
            Objects.requireNonNull(incoming, "frame");
            if (incoming.length < HEADER_BYTES || incoming.length > maxFrameBytes) {
                throw new IllegalArgumentException("invalid batch frame");
            }
            // Every input byte is read exactly once: header and length words into locals/lengths,
            // record bytes into their own arrays. Validation and output therefore use one
            // consistent view, as with a whole-frame snapshot, without copying the frame twice.
            if (readInt(incoming, 0) != MAGIC) throw new IllegalArgumentException("invalid batch frame");
            int count = readInt(incoming, 4);
            if (count < 1 || count > maxRecords || count > (incoming.length - HEADER_BYTES) / 5) {
                throw new IllegalArgumentException("invalid batch count");
            }
            // First pass rejects truncation, overflow and trailing data before allocating records.
            int[] lengths = new int[count];
            int position = HEADER_BYTES;
            for (int i = 0; i < count; i++) {
                if (incoming.length - position < 4) throw new IllegalArgumentException("truncated batch");
                int length = readInt(incoming, position);
                position += 4;
                if (length < 1 || length > incoming.length - position) {
                    throw new IllegalArgumentException("invalid batch record length");
                }
                lengths[i] = length;
                position += length;
            }
            if (position != incoming.length) throw new IllegalArgumentException("trailing batch data");
            List<byte[]> records = new ArrayList<byte[]>(count);
            position = HEADER_BYTES;
            for (int i = 0; i < count; i++) {
                int length = lengths[i];
                position += 4;
                byte[] record = new byte[length];
                System.arraycopy(incoming, position, record, 0, length);
                position += length;
                records.add(record);
            }
            writer.writeBatch(Collections.unmodifiableList(records));
        };
    }

    private static void checkBounds(int maxFrameBytes, int maxRecords) {
        if (maxFrameBytes < MIN_FRAME_BYTES || maxFrameBytes > MAX_FRAME_BYTES
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
