package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Benchmark-only verbatim copies of the round-1 (main @ 94f6f63) JSONL encoder, batch sender and
 * TraceBatchDispatcher, so the round-2 changes can be compared in the same JMH run on a shared
 * host. Only package-private helpers were inlined (TypeChecks, putInt). Not production code.
 */
final class Round1Transport {
    private Round1Transport() { }

    /** Round-1 RecordWriter shape: one exact-length array per record. */
    interface RecordWriter {
        void writeRecord(byte[] utf8JsonLine) throws Exception;
    }

    private static final int FIELD_CHARS = 128;
    private static final int MESSAGE_CHARS = 512;
    static final int MAX_RESERVATION = MESSAGE_CHARS * 3 + 12;
    private static final int FIXED_BYTES = 240;
    private static final int MESSAGE_KEY_BYTES = 34;
    private static final int FRAME_KEY_BYTES = 56;
    private static final int NULL_FRAME_BYTES = 5;
    private static final byte[] HEX = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };
    private static final int MAGIC = 0x4d484231; // MHB1
    private static final int HEADER_BYTES = 8;
    private static final int INITIAL_FRAME_BYTES = 512;

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable throwable) {
        return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
    }

    @SuppressWarnings("removal")
    private static RuntimeException fatal(Throwable throwable) {
        if (throwable instanceof VirtualMachineError) throw (VirtualMachineError) throwable;
        if (throwable instanceof ThreadDeath) throw (ThreadDeath) throwable;
        return new IllegalStateException("not a fatal VM event", throwable);
    }

    /** Round-1 JsonLineTraceTransport.encode(event, includeMessage, maxFrames). */
    static byte[] encode(CallbackExceptionTracker.Event event, boolean includeMessage, int maxFrames) {
        Objects.requireNonNull(event, "event");
        if (maxFrames < 0 || maxFrames > 32) throw new IllegalArgumentException("maxFrames must be 0..32");
        Utf8 text = new Utf8();
        write(text, event, includeMessage, maxFrames);
        return text.finish();
    }

    /** Round-1 JsonLineTraceTransport.send: encode, then pass the exact-length copy. */
    static void send(RecordWriter writer, CallbackExceptionTracker.Event event, boolean includeMessage,
                     int maxFrames) throws Exception {
        writer.writeRecord(encode(event, includeMessage, maxFrames));
    }

    /** Round-1 BatchTraceTransport.sender body (bounds already validated by the caller). */
    static int sendBatch(RecordWriter writer, List<CallbackExceptionTracker.Event> events, boolean includeMessage,
                         int maxFrames, int maxFrameBytes, int maxRecords) throws Exception {
        Objects.requireNonNull(events, "events");
        Utf8 frame = Utf8.bounded(INITIAL_FRAME_BYTES, maxFrameBytes);
        frame.skip(HEADER_BYTES);
        int count = 0;
        for (CallbackExceptionTracker.Event event : events) {
            if (count == maxRecords || maxFrameBytes - frame.size() < 5) break;
            frame.beginRecord();
            write(frame, event, includeMessage, maxFrames);
            if (!frame.endRecord()) {
                if (count == 0) throw new IllegalArgumentException("trace record does not fit batch frame");
                break;
            }
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("empty batch");
        byte[] bytes = frame.finish();
        putInt(bytes, 0, MAGIC);
        putInt(bytes, 4, count);
        writer.writeRecord(bytes);
        return count;
    }

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
                if (isFatal(failure)) throw fatal(failure);
                messageError = true;
            }
        }
        StackTraceElement[] frames = null;
        boolean framesError = false;
        if (maxFrames != 0) {
            try {
                frames = throwable.getStackTrace();
            } catch (Throwable failure) {
                if (isFatal(failure)) throw fatal(failure);
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
        if (limit < value.length() && limit > 0 && Character.isHighSurrogate(value.charAt(limit - 1))
                && Character.isLowSurrogate(value.charAt(limit))) limit--;
        text.ascii('"');
        text.string(value, limit);
        text.ascii('"');
        if (value.length() > limit) text.ascii(",\"").ascii(name).ascii("Truncated\":true");
    }

    static final class Utf8 {
        private byte[] bytes;
        private int size;
        private final int overflowAt;
        private final int maxCapacity;
        private int recordStart = -1;
        private boolean overflowed;

        Utf8() {
            this(0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        private Utf8(int initialCapacity, int overflowAt, int maxCapacity) {
            this.bytes = new byte[initialCapacity];
            this.overflowAt = overflowAt;
            this.maxCapacity = maxCapacity;
        }

        static Utf8 bounded(int initialCapacity, int limit) {
            return new Utf8(Math.min(initialCapacity, limit), limit, limit + MAX_RESERVATION + 64);
        }

        int size() { return size; }

        void skip(int count) {
            reserve(count);
            size += count;
        }

        void beginRecord() {
            recordStart = size;
            skip(4);
        }

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

        void reserveRecord(int estimate) {
            reserve(Math.max(0, Math.min(estimate, overflowAt - size)));
        }

        private void reserve(int count) {
            if (size > overflowAt && recordStart >= 0) {
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
                        position = unicode(out, position, '\ufffd');
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

    /** Round-1 TraceBatchDispatcher.submit (verbatim apart from the inlined fatal checks). */
    static Attempt submit(Executor executor, CallbackExceptionTracker tracker,
                          CallbackExceptionTracker.BatchTransport transport, int limit) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(transport, "transport");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Attempt attempt = new Attempt(tracker, transport, limit);
        try {
            executor.execute(attempt::run);
        } catch (Throwable failure) {
            attempt.submissionFailed(failure);
            if (isFatal(failure)) throw fatal(failure);
        }
        return attempt;
    }

    /** Round-1 TraceBatchDispatcher.Attempt. */
    static final class Attempt implements Future<CallbackExceptionTracker.BatchAttempt> {
        private enum State { WAITING, RUNNING, CANCELLED, FINISHED }

        private CallbackExceptionTracker tracker;
        private CallbackExceptionTracker.BatchTransport transport;
        private final int limit;
        private final CountDownLatch done = new CountDownLatch(1);
        private State state = State.WAITING;
        private CallbackExceptionTracker.BatchAttempt result;
        private Throwable failure;
        private volatile Throwable submissionFailure;

        private Attempt(CallbackExceptionTracker tracker, CallbackExceptionTracker.BatchTransport transport, int limit) {
            this.tracker = tracker;
            this.transport = transport;
            this.limit = limit;
        }

        public Throwable submissionFailure() { return submissionFailure; }

        private synchronized void submissionFailed(Throwable problem) {
            submissionFailure = problem;
            if (state == State.WAITING) {
                failure = problem;
                tracker = null;
                transport = null;
                state = State.FINISHED;
                done.countDown();
            }
        }

        private void run() {
            CallbackExceptionTracker activeTracker;
            CallbackExceptionTracker.BatchTransport activeTransport;
            synchronized (this) {
                if (state != State.WAITING) return;
                state = State.RUNNING;
                activeTracker = tracker;
                activeTransport = transport;
            }
            try {
                result = activeTracker.tryDeliverBatch(activeTransport, limit);
            } catch (Throwable problem) {
                failure = problem;
                if (isFatal(problem)) throw fatal(problem);
            } finally {
                synchronized (this) {
                    tracker = null;
                    transport = null;
                    state = State.FINISHED;
                    done.countDown();
                }
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (this) {
                if (state != State.WAITING) return false;
                tracker = null;
                transport = null;
                state = State.CANCELLED;
                done.countDown();
            }
            return true;
        }

        @Override public synchronized boolean isCancelled() { return state == State.CANCELLED; }
        @Override public synchronized boolean isDone() {
            return state == State.CANCELLED || state == State.FINISHED;
        }

        @Override public CallbackExceptionTracker.BatchAttempt get() throws InterruptedException, ExecutionException {
            done.await();
            return outcome();
        }

        @Override public CallbackExceptionTracker.BatchAttempt get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (!done.await(timeout, unit)) throw new TimeoutException();
            return outcome();
        }

        private CallbackExceptionTracker.BatchAttempt outcome() throws ExecutionException {
            synchronized (this) {
                if (state == State.CANCELLED) throw new CancellationException();
                if (failure != null) throw new ExecutionException(failure);
                return result;
            }
        }
    }
}
