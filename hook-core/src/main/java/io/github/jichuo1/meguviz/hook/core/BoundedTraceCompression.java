package io.github.jichuo1.meguviz.hook.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final int SESSION_SCRATCH_BYTES = 16 * 1024;

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

    /**
     * Output-buffer allocation policy for an explicitly created session. STAGED starts large
     * compression attempts in bounded session scratch and expands only if necessary; it still
     * submits the whole input under the same candidate limit. It can retain scratch until close and
     * can add an allocation and copy for incompressible inputs. STANDARD is the default.
     */
    public enum OutputBufferStrategy { STANDARD, STAGED }

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
     * Largest batch-frame limit that can fit this codec and the supplied wire-record limit even
     * when compression falls back to RAW. Use the returned value as the batch sender's frame
     * limit; the receiver must independently use compatible limits. This only budgets byte
     * lengths, and does not guarantee that an individual record fits or that the wire accepts it.
     *
     * @throws IllegalArgumentException if the limits cannot hold the smallest batch frame
     */
    public int rawSafeBatchLimit(int wireMaxRecordBytes) {
        if (wireMaxRecordBytes < HEADER_BYTES) {
            throw new IllegalArgumentException("wire limit cannot hold a compressed frame");
        }
        int limit = Math.min(BatchTraceTransport.MAX_FRAME_BYTES, Math.min(maxPlainBytes,
                Math.min(maxFrameBytes - HEADER_BYTES, wireMaxRecordBytes - HEADER_BYTES)));
        if (limit < BatchTraceTransport.MIN_FRAME_BYTES) {
            throw new IllegalArgumentException("limits cannot hold a batch frame");
        }
        return limit;
    }

    /**
     * Create an optional reusable compression session owned by the calling thread. The caller must
     * close it, including when processing fails. Stateless encode/decode remain independent of it.
     */
    public Session newSession() { return new Session(); }

    /** Create a thread-owned session with an explicit output-buffer policy. */
    public Session newSession(OutputBufferStrategy strategy) {
        return new Session(Thread.currentThread(), Objects.requireNonNull(strategy, "strategy"));
    }

    /**
     * Create an explicitly serialized codec session that may be used by different threads.
     * Overlapping use, including close, is rejected rather than queued. The caller must close
     * this session and must coordinate worker and transport lifetimes separately.
     */
    public SerializedSession newSerializedSession() { return new SerializedSession(); }

    /** Create a serially reusable cross-thread session with an explicit output-buffer policy. */
    public SerializedSession newSerializedSession(OutputBufferStrategy strategy) {
        return new SerializedSession(Objects.requireNonNull(strategy, "strategy"));
    }

    /**
     * Explicit single-threaded native codec ownership; this object creates no worker or queue.
     * Deflater/Inflater are lazy and reset after every compressed frame, including failed frames;
     * each schema encoding installs the dictionary again. The frame format and limits are the
     * enclosing codec's. Cross-thread use, including close, is rejected. A failed native reset
     * permanently poisons the session, but close is still required to release its resources.
     * Reset does not promise secure erasure of native memory. A session is not safe to use from
     * a hook callback or Binder thread; use it on a caller-owned background worker.
     */
    public final class Session implements AutoCloseable {
        private final Thread owner;
        private final OutputBufferStrategy outputBufferStrategy;
        private Deflater deflater;
        private Inflater inflater;
        private final CRC32 crc = new CRC32();
        private byte[] retainedScratch;

        private byte[] scratchFor(int required) {
            if (required > SESSION_SCRATCH_BYTES) return new byte[required];
            if (retainedScratch == null || retainedScratch.length < required) {
                retainedScratch = new byte[required];
            }
            return retainedScratch;
        }
        private boolean closed;
        private boolean broken;
        private boolean busy;

        private Session() { this(Thread.currentThread(), OutputBufferStrategy.STANDARD); }

        // Only SerializedSession may use an unbound delegate, protected by its private lock.
        private Session(Thread owner, OutputBufferStrategy strategy) {
            this.owner = owner;
            this.outputBufferStrategy = strategy;
        }

        private void checkOwner() {
            if (owner != null && Thread.currentThread() != owner) throw new IllegalStateException("compression session belongs to another thread");
        }

        private void begin() {
            checkOwner();
            if (closed) throw new IllegalStateException("compression session is closed");
            if (broken) throw new IllegalStateException("compression session reset failed; close it");
            if (busy) throw new IllegalStateException("compression session is already in use");
            busy = true;
        }

        private Deflater deflater() {
            if (deflater == null) deflater = new Deflater(Deflater.BEST_SPEED);
            return deflater;
        }

        private Inflater inflater() {
            if (inflater == null) inflater = new Inflater();
            return inflater;
        }

        private void resetDeflater(Throwable primary) {
            try { deflater.reset(); }
            catch (RuntimeException | Error failure) { broken = true; cleanupFailure(primary, failure); }
        }

        private void resetInflater(Throwable primary) {
            try { inflater.reset(); }
            catch (RuntimeException | Error failure) { broken = true; cleanupFailure(primary, failure); }
        }

        private CRC32 crc() { crc.reset(); return crc; }

        /** Encode one complete independent frame with the enclosing codec's exact policy. */
        public Encoding encode(byte[] input) throws IOException {
            begin();
            try { return encodeInternal(input, this); }
            finally { busy = false; }
        }

        /** Decode one frame, recovering for later frames after ordinary malformed-input failures. */
        public byte[] decode(byte[] frame) throws IOException {
            begin();
            try { return decodeInternal(frame, this); }
            finally { busy = false; }
        }

        /** Writers retain this session, so they must be used on its owner thread before close. */
        public JsonLineTraceTransport.RecordWriter encodingWriter(JsonLineTraceTransport.RecordWriter writer) {
            Objects.requireNonNull(writer, "writer");
            checkOwner();
            if (closed || broken) throw new IllegalStateException("compression session is not available");
            return record -> writer.writeRecord(encode(record).frame);
        }

        /**
         * Pair batch formation with this session's encoder. The newly constructed batch stays
         * private until encoded, avoiding a second input snapshot. The returned transport retains
         * this session and must be used on its owner thread before close.
         */
        public CallbackExceptionTracker.BatchTransport batchSender(JsonLineTraceTransport.RecordWriter writer,
                boolean includeMessage, int maxFrames, int maxBatchBytes, int maxRecords) {
            Objects.requireNonNull(writer, "writer");
            checkOwner();
            if (closed || broken) throw new IllegalStateException("compression session is not available");
            return BatchTraceTransport.sender(frame -> {
                begin();
                Encoding encoded;
                try { encoded = encodeInternal(frame, this, false); }
                finally { busy = false; }
                writer.writeRecord(encoded.frame);
            }, includeMessage, maxFrames, maxBatchBytes, maxRecords);
        }

        /** Caller must arrange bounded worker execution and verify the sender independently. */
        public JsonLineTraceTransport.RecordWriter decodingWriter(JsonLineTraceTransport.RecordWriter writer) {
            Objects.requireNonNull(writer, "writer");
            checkOwner();
            if (closed || broken) throw new IllegalStateException("compression session is not available");
            return frame -> writer.writeRecord(decode(frame));
        }

        /** Close once on the owner thread, attempting both native ends even when one fails. */
        @Override public void close() {
            checkOwner();
            if (closed) return;
            if (busy) throw new IllegalStateException("compression session is already in use");
            closed = true;
            retainedScratch = null;
            Throwable first = null;
            try { if (deflater != null) deflater.end(); }
            catch (RuntimeException | Error failure) { first = failure; }
            try { if (inflater != null) inflater.end(); }
            catch (RuntimeException | Error failure) {
                if (first == null) first = failure;
                else if (TypeChecks.isFatal(failure) && !TypeChecks.isFatal(first)) {
                    addSuppressed(failure, first);
                    first = failure;
                } else addSuppressed(first, failure);
            }
            if (first != null) rethrowUnchecked(first);
        }
    }

    /**
     * A session that permits sequential use across threads while rejecting concurrent operations
     * and same-thread reentry. No operation waits for another operation or creates a worker.
     * Codec state is held exclusively during encode/decode and native cleanup. Writers run after
     * that exclusive section and may overlap or reenter; the caller owns their synchronization.
     * Closing this session does not wait for a writer already invoked, and closing a poller does
     * not wait for its claimed send or close this session. Coordinate those lifetimes explicitly.
     * The frame, reset, poison and input-snapshot rules are the same as for {@link Session}.
     */
    public final class SerializedSession implements AutoCloseable {
        private final ReentrantLock gate = new ReentrantLock();
        private final Session delegate;

        private SerializedSession() { this(OutputBufferStrategy.STANDARD); }

        private SerializedSession(OutputBufferStrategy strategy) { this.delegate = new Session(null, strategy); }

        private void enter() {
            if (gate.isHeldByCurrentThread() || !gate.tryLock()) {
                throw new IllegalStateException("serialized compression session is already in use");
            }
        }

        private void checkAvailable() {
            enter();
            try {
                if (delegate.closed || delegate.broken) {
                    throw new IllegalStateException("compression session is not available");
                }
            } finally {
                gate.unlock();
            }
        }

        /** Encode one independent frame; overlapping session operations are rejected. */
        public Encoding encode(byte[] input) throws IOException {
            enter();
            try { return delegate.encode(input); }
            finally { gate.unlock(); }
        }

        /** Decode one frame; malformed frames preserve the existing reset and recovery rules. */
        public byte[] decode(byte[] frame) throws IOException {
            enter();
            try { return delegate.decode(frame); }
            finally { gate.unlock(); }
        }

        /** The returned writer retains this session and invokes the supplied writer without the lock. */
        public JsonLineTraceTransport.RecordWriter encodingWriter(JsonLineTraceTransport.RecordWriter writer) {
            Objects.requireNonNull(writer, "writer");
            checkAvailable();
            return record -> writer.writeRecord(encode(record).frame);
        }

        /** The returned writer retains this session and invokes the supplied writer without the lock. */
        public JsonLineTraceTransport.RecordWriter decodingWriter(JsonLineTraceTransport.RecordWriter writer) {
            Objects.requireNonNull(writer, "writer");
            checkAvailable();
            return frame -> writer.writeRecord(decode(frame));
        }

        /**
         * Pair batch formation with this session. Its freshly constructed batch remains private
         * during encoding, so no additional input snapshot is required. The supplied writer runs
         * after the session lock is released and must be coordinated by the caller.
         */
        public CallbackExceptionTracker.BatchTransport batchSender(JsonLineTraceTransport.RecordWriter writer,
                boolean includeMessage, int maxFrames, int maxBatchBytes, int maxRecords) {
            Objects.requireNonNull(writer, "writer");
            checkAvailable();
            return BatchTraceTransport.sender(frame -> {
                Encoding encoded;
                enter();
                try {
                    delegate.begin();
                    try { encoded = encodeInternal(frame, delegate, false); }
                    finally { delegate.busy = false; }
                } finally {
                    gate.unlock();
                }
                writer.writeRecord(encoded.frame);
            }, includeMessage, maxFrames, maxBatchBytes, maxRecords);
        }

        /**
         * Close from any thread while idle. A concurrent operation is rejected; retry after it
         * finishes. Once a close finishes, later close calls are idempotent. Both native codecs
         * are ended according to the same failure rules as {@link Session#close()}.
         */
        @Override public void close() {
            enter();
            try { delegate.close(); }
            finally { gate.unlock(); }
        }
    }

    /** A reset failure poisons the session without replacing an earlier failure or fatal event. */
    private static void cleanupFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) { rethrowUnchecked(cleanup); return; }
        if (TypeChecks.isFatal(cleanup) && !TypeChecks.isFatal(primary)) {
            addSuppressed(cleanup, primary);
            rethrowUnchecked(cleanup);
            return;
        }
        addSuppressed(primary, cleanup);
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary == secondary) return;
        try { primary.addSuppressed(secondary); }
        catch (Throwable failure) {
            if (TypeChecks.isFatal(failure) && !TypeChecks.isFatal(primary)) throw TypeChecks.fatal(failure);
        }
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("unexpected codec cleanup failure", failure);
    }

    /**
     * Returns a complete independent frame or fails without producing a partial result. A bounded
     * snapshot is used consistently for compression and checksum; callers should avoid mutating
     * input while it is copied, because copying a concurrently modified array is not atomic.
     */
    public Encoding encode(byte[] input) throws IOException {
        return encodeInternal(input, null);
    }

    private Encoding encodeInternal(byte[] input, Session session) throws IOException {
        return encodeInternal(input, session, true);
    }

    private Encoding encodeInternal(byte[] input, Session session, boolean snapshot) throws IOException {
        Objects.requireNonNull(input, "input");
        if (input.length > maxPlainBytes) throw new IOException("plain trace exceeds limit");
        boolean rawFits = input.length <= maxFrameBytes - HEADER_BYTES;
        boolean attempt = input.length > 0 && (input.length >= thresholdBytes || !rawFits);
        // A RAW frame that needs no compression attempt copies the input once, straight into
        // the frame; that copy is also the snapshot the checksum covers (for every size).
        if (snapshot && !attempt && rawFits) {
            // The final payload is also the snapshot used by the checksum. Concurrent input
            // mutation during the copy is not atomic, just as with the ordinary clone path.
            ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + input.length).order(ByteOrder.BIG_ENDIAN);
            frame.position(HEADER_BYTES);
            frame.put(input);
            CRC32 crc = session == null ? new CRC32() : session.crc();
            crc.update(frame.array(), HEADER_BYTES, input.length);
            frame.position(0);
            frame.putInt(MAGIC).put((byte) VERSION).put((byte) RAW).putShort((short) 0);
            frame.putInt(input.length).putInt(input.length).putInt((int) crc.getValue());
            return new Encoding(frame.array(), input.length, Decision.BELOW_THRESHOLD);
        }
        if (snapshot) input = input.clone();
        byte[] payload = input;
        int payloadLength = input.length;
        int method = RAW;
        Decision decision = attempt ? Decision.INSUFFICIENT_SAVINGS : Decision.BELOW_THRESHOLD;
        if (attempt) {
            // Require a strict net improvement unless the only alternative is an oversized frame.
            long usefulLimit = (long) input.length - HEADER_BYTES - Math.max(1, minSavingBytes);
            int maxCandidate = rawFits
                    ? (int) Math.min((long) maxFrameBytes - HEADER_BYTES, Math.max(0L, usefulLimit))
                    : maxFrameBytes - HEADER_BYTES;
            if (maxCandidate > 0) {
                boolean staged = session != null && session.outputBufferStrategy == OutputBufferStrategy.STAGED
                        && maxCandidate > SESSION_SCRATCH_BYTES;
                int firstLimit = staged ? SESSION_SCRATCH_BYTES : maxCandidate;
                byte[] candidate = session == null ? new byte[maxCandidate] : session.scratchFor(firstLimit);
                Deflater compressor = session == null ? new Deflater(Deflater.BEST_SPEED) : session.deflater();
                Throwable primary = null;
                try {
                    if (algorithm == Algorithm.TRACE_SCHEMA_V1) compressor.setDictionary(DICTIONARY);
                    compressor.setInput(input);
                    compressor.finish();
                    int used = 0;
                    if (staged) {
                        int outputLimit = firstLimit;
                        while (!compressor.finished() && used < maxCandidate) {
                            if (used == outputLimit) {
                                byte[] expanded = new byte[maxCandidate];
                                System.arraycopy(candidate, 0, expanded, 0, used);
                                candidate = expanded;
                                outputLimit = maxCandidate;
                            }
                            int written = compressor.deflate(candidate, used, outputLimit - used);
                            if (written == 0) break;
                            used += written;
                        }
                    } else {
                        while (!compressor.finished() && used < maxCandidate) {
                            int written = compressor.deflate(candidate, used, maxCandidate - used);
                            if (written == 0) break;
                            used += written;
                        }
                    }
                    if (compressor.finished()) {
                        payload = candidate;
                        payloadLength = used;
                        method = algorithm == Algorithm.TRACE_SCHEMA_V1 ? TRACE_SCHEMA : ZLIB;
                        decision = rawFits ? Decision.COMPRESSED : Decision.COMPRESSED_TO_FIT;
                    }
                } catch (RuntimeException | Error failure) {
                    primary = failure;
                    throw failure;
                } finally {
                    if (session == null) compressor.end(); else session.resetDeflater(primary);
                }
            }
        }
        if (method == RAW && !rawFits) throw new IOException("trace frame exceeds limit");
        CRC32 crc = session == null ? new CRC32() : session.crc();
        crc.update(input);
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payloadLength).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(MAGIC).put((byte) VERSION).put((byte) method).putShort((short) 0);
        frame.putInt(input.length).putInt(payloadLength).putInt((int) crc.getValue()).put(payload, 0, payloadLength);
        return new Encoding(frame.array(), input.length, decision);
    }

    /**
     * Rejects unknown formats, bad bounds, trailing bytes, expansion beyond the limit and CRC
     * errors. Each input byte is read once: header fields into locals, then a RAW payload straight
     * into the returned plaintext (which is what the checksum covers) or a compressed payload into
     * a bounded snapshot before inflation. Concurrent mutation of the input cannot separate the
     * validated header and checksum from the returned bytes; the input is not retained.
     */
    public byte[] decode(byte[] frame) throws IOException {
        return decodeInternal(frame, null);
    }

    private byte[] decodeInternal(byte[] frame, Session session) throws IOException {
        if (frame == null || frame.length < HEADER_BYTES || frame.length > maxFrameBytes) {
            throw new IOException("invalid trace frame size");
        }
        ByteBuffer header = ByteBuffer.wrap(frame, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
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
            // The single payload copy is the snapshot: the checksum below covers exactly these bytes.
            System.arraycopy(frame, HEADER_BYTES, plain, 0, plainLength);
        } else {
            // Compressed payloads keep the bounded snapshot: native inflation consumes the input
            // incrementally across several calls and must see one stable byte sequence.
            frame = frame.clone();
            Inflater inflater = session == null ? new Inflater() : session.inflater();
            Throwable primary = null;
            try { inflate(frame, method, plain, inflater); }
            catch (IOException | RuntimeException | Error failure) { primary = failure; throw failure; }
            finally { if (session == null) inflater.end(); else session.resetInflater(primary); }
        }
        CRC32 crc = session == null ? new CRC32() : session.crc();
        crc.update(plain);
        if ((int) crc.getValue() != expectedCrc) throw new IOException("trace checksum mismatch");
        return plain;
    }

    private static void inflate(byte[] frame, int method, byte[] plain, Inflater inflater) throws IOException {
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
        }
    }

    /** Caller must arrange format agreement and worker execution; writer exceptions propagate. */
    public JsonLineTraceTransport.RecordWriter encodingWriter(JsonLineTraceTransport.RecordWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return record -> writer.writeRecord(encode(record).frame);
    }

    /**
     * Pair batch formation and compression on a caller-owned worker. The private batch frame is
     * encoded without another full input snapshot; ordinary {@link #encode(byte[])} and
     * {@link #encodingWriter(JsonLineTraceTransport.RecordWriter)} retain their snapshot contract.
     * A normal writer return is acceptance, not proof of durable delivery.
     */
    public CallbackExceptionTracker.BatchTransport batchSender(JsonLineTraceTransport.RecordWriter writer,
            boolean includeMessage, int maxFrames, int maxBatchBytes, int maxRecords) {
        Objects.requireNonNull(writer, "writer");
        return BatchTraceTransport.sender(frame -> writer.writeRecord(encodeInternal(frame, null, false).frame),
                includeMessage, maxFrames, maxBatchBytes, maxRecords);
    }

    /** Call from a bounded worker after authentication, not directly on a Binder thread. */
    public JsonLineTraceTransport.RecordWriter decodingWriter(JsonLineTraceTransport.RecordWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return frame -> writer.writeRecord(decode(frame));
    }
}
