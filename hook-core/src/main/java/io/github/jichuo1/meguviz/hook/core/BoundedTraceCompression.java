package io.github.jichuo1.meguviz.hook.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Optional, bounded compression for explicitly paired trace writers. This is a separate binary
 * frame format, not JSONL or Binder protocol negotiation. Both endpoints must opt in to this
 * format and agree on limits; never send these frames to an unmodified JSONL consumer.
 *
 * <p>Use only on caller-owned workers, not hook callbacks or Binder threads. CRC32 detects
 * accidental corruption but provides neither authentication nor confidentiality. Callers must
 * authenticate the sender and enforce rate, memory, privacy and storage limits independently.
 */
public final class BoundedTraceCompression {
    public static final int HEADER_BYTES = 20;
    public static final int MAX_PLAIN_BYTES = 256 * 1024;
    public static final int MAX_FRAME_BYTES = MAX_PLAIN_BYTES + HEADER_BYTES;

    private static final int MAGIC = 0x4d485443; // MHTC
    private static final int VERSION = 1;
    private static final int RAW = 0;
    private static final int ZLIB = 1;
    private static final int TRACE_SCHEMA = 2;
    // Public format constant; changing these bytes requires a new frame method/version.
    private static final byte[] DICTIONARY = ("\"version\":1,\"source\":\"target-process\",\"hook\":\""
            + "\",\"generation\":,\"timeNanos\":,\"stage\":\"BEFORE\",\"stage\":\"AFTER\","
            + "\"stage\":\"AROUND\",\"stage\":\"OBSERVED_THROWABLE\",\"type\":\"java.lang."
            + "RuntimeException\",\"message\":\"\",\"frames\":[{\"class\":\"com.example."
            + "\",\"method\":\"invoke\",\"file\":\"Example.java\",\"line\":}],"
            + "\"framesCollected\":true,\"framesTruncated\":false}\n").getBytes(StandardCharsets.UTF_8);

    /** Selects the candidate to try; raw frames remain a possible outcome. */
    public enum Algorithm { ZLIB, TRACE_SCHEMA_V1 }

    /** Explains the choice without treating an unattempted compression as incompressible. */
    public enum Decision { BELOW_THRESHOLD, INSUFFICIENT_SAVINGS, COMPRESSED, COMPRESSED_TO_FIT }

    public static final class Encoding {
        private final byte[] frame;
        private final int plainBytes;
        private final Decision decision;

        private Encoding(byte[] frame, int plainBytes, Decision decision) {
            this.frame = frame;
            this.plainBytes = plainBytes;
            this.decision = decision;
        }

        /** Returns an independent copy. */
        public byte[] bytes() { return frame.clone(); }
        public int plainBytes() { return plainBytes; }
        public int frameBytes() { return frame.length; }
        /** Signed saving versus unframed input; raw frames add {@link #HEADER_BYTES} bytes. */
        public int netSavingBytes() { return plainBytes - frame.length; }
        public boolean compressed() { return decision == Decision.COMPRESSED || decision == Decision.COMPRESSED_TO_FIT; }
        public Decision reason() { return decision; }
    }

    private final int maxPlainBytes;
    private final int maxFrameBytes;
    private final int thresholdBytes;
    private final int minSavingBytes;
    private final Algorithm algorithm;

    /**
     * The minimum saving is compared against unframed input and includes the frame header;
     * raw frames always add {@link #HEADER_BYTES} bytes. Set a threshold appropriate to the
     * workload; no sampling is used. If a raw frame exceeds the frame limit, compression is
     * attempted even below the threshold and may be chosen without the minimum net saving.
     */
    public BoundedTraceCompression(int maxPlainBytes, int maxFrameBytes, int thresholdBytes,
                                   int minSavingBytes, Algorithm algorithm) {
        if (maxPlainBytes < 0 || maxPlainBytes > MAX_PLAIN_BYTES
                || maxFrameBytes < HEADER_BYTES || maxFrameBytes > MAX_FRAME_BYTES
                || thresholdBytes < 0 || minSavingBytes < 0) {
            throw new IllegalArgumentException("invalid compression limits");
        }
        this.maxPlainBytes = maxPlainBytes;
        this.maxFrameBytes = maxFrameBytes;
        this.thresholdBytes = thresholdBytes;
        this.minSavingBytes = minSavingBytes;
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
    }

    /**
     * Returns a complete independent frame or fails without producing a partial result. A bounded
     * snapshot is used consistently for compression and checksum; callers should avoid mutating
     * input while it is copied, because copying a concurrently modified array is not atomic.
     */
    public Encoding encode(byte[] input) throws IOException {
        Objects.requireNonNull(input, "input");
        if (input.length > maxPlainBytes) throw new IOException("plain trace exceeds limit");
        input = input.clone();
        boolean rawFits = input.length <= maxFrameBytes - HEADER_BYTES;
        boolean attempt = input.length > 0 && (input.length >= thresholdBytes || !rawFits);
        byte[] payload = input;
        int method = RAW;
        Decision decision = attempt ? Decision.INSUFFICIENT_SAVINGS : Decision.BELOW_THRESHOLD;
        if (attempt) {
            // Require a strict net improvement unless the only alternative is an oversized frame.
            long usefulLimit = (long) input.length - HEADER_BYTES - Math.max(1, minSavingBytes);
            int maxCandidate = rawFits
                    ? (int) Math.min((long) maxFrameBytes - HEADER_BYTES, Math.max(0L, usefulLimit))
                    : maxFrameBytes - HEADER_BYTES;
            if (maxCandidate > 0) {
                byte[] candidate = new byte[maxCandidate];
                Deflater compressor = new Deflater(Deflater.BEST_SPEED);
                try {
                    if (algorithm == Algorithm.TRACE_SCHEMA_V1) compressor.setDictionary(DICTIONARY);
                    compressor.setInput(input);
                    compressor.finish();
                    int used = 0;
                    while (!compressor.finished() && used < candidate.length) {
                        int written = compressor.deflate(candidate, used, candidate.length - used);
                        if (written == 0) break;
                        used += written;
                    }
                    if (compressor.finished()) {
                        payload = new byte[used];
                        System.arraycopy(candidate, 0, payload, 0, used);
                        method = algorithm == Algorithm.TRACE_SCHEMA_V1 ? TRACE_SCHEMA : ZLIB;
                        decision = rawFits ? Decision.COMPRESSED : Decision.COMPRESSED_TO_FIT;
                    }
                } finally {
                    compressor.end();
                }
            }
        }
        if (method == RAW && !rawFits) throw new IOException("trace frame exceeds limit");
        CRC32 crc = new CRC32();
        crc.update(input);
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(MAGIC).put((byte) VERSION).put((byte) method).putShort((short) 0);
        frame.putInt(input.length).putInt(payload.length).putInt((int) crc.getValue()).put(payload);
        return new Encoding(frame.array(), input.length, decision);
    }

    /**
     * Rejects unknown formats, bad bounds, trailing bytes, expansion beyond the limit and CRC
     * errors. A bounded snapshot is used for header validation and payload decoding.
     */
    public byte[] decode(byte[] frame) throws IOException {
        if (frame == null || frame.length < HEADER_BYTES || frame.length > maxFrameBytes) {
            throw new IOException("invalid trace frame size");
        }
        frame = frame.clone();
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        if (header.getInt() != MAGIC || (header.get() & 255) != VERSION) throw new IOException("trace frame version");
        int method = header.get() & 255;
        if (header.getShort() != 0 || (method != RAW && method != ZLIB && method != TRACE_SCHEMA)) {
            throw new IOException("unsupported trace frame method");
        }
        int plainLength = header.getInt();
        int payloadLength = header.getInt();
        int expectedCrc = header.getInt();
        if (plainLength < 0 || plainLength > maxPlainBytes || payloadLength < 0
                || payloadLength != frame.length - HEADER_BYTES) throw new IOException("invalid trace lengths");
        if (method == RAW && plainLength != payloadLength) throw new IOException("raw trace length mismatch");
        if (method != RAW && (plainLength == 0 || payloadLength == 0)) throw new IOException("empty compressed trace");
        byte[] plain = new byte[plainLength];
        if (method == RAW) {
            System.arraycopy(frame, HEADER_BYTES, plain, 0, plainLength);
        } else {
            inflate(frame, method, plain);
        }
        CRC32 crc = new CRC32();
        crc.update(plain);
        if ((int) crc.getValue() != expectedCrc) throw new IOException("trace checksum mismatch");
        return plain;
    }

    private static void inflate(byte[] frame, int method, byte[] plain) throws IOException {
        Inflater inflater = new Inflater();
        boolean dictionarySet = false;
        try {
            inflater.setInput(frame, HEADER_BYTES, frame.length - HEADER_BYTES);
            int used = 0;
            while (used < plain.length) {
                int count = inflater.inflate(plain, used, plain.length - used);
                if (count > 0) { used += count; continue; }
                if (inflater.needsDictionary() && method == TRACE_SCHEMA && !dictionarySet) {
                    try { inflater.setDictionary(DICTIONARY); }
                    catch (IllegalArgumentException wrong) { throw new IOException("trace dictionary mismatch", wrong); }
                    dictionarySet = true;
                    continue;
                }
                throw new IOException("short or invalid compressed trace");
            }
            byte[] extra = new byte[1];
            if (!inflater.finished() && inflater.inflate(extra) != 0) throw new IOException("trace expansion overflow");
            if (!inflater.finished() || inflater.getRemaining() != 0 || (method == TRACE_SCHEMA && !dictionarySet)) {
                throw new IOException("incomplete or trailing compressed trace");
            }
        } catch (DataFormatException invalid) {
            throw new IOException("invalid compressed trace", invalid);
        } finally {
            inflater.end();
        }
    }

    /** Caller must arrange format agreement and worker execution; writer exceptions propagate. */
    public JsonLineTraceTransport.RecordWriter encodingWriter(JsonLineTraceTransport.RecordWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return record -> writer.writeRecord(encode(record).frame);
    }

    /** Call from a bounded worker after authentication, not directly on a Binder thread. */
    public JsonLineTraceTransport.RecordWriter decodingWriter(JsonLineTraceTransport.RecordWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return frame -> writer.writeRecord(decode(frame));
    }
}
